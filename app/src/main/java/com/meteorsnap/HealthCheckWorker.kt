package com.meteorsnap

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 每 15 分钟进行一次轻量健康检查。
 *
 * 注意：PeriodicWorkRequest 的 15 分钟是系统允许的最小周期，并非精确定时。Doze 下可能延迟。
 * Worker 绝不直接启动 camera FGS，只在用户原本希望监测、但服务/视频心跳已经失效时发通知。
 */
class HealthCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences(
            MeteorDetectService.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val monitoringWanted = preferences.getBoolean(
            MeteorDetectService.PREF_MONITORING_WANTED,
            false
        )

        if (monitoringWanted && !MeteorDetectService.isPipelineHealthy()) {
            showRestoreNotification()
        }
        return Result.success()
    }

    private fun showRestoreNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.health_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val openActivity = PendingIntent.getActivity(
            applicationContext,
            2001,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_meteor)
            .setContentTitle(applicationContext.getString(R.string.restore_monitor_title))
            .setContentText(applicationContext.getString(R.string.restore_monitor_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    applicationContext.getString(R.string.restore_monitor_text)
                )
            )
            .setContentIntent(openActivity)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(applicationContext)
                .notify(RESTORE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "MeteorSnap.HealthCheck"
        private const val CHANNEL_ID = "meteor_health"
        private const val RESTORE_NOTIFICATION_ID = 2102
    }
}
