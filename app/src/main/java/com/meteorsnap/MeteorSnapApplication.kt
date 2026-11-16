package com.meteorsnap

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 进程入口只负责安排低频健康检查，不在后台擅自拉起相机前台服务。
 *
 * Android 12+ 普遍禁止后台启动前台服务；Android 14+ 还会在 camera FGS 启动时立即校验
 * while-in-use CAMERA 权限。因此 Worker 发现异常后只能发“点击恢复”通知，让用户回到可见
 * Activity 再启动服务。
 */
class MeteorSnapApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(
            15,
            TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HealthCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
