package silver.wifiautoconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WIFI_STATE_DISABLED
import android.net.wifi.WifiManager.WIFI_STATE_ENABLED
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class WifiConnectService : Service() {

    companion object {
        private const val TAG = "WifiConnectService"
        const val CHANNEL_ID = "wifi_connect_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_SSID = "extra_ssid"
        const val EXTRA_INTERVAL = "extra_interval"
        const val ACTION_STATUS = "silver.wifiautoconnect.WIFI_STATUS"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_IS_CONNECTED = "extra_is_connected"

        var isRunning = false
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var handler: Handler

    private var targetSsid: String = "Silvers phone"
    private var intervalMs: Long = 10_000L
    private var attemptCount = 0

    // Текущий статус подключения — обновляется через NetworkCallback без лишних действий
    private var isConnected = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Слушаем изменения состояния WiFi чтобы понять — выключил пользователь или нет
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
            // DISABLED после того как сервис уже работал — пользователь выключил руками
            val prefs = getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
            when (state) {
                WIFI_STATE_DISABLED -> {
                    prefs.edit().putBoolean("user_disabled_wifi", true).apply()
                    Log.d(TAG, "WiFi disabled by user")
                }
                WIFI_STATE_ENABLED -> {
                    prefs.edit().putBoolean("user_disabled_wifi", false).apply()
                    Log.d(TAG, "WiFi enabled")
                }
            }
        }
    }

    private val connectRunnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                Log.e(TAG, "Error in connectRunnable: ${e.message}", e)
                sendStatus(getString(R.string.status_error, e.message ?: "?", attemptCount), false)
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        handler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetSsid = intent?.getStringExtra(EXTRA_SSID) ?: "Silvers phone"
        intervalMs = intent?.getLongExtra(EXTRA_INTERVAL, 10_000L) ?: 10_000L
        attemptCount = 0
        isRunning = true

        // Сохраняем состояние для восстановления после перезагрузки
        getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE).edit()
            .putBoolean("service_running", true)
            .putString("ssid", targetSsid)
            .putLong("interval_ms", intervalMs)
            .apply()

        // Сбрасываем флаг при старте сервиса — WiFi сейчас включён
        getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE).edit()
            .putBoolean("user_disabled_wifi", false).apply()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)))
        registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))

        // Подписываемся на изменения сети — без лишних su-вызовов
        registerNetworkCallback()

        handler.removeCallbacks(connectRunnable)
        handler.post(connectRunnable)

        // Сразу сообщаем UI что сервис работает — важно после перезагрузки,
        // когда MainActivity открывается до первого tick()
        sendStatus(getString(R.string.status_service_launched, targetSsid), false)

        Log.d(TAG, "Service started. SSID=$targetSsid interval=${intervalMs}ms")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        handler.removeCallbacks(connectRunnable)
        unregisterNetworkCallback()
        try { unregisterReceiver(wifiStateReceiver) } catch (_: Exception) {}
        RootShell.close()

        // Запоминаем что сервис остановлен — не запускать после перезагрузки
        getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE).edit()
            .putBoolean("service_running", false)
            .apply()

        sendStatus(getString(R.string.status_service_stopped), false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // -------------------------------------------------------------------------
    // NetworkCallback — отслеживаем подключение без root
    // -------------------------------------------------------------------------

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: ""
                if (ssid == targetSsid) {
                    Log.d(TAG, "Network available: $ssid")
                    isConnected = true
                    sendStatus(getString(R.string.status_connected, targetSsid, attemptCount), true)
                    updateNotification(getString(R.string.status_connected, targetSsid, attemptCount))
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                isConnected = false
                sendStatus(getString(R.string.status_network_lost, targetSsid), false)
                updateNotification(getString(R.string.status_network_lost, targetSsid))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: ""
                isConnected = ssid == targetSsid && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
    }

    // -------------------------------------------------------------------------
    // Main tick — вызывается раз в intervalMs
    // -------------------------------------------------------------------------

    private fun tick() {
        // Если уже подключены — ничего не делаем, NetworkCallback сам уведомит об обрыве
        if (isConnected) {
            Log.d(TAG, "tick: already connected, skipping")
            return
        }

        attemptCount++
        Log.d(TAG, "tick: attempt $attemptCount, target=$targetSsid")

        if (!wifiManager.isWifiEnabled) {
            // Если пользователь сам выключил WiFi — не включаем принудительно
            val wifiDisabledByUser = getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
                .getBoolean("user_disabled_wifi", false)
            if (wifiDisabledByUser) {
                sendStatus(getString(R.string.status_wifi_disabled_manual, attemptCount), false)
                updateNotification(getString(R.string.status_wifi_disabled_manual, attemptCount))
                return
            }
            sendStatus(getString(R.string.status_wifi_enabling, attemptCount), false)
            updateNotification(getString(R.string.notification_starting))
            try {
                RootShell.exec("svc wifi enable")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable wifi: ${e.message}")
            }
            return
        }

        sendStatus(getString(R.string.status_connecting, targetSsid, attemptCount), false)
        updateNotification(getString(R.string.status_connecting, targetSsid, attemptCount))

        connectViaRoot(targetSsid)
    }

    // -------------------------------------------------------------------------
    // Root connection
    // -------------------------------------------------------------------------

    /**
     * Определяет название WiFi-интерфейса через /sys/class/net или ip link show.
     */
    private fun detectWifiInterface(): String {
        return try {
            val sysOut = RootShell.exec("ls /sys/class/net/")
            val iface = sysOut.split(Regex("\\s+")).map { it.trim() }.firstOrNull { it.startsWith("wl") }
            if (!iface.isNullOrBlank()) {
                Log.d(TAG, "WiFi interface detected via /sys/class/net: $iface")
                return iface
            }
            val ipOut = RootShell.exec("ip link show")
            val match = Regex("\\d+:\\s+(wl\\S+):").find(ipOut)
            if (match != null) {
                Log.d(TAG, "WiFi interface detected via ip link: ${match.groupValues[1]}")
                return match.groupValues[1]
            }
            Log.w(TAG, "Could not detect WiFi interface, using wlan0")
            "wlan0"
        } catch (e: Exception) {
            Log.e(TAG, "detectWifiInterface error: ${e.message}")
            "wlan0"
        }
    }

    /**
     * Подключается к сети через root (один постоянный su-процесс).
     * Порядок попыток:
     *  1. cmd wifi connect-network (Android 10+ shell API)
     *  2. wpa_cli select_network (классический способ)
     */
    private fun connectViaRoot(ssid: String) {
        try {
            if (!RootShell.isAvailable()) {
                sendStatus(getString(R.string.status_no_root, attemptCount), false)
                updateNotification(getString(R.string.status_no_root, attemptCount))
                return
            }

            val iface = detectWifiInterface()
            Log.d(TAG, "connectViaRoot: ssid=[$ssid] iface=$iface attempt=$attemptCount")

            // ── Способ 1: wpa_cli (основной — работает с любым типом безопасности) ──
            // wpa_cli может выводить "Selected interface '...'" перед таблицей,
            // поэтому берём только строки где первый столбец — число (network id)
            val listOutput = RootShell.exec("wpa_cli -i $iface list_networks 2>&1")
            Log.d(TAG, "wpa_cli list_networks raw: $listOutput")

            val networkId = listOutput.lines()
                .filter { it.isNotBlank() }
                .firstOrNull { line ->
                    val parts = line.split("\t")
                    if (parts.size < 2) return@firstOrNull false
                    val id = parts[0].trim()
                    val lineSsid = parts[1].trim()
                    // id должен быть числом, SSID совпадает
                    id.all { it.isDigit() } && lineSsid == ssid
                }
                ?.split("\t")?.firstOrNull()?.trim()

            Log.d(TAG, "wpa_cli found networkId=$networkId for ssid=[$ssid]")

            if (networkId != null) {
                // Отключаемся и выбираем нужную сеть
                RootShell.exec("wpa_cli -i $iface disable_network all 2>&1")
                RootShell.exec("wpa_cli -i $iface select_network $networkId 2>&1")
                RootShell.exec("wpa_cli -i $iface reassociate 2>&1")
                sendStatus(getString(R.string.status_command_sent, iface, attemptCount), false)
                Log.d(TAG, "wpa_cli select_network $networkId sent")
                return
            }

            // ── Способ 2: cmd wifi connect-network (Android 10+, saved network) ──
            // Не указываем тип безопасности — Android сам определит из сохранённого профиля
            val cmdSaved = RootShell.exec("cmd wifi connect-network \"$ssid\" 2>&1")
            Log.d(TAG, "cmd wifi (no-sec) result: $cmdSaved")
            if (!cmdSaved.contains("error", ignoreCase = true) &&
                !cmdSaved.contains("Exception", ignoreCase = true)) {
                sendStatus(getString(R.string.status_command_sent, iface, attemptCount), false)
                return
            }

            // ── Способ 3: wpa_cli без указания интерфейса (некоторые прошивки) ──
            val listNoIface = RootShell.exec("wpa_cli list_networks 2>&1")
            Log.d(TAG, "wpa_cli (no iface) list: $listNoIface")
            val networkIdNoIface = listNoIface.lines()
                .filter { it.isNotBlank() }
                .firstOrNull { line ->
                    val parts = line.split("\t")
                    if (parts.size < 2) return@firstOrNull false
                    val id = parts[0].trim()
                    val lineSsid = parts[1].trim()
                    id.all { it.isDigit() } && lineSsid == ssid
                }
                ?.split("\t")?.firstOrNull()?.trim()

            if (networkIdNoIface != null) {
                RootShell.exec("wpa_cli select_network $networkIdNoIface 2>&1")
                RootShell.exec("wpa_cli reassociate 2>&1")
                sendStatus(getString(R.string.status_command_sent, "default", attemptCount), false)
                Log.d(TAG, "wpa_cli (no iface) select_network $networkIdNoIface sent")
                return
            }

            // Все способы исчерпаны
            Log.w(TAG, "Network [$ssid] not found by any method. wpa_cli output: $listOutput")
            sendStatus(getString(R.string.status_network_not_found, ssid, attemptCount), false)

        } catch (e: Exception) {
            Log.e(TAG, "connectViaRoot error: ${e.message}", e)
            sendStatus(getString(R.string.status_error, e.message ?: "?", attemptCount), false)
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun sendStatus(text: String, isConnected: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS_TEXT, text)
            putExtra(EXTRA_IS_CONNECTED, isConnected)
        })
    }
}
