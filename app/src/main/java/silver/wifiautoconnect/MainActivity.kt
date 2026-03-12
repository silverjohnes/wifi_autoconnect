package silver.wifiautoconnect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editSsid: EditText
    private lateinit var btnWifiSettings: Button
    private lateinit var btnClear: Button
    private lateinit var tvSsidHint: TextView
    private lateinit var spinnerInterval: Spinner
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusDot: TextView

    private val intervalValues = longArrayOf(5_000, 10_000, 20_000, 30_000, 60_000)
    private var serviceRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(WifiConnectService.EXTRA_STATUS_TEXT) ?: return
            val connected = intent.getBooleanExtra(WifiConnectService.EXTRA_IS_CONNECTED, false)
            updateStatus(text, connected)
        }
    }

    companion object { private const val PERM_REQUEST = 100 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editSsid        = findViewById(R.id.editSsid)
        btnWifiSettings = findViewById(R.id.btnWifiSettings)
        btnClear        = findViewById(R.id.btnClear)
        tvSsidHint      = findViewById(R.id.tvSsidHint)
        spinnerInterval = findViewById(R.id.spinnerInterval)
        btnToggle       = findViewById(R.id.btnToggle)
        tvStatus        = findViewById(R.id.tvStatus)
        tvStatusDot     = findViewById(R.id.tvStatusDot)

        setupIntervalSpinner()
        restoreSsidField()

        serviceRunning = isServiceRunningFromPrefs()
        refreshToggleButton()
        if (!serviceRunning) updateStatus(getString(R.string.status_service_stopped), false)

        btnToggle.setOnClickListener { if (serviceRunning) stopWifiService() else startWifiService() }

        btnWifiSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        btnClear.setOnClickListener {
            val current = getCurrentSsid()
            if (current.isNotBlank()) {
                // Пользователь подключён — поле само заполнится текущей сетью, предупреждаем
                Toast.makeText(this, getString(R.string.toast_clear_while_connected), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE).edit()
                .remove("ssid").apply()
            editSsid.setText("")
            editSsid.hint = getString(R.string.hint_ssid)
            btnWifiSettings.visibility = View.VISIBLE
        }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(WifiConnectService.ACTION_STATUS))
        serviceRunning = isServiceRunningFromPrefs()
        refreshToggleButton()
        if (serviceRunning) {
            val ssid = getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
                .getString("ssid", "") ?: ""
            if (tvStatus.text == getString(R.string.status_service_stopped)) {
                updateStatus(getString(R.string.status_service_running, ssid), false)
            }
        } else {
            updateStatus(getString(R.string.status_service_stopped), false)
        }
        if (editSsid.text.isBlank()) restoreSsidField()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun restoreSsidField() {
        val prefs = getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
        val savedSsid = prefs.getString("ssid", "") ?: ""
        if (savedSsid.isNotBlank()) {
            editSsid.setText(savedSsid)
            btnWifiSettings.visibility = View.GONE
            return
        }
        val current = getCurrentSsid()
        if (current.isNotBlank()) {
            editSsid.setText(current)
            btnWifiSettings.visibility = View.GONE
        } else {
            editSsid.hint = getString(R.string.hint_ssid)
            btnWifiSettings.visibility = View.VISIBLE
        }
    }

    private fun getCurrentSsid(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wm.connectionInfo?.ssid?.removeSurrounding("\"") ?: ""
            if (ssid == "<unknown ssid>" || ssid == "0x") "" else ssid
        } catch (_: Exception) { "" }
    }

    private fun setupIntervalSpinner() {
        // Берём массив из строковых ресурсов — локализован автоматически
        val labels = resources.getStringArray(R.array.interval_labels)
        // Используем собственные layout-файлы чтобы тема MaterialComponents
        // не перекрывала цвет текста спиннера
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerInterval.adapter = adapter

        val prefs = getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
        val saved = prefs.getLong("interval_ms", 10_000L)
        spinnerInterval.setSelection(intervalValues.indexOfFirst { it == saved }.takeIf { it >= 0 } ?: 1)
    }

    private fun startWifiService() {
        val ssid = editSsid.text.toString().trim()
        if (ssid.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_enter_ssid), Toast.LENGTH_SHORT).show()
            return
        }
        val intervalMs = intervalValues[spinnerInterval.selectedItemPosition]
        getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE).edit()
            .putBoolean("service_running", true)
            .putString("ssid", ssid)
            .putLong("interval_ms", intervalMs)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, WifiConnectService::class.java).apply {
            putExtra(WifiConnectService.EXTRA_SSID, ssid)
            putExtra(WifiConnectService.EXTRA_INTERVAL, intervalMs)
        })
        serviceRunning = true
        refreshToggleButton()
        updateStatus(getString(R.string.status_service_starting), false)
    }

    private fun stopWifiService() {
        getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE).edit()
            .putBoolean("service_running", false).apply()
        stopService(Intent(this, WifiConnectService::class.java))
        serviceRunning = false
        refreshToggleButton()
        updateStatus(getString(R.string.status_service_stopped), false)
    }

    private fun isServiceRunningFromPrefs(): Boolean {
        val prefs = getSharedPreferences("wifi_autoconnect", Context.MODE_PRIVATE)
        return prefs.getBoolean("service_running", false) || WifiConnectService.isRunning
    }

    private fun refreshToggleButton() {
        if (serviceRunning) {
            btnToggle.text = getString(R.string.btn_stop_service)
            btnToggle.setBackgroundColor(0xFFE53935.toInt())
        } else {
            btnToggle.text = getString(R.string.btn_start_service)
            btnToggle.setBackgroundColor(0xFF43A047.toInt())
        }
    }

    private fun updateStatus(text: String, connected: Boolean) {
        tvStatus.text = text
        tvStatusDot.text = if (connected) "🟢" else "🔴"
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERM_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) restoreSsidField()
    }
}
