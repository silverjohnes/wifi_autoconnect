package silver.wifiautoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * При BOOT_COMPLETED ставим задачу в WorkManager.
 * WorkManager обходит ограничение Android 8+ на запуск foreground service
 * из BroadcastReceiver — он запускается как foreground worker и уже оттуда
 * стартует наш сервис.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("service_running", false)) return

        val ssid = prefs.getString("ssid", "") ?: ""
        val interval = prefs.getLong("interval_ms", 10_000L)
        if (ssid.isBlank()) return

        val request = OneTimeWorkRequestBuilder<BootStartWorker>()
            .setInputData(workDataOf(
                "ssid" to ssid,
                "interval_ms" to interval
            ))
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
