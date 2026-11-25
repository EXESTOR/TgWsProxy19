package com.tgwsproxy19

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            PyBridge.ensureStarted(this)
        } catch (t: Throwable) {
            ProxyRuntime.error = "Не удалось запустить Python: ${t.message}"
        }
    }
}
