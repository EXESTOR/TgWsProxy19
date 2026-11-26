package com.tgwsproxy19

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Тонкая обёртка над Python-модулем runner.py. */
object PyBridge {

    private val startLock = Any()

    fun ensureStarted(context: Context) {
        synchronized(startLock) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
        }
    }

    private fun runner(): PyObject? {
        if (!Python.isStarted()) return null
        return try {
            Python.getInstance().getModule("runner")
        } catch (t: Throwable) {
            ProxyRuntime.error = "Импорт runner.py: ${t.message}"
            null
        }
    }

    fun start(port: Int, secret: String, noH2: Boolean, dcIp: String): String {
        val module = runner() ?: return "Python не запущен"
        return try {
            module.callAttr("start_proxy", port, secret, noH2, dcIp).toString()
        } catch (t: Throwable) {
            "Ошибка Python: ${t.message}"
        }
    }

    fun stop(): String {
        val module = runner() ?: return "Python не запущен"
        return try {
            module.callAttr("stop_proxy").toString()
        } catch (t: Throwable) {
            "Ошибка остановки: ${t.message}"
        }
    }

    fun logs(): String {
        val module = runner() ?: return ""
        return try {
            module.callAttr("get_logs", 300).toString()
        } catch (t: Throwable) {
            "Ошибка чтения логов: ${t.message}"
        }
    }

    fun clearLogs() {
        val module = runner() ?: return
        try {
            module.callAttr("clear_logs")
        } catch (t: Throwable) {
        }
    }

    fun stats(): String {
        val module = runner() ?: return "{}"
        return try {
            module.callAttr("get_stats").toString()
        } catch (t: Throwable) {
            "{}"
        }
    }

    fun isRunning(): Boolean {
        val module = runner() ?: return false
        return try {
            module.callAttr("is_running").toBoolean()
        } catch (t: Throwable) {
            false
        }
    }
}
