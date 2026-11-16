package com.meteorsnap.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferTest {

    @Test
    fun `按时间窗口与数量上限淘汰`() {
        val buffer = RingBuffer(
            maxDurationNanos = 5L,
            maxEntries = 3,
            copier = { value: String -> value }
        )

        buffer.addOwned(1L, "one")
        buffer.addOwned(3L, "three")
        buffer.addOwned(6L, "six")
        buffer.addOwned(7L, "seven")

        buffer.snapshot().use { snapshot ->
            assertEquals(listOf("three", "six", "seven"), snapshot.entries.map { it.value })
        }
        buffer.close()
    }

    @Test
    fun `快照是深拷贝且 extract 不破坏实时缓冲`() {
        val buffer = RingBuffer(
            maxDurationNanos = 100L,
            maxEntries = 10,
            copier = { value: ByteArray -> value.copyOf() }
        )
        val original = byteArrayOf(1, 2, 3)
        buffer.addCopy(10L, original)
        original[0] = 9

        buffer.extract(0L, 20L).use { snapshot ->
            assertTrue(snapshot.entries.single().value.contentEquals(byteArrayOf(1, 2, 3)))
        }
        assertEquals(1, buffer.size())
        buffer.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝时间戳倒退`() {
        val buffer = RingBuffer(
            maxDurationNanos = 100L,
            maxEntries = 10,
            copier = { value: Int -> value }
        )
        buffer.addOwned(20L, 1)
        buffer.addOwned(19L, 2)
    }
}
