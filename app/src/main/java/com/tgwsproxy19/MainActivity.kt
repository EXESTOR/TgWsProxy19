package com.tgwsproxy19

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        try {
            PyBridge.ensureStarted(this)
        } catch (t: Throwable) {
            ProxyRuntime.error = "Не удалось запустить Python: ${t.message}"
        }

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#05070E")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#05070E")

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#05070E"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(WebAppBridge(this@MainActivity, prefs), "Android")
        }

        setContentView(webView)
        webView.loadUrl("file:///android_asset/ui/index.html")

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }

    fun openTelegram(port: Int, secret: String) {
        val link = "tg://proxy?server=127.0.0.1&port=$port&secret=dd$secret"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(link)
            toast("Telegram не найден — ссылка скопирована")
        }
    }

    fun openUrl(url: String) {
        val telegramUri = telegramUriFor(url)
        if (telegramUri != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, telegramUri))
                return
            } catch (_: ActivityNotFoundException) {
                // Telegram не установлен — падаем в обычный https
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            copyToClipboard(url)
            toast("Ссылка скопирована")
        }
    }

    /** t.me/EXESTOR -> tg://resolve?domain=EXESTOR, чтобы открывалось сразу в Telegram. */
    private fun telegramUriFor(url: String): Uri? {
        val match = TELEGRAM_LINK.find(url.trim()) ?: return null
        val username = match.groupValues[1]
        return Uri.parse("tg://resolve?domain=$username")
    }

    fun copyToClipboard(text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("TgWsProxy19", text))
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        val TELEGRAM_LINK = Regex("""^(?:https?://)?(?:www\.)?t\.me/(?:s/)?([A-Za-z0-9_]{2,64})/?$""")
    }

    fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Фоновая работа уже разрешена")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Не удалось открыть настройки")
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }
}
