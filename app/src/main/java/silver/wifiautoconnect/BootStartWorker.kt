package silver.wifiautoconnect

import android.content.Context
import silver.wifiautoconnect.R
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Worker запускается WorkManager'ом после загрузки устройства.
 * Работает в foreground — имеет право запустить foreground service.
 */
class BootStartWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ssid = inputData.getString("ssid") ?: return Result.failure()
        val intervalMs = inputData.getLong("interval_ms", 10_000L)

        // Запускаем сервис
        val serviceIntent = Intent(context, WifiConnectService::class.java).apply {
            putExtra(WifiConnectService.EXTRA_SSID, ssid)
            putExtra(WifiConnectService.EXTRA_INTERVAL, intervalMs)
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "boot_worker_channel"
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, context.getString(R.string.boot_worker_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("WiFi AutoConnect")
            .setContentText(context.getString(R.string.boot_worker_notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        return ForegroundInfo(2, notification)
    }
}
