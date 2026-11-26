package com.tgwsproxy19

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

/** Мост между HTML-интерфейсом (WebView) и нативным слоем. */
class WebAppBridge(
    private val activity: MainActivity,
    private val prefs: SharedPreferences
) {

    @JavascriptInterface
    fun getState(): String {
        val json = JSONObject()
        json.put("running", ProxyRuntime.running)
        json.put("sleeping", ProxyRuntime.sleeping)
        json.put("status", ProxyRuntime.status)
        json.put("error", ProxyRuntime.error ?: "")
        json.put("port", ProxyRuntime.port)
        json.put("secret", ProxyRuntime.secret)
        return json.toString()
    }

    @JavascriptInterface
    fun getSettings(): String {
        val json = JSONObject()
        json.put("port", prefs.getInt(KEY_PORT, 1445))
        json.put("secret", prefs.getString(KEY_SECRET, "").orEmpty())
        json.put("noH2", prefs.getBoolean(KEY_NO_H2, true))
        json.put("dcIp", prefs.getString(KEY_DC_IP, DEFAULT_DC_IP).orEmpty())
        json.put("autostart", prefs.getBoolean(KEY_AUTOSTART, true))
        json.put("bgMode", prefs.getString(KEY_BG_MODE, "saver"))
        json.put("bgPercent", prefs.getInt(KEY_BG_PERCENT, 15))
        json.put("snow", prefs.getBoolean(KEY_SNOW, true))
        return json.toString()
    }

    @JavascriptInterface
    fun saveSettings(port: Int, secret: String, noH2: Boolean, dcIp: String) {
        prefs.edit()
            .putInt(KEY_PORT, port)
            .putString(KEY_SECRET, secret.lowercase(Locale.US))
            .putBoolean(KEY_NO_H2, noH2)
            .putString(KEY_DC_IP, dcIp)
            .apply()
    }

    @JavascriptInterface
    fun setAutostart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART, enabled).apply()
    }

    @JavascriptInterface
    fun setBackgroundPolicy(mode: String, percent: Int) {
        prefs.edit()
            .putString(KEY_BG_MODE, mode)
            .putInt(KEY_BG_PERCENT, percent.coerceIn(5, 60))
            .apply()
    }

    @JavascriptInterface
    fun setSnow(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNOW, enabled).apply()
    }

    @JavascriptInterface
    fun generateSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val builder = StringBuilder(32)
        for (b in bytes) builder.append(String.format(Locale.US, "%02x", b))
        return builder.toString()
    }

    @JavascriptInterface
    fun startProxy(port: Int, secret: String, noH2: Boolean, dcIp: String) {
        saveSettings(port, secret, noH2, dcIp)
        ProxyRuntime.port = port
        ProxyRuntime.secret = secret.lowercase(Locale.US)
        ProxyRuntime.status = "Запуск…"
        ProxyRuntime.error = null

        val intent = Intent(activity, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
            putExtra(ProxyForegroundService.EXTRA_PORT, port)
            putExtra(ProxyForegroundService.EXTRA_SECRET, secret.lowercase(Locale.US))
            putExtra(ProxyForegroundService.EXTRA_NO_H2, noH2)
            putExtra(ProxyForegroundService.EXTRA_DC_IP, dcIp)
        }
        ContextCompat.startForegroundService(activity, intent)
    }

    @JavascriptInterface
    fun stopProxy() {
        ProxyRuntime.status = "Остановка…"
        activity.startService(
            Intent(activity, ProxyForegroundService::class.java)
                .setAction(ProxyForegroundService.ACTION_STOP)
        )
    }

    @JavascriptInterface
    fun getStats(): String = PyBridge.stats()

    /** Пробует TCP-коннект к локальному прокси. "" — порт отвечает. */
    @JavascriptInterface
    fun testConnection(port: Int): String {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500)
            }
            ""
        } catch (e: Exception) {
            e.message ?: "нет соединения"
        }
    }

    @JavascriptInterface
    fun getLogs(): String = PyBridge.logs()

    @JavascriptInterface
    fun clearLogs(): Boolean {
        PyBridge.clearLogs()
        return true
    }

    @JavascriptInterface
    fun openTelegram(port: Int, secret: String) {
        activity.runOnUiThread { activity.openTelegram(port, secret.lowercase(Locale.US)) }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread { activity.openUrl(url) }
    }

    @JavascriptInterface
    fun copyText(text: String) {
        val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("TgWsProxy19", text))
    }

    @JavascriptInterface
    fun requestBattery() {
        activity.runOnUiThread { activity.requestBatteryExemption() }
    }

    @JavascriptInterface
    fun toast(message: String) {
        activity.runOnUiThread { activity.toast(message) }
    }

    @JavascriptInterface
    fun version(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    companion object {
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret"
        private const val KEY_NO_H2 = "no_h2"
        private const val KEY_DC_IP = "dc_ip"
        private const val DEFAULT_DC_IP = "4:149.154.167.220"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_BG_MODE = "bg_mode"
        private const val KEY_BG_PERCENT = "bg_percent"
        private const val KEY_SNOW = "snow"
    }
}
