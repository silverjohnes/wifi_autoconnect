package silver.wifiautoconnect

import android.util.Log

/**
 * Держит один постоянный root-процесс (su).
 * Все команды выполняются в нём — Magisk спрашивает разрешение только один раз.
 */
object RootShell {

    private val TAG = "RootShell"

    private var process: Process? = null
    private var writer: java.io.OutputStreamWriter? = null
    private var reader: java.io.BufferedReader? = null

    @Synchronized
    fun isAvailable(): Boolean {
        return try {
            ensureOpen()
            val result = exec("echo OK")
            result.trim() == "OK"
        } catch (e: Exception) {
            Log.e(TAG, "Root not available: ${e.message}")
            false
        }
    }

    /**
     * Выполняет команду и возвращает весь stdout до маркера.
     * Возвращает null при ошибке.
     */
    @Synchronized
    fun exec(command: String): String {
        ensureOpen()
        val marker = "---END---"
        writer!!.write("$command; echo $marker\n")
        writer!!.flush()

        val sb = StringBuilder()
        var line: String?
        while (true) {
            line = reader!!.readLine() ?: break
            if (line == marker) break
            sb.appendLine(line)
        }
        return sb.toString()
    }

    @Synchronized
    fun close() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
    }

    private fun ensureOpen() {
        if (process?.isAlive == true) return
        close()
        process = Runtime.getRuntime().exec(arrayOf("su"))
        writer = java.io.OutputStreamWriter(process!!.outputStream)
        reader = java.io.BufferedReader(java.io.InputStreamReader(process!!.inputStream))
    }
}
