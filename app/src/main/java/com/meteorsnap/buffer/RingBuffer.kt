package com.meteorsnap.buffer

import java.io.Closeable
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 带单调时间戳的数据项。
 *
 * 时间戳必须来自 [android.os.SystemClock.elapsedRealtimeNanos] 或同类单调时钟，不能使用
 * 会因用户校时、时区或网络授时而跳变的 `System.currentTimeMillis()`。
 */
data class Timestamped<T : Any>(
    val timestampNanos: Long,
    val value: T
)

/**
 * 环形缓冲生成的独立快照。
 *
 * 快照拥有其中所有 [value][Timestamped.value] 的所有权，调用方必须使用 `use { ... }`
 * 或显式调用 [close]。关闭后，快照中的 native 对象可能已被释放，不得继续访问。
 * 同一个快照不应在一个线程读取的同时由另一个线程关闭。
 */
class RingSnapshot<T : Any> internal constructor(
    entries: List<Timestamped<T>>,
    private val releaser: (T) -> Unit,
    private val onReleaseError: (Throwable) -> Unit
) : Closeable {

    // 同时复制容器并包装为不可修改列表，防止调用方通过向下转型篡改所有权集合。
    private val ownedEntries: List<Timestamped<T>> =
        Collections.unmodifiableList(ArrayList(entries))
    private val closed = AtomicBoolean(false)

    val entries: List<Timestamped<T>>
        get() {
            check(!closed.get()) { "RingSnapshot 已关闭" }
            return ownedEntries
        }

    /** 幂等关闭；即使某个元素释放失败，也会继续尝试释放其余元素。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownedEntries.forEach { entry ->
            releaseBestEffort(entry.value, releaser, onReleaseError)
        }
    }
}

/**
 * 基于时间窗口的线程安全泛型环形缓冲。
 *
 * [ConcurrentLinkedDeque] 保证单个容器操作的并发安全；额外的 [ReentrantLock] 用于保证
 * “校验时间戳、插入、淘汰”和“复制完整快照”等复合操作的原子性。
 *
 * 所有权约定：
 *
 * 1. [addOwned] 正常返回后，`value` 的所有权转移给本缓冲，调用方不得再修改或释放它；
 *    方法抛出参数/状态异常时，所有权仍属于调用方。
 * 2. UVC 库会复用回调中的 `Mat` 或 `ByteArray` 时，应调用 [addCopy]。
 * 3. [snapshot] 与 [extract] 都返回深拷贝，调用方必须关闭 [RingSnapshot]。
 * 4. [copier] 必须返回能够独立释放的对象；不可变对象只有在 [releaser] 为空操作时才可
 *    安全地返回自身。`Mat` 推荐 `copier = { it.clone() }`、
 *    `releaser = { it.release() }`。
 * 5. [copier]、[releaser] 和 [onReleaseError] 不得回调本缓冲；其中清理回调会在锁外执行。
 *
 * @param maxDurationNanos 最多保留的时间跨度。
 * @param maxEntries 最大元素数，用于防止异常输入洪峰耗尽内存。
 * @param copier 创建元素的独立副本。
 * @param releaser 淘汰、清空或关闭元素时释放其资源。
 * @param onReleaseError 清理失败时的上报入口；清理异常不会破坏已经完成的所有权转移。
 */
class RingBuffer<T : Any>(
    private val maxDurationNanos: Long,
    private val maxEntries: Int,
    private val copier: (T) -> T,
    private val releaser: (T) -> Unit = {},
    private val onReleaseError: (Throwable) -> Unit = {}
) : Closeable {

    private val deque = ConcurrentLinkedDeque<Timestamped<T>>()
    private val lock = ReentrantLock()

    // 下列字段始终在 lock 内读写，不需要再使用 AtomicInteger。
    private var entryCount = 0
    private var lastTimestampNanos: Long? = null
    private var closed = false

    init {
        require(maxDurationNanos > 0L) { "maxDurationNanos 必须大于 0" }
        require(maxEntries > 0) { "maxEntries 必须大于 0" }
    }

    /**
     * 零拷贝入队。只有本方法正常返回后，`value` 的所有权才转移给缓冲。
     * 相机回调对象仍会被生产者复用时，请改用 [addCopy]。
     */
    fun addOwned(timestampNanos: Long, value: T) {
        require(timestampNanos >= 0L) { "timestampNanos 不能为负数" }

        val evicted = ArrayList<T>()
        lock.withLock {
            check(!closed) { "RingBuffer 已关闭" }
            val previous = lastTimestampNanos
            require(previous == null || timestampNanos >= previous) {
                "时间戳必须单调不减：previous=$previous, current=$timestampNanos"
            }

            deque.addLast(Timestamped(timestampNanos, value))
            entryCount++
            lastTimestampNanos = timestampNanos

            // 两个操作数的取值范围保证这里不会发生 Long 下溢。
            val cutoffNanos = timestampNanos - maxDurationNanos
            while (true) {
                val oldest = deque.peekFirst() ?: break
                val expired = oldest.timestampNanos < cutoffNanos
                val tooMany = entryCount > maxEntries
                if (!expired && !tooMany) break

                deque.pollFirst()?.let { removed ->
                    entryCount--
                    evicted.add(removed.value)
                }
            }
        }

        // 新元素已经成功提交。释放旧元素失败只能上报，不能向调用方抛出并制造所有权歧义。
        evicted.forEach { value ->
            releaseBestEffort(value, releaser, onReleaseError)
        }
    }

    /**
     * 复制后入队，适合马上会被相机库复用或回收的回调对象。
     * 若校验失败，刚创建的副本会在本方法内释放。
     */
    fun addCopy(timestampNanos: Long, source: T) {
        val ownedCopy = copier(source)
        var transferred = false
        try {
            addOwned(timestampNanos, ownedCopy)
            transferred = true
        } finally {
            if (!transferred) {
                releaseBestEffort(ownedCopy, releaser, onReleaseError)
            }
        }
    }

    /**
     * 返回闭区间 `[fromInclusiveNanos, toInclusiveNanos]` 的非破坏性深拷贝快照。
     * 快照中的条目保持时间戳升序。
     */
    fun snapshot(
        fromInclusiveNanos: Long,
        toInclusiveNanos: Long
    ): RingSnapshot<T> {
        require(fromInclusiveNanos >= 0L) { "fromInclusiveNanos 不能为负数" }
        require(toInclusiveNanos >= fromInclusiveNanos) {
            "toInclusiveNanos 必须大于等于 fromInclusiveNanos"
        }

        val copied = ArrayList<Timestamped<T>>()
        try {
            lock.withLock {
                check(!closed) { "RingBuffer 已关闭" }
                for (entry in deque) {
                    when {
                        entry.timestampNanos < fromInclusiveNanos -> continue
                        entry.timestampNanos > toInclusiveNanos -> break
                        else -> copied.add(
                            Timestamped(entry.timestampNanos, copier(entry.value))
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            // copier 中途失败时，释放已经成功创建的所有独立副本。
            copied.forEach { entry ->
                releaseBestEffort(entry.value, releaser, onReleaseError)
            }
            throw error
        }

        return RingSnapshot(copied, releaser, onReleaseError)
    }

    /** 返回当前全部元素的非破坏性深拷贝快照。 */
    fun snapshot(): RingSnapshot<T> = snapshot(0L, Long.MAX_VALUE)

    /**
     * 名称与业务需求保持一致；语义与 [snapshot] 相同，不会从实时缓冲中删除元素。
     */
    fun extract(
        fromInclusiveNanos: Long,
        toInclusiveNanos: Long
    ): RingSnapshot<T> = snapshot(fromInclusiveNanos, toInclusiveNanos)

    fun size(): Int = lock.withLock { entryCount }

    fun isEmpty(): Boolean = lock.withLock { entryCount == 0 }

    fun oldestTimestampNanos(): Long? = lock.withLock {
        deque.peekFirst()?.timestampNanos
    }

    fun newestTimestampNanos(): Long? = lock.withLock {
        deque.peekLast()?.timestampNanos
    }

    /** 清空但不关闭缓冲；所有元素均在锁外释放。 */
    fun clear() {
        val removed = lock.withLock {
            if (closed) null else {
                lastTimestampNanos = null
                drainLocked()
            }
        } ?: return

        removed.forEach { value ->
            releaseBestEffort(value, releaser, onReleaseError)
        }
    }

    /** 幂等关闭；关闭后不再允许入队或创建快照。 */
    override fun close() {
        val removed = lock.withLock {
            if (closed) null else {
                closed = true
                lastTimestampNanos = null
                drainLocked()
            }
        } ?: return

        removed.forEach { value ->
            releaseBestEffort(value, releaser, onReleaseError)
        }
    }

    /** 仅可在持有 lock 时调用。 */
    private fun drainLocked(): List<T> {
        val removed = ArrayList<T>(entryCount)
        while (true) {
            val entry = deque.pollFirst() ?: break
            removed.add(entry.value)
        }
        entryCount = 0
        return removed
    }
}

/**
 * 清理路径采用 best-effort：单个对象失败不能阻止其余对象释放，也不能反向破坏所有权语义。
 */
private fun <T> releaseBestEffort(
    value: T,
    releaser: (T) -> Unit,
    onReleaseError: (Throwable) -> Unit
) {
    try {
        releaser(value)
    } catch (error: Throwable) {
        try {
            onReleaseError(error)
        } catch (_: Throwable) {
            // 错误上报本身失败时也不能让清理路径抛出。
        }
    }
}
