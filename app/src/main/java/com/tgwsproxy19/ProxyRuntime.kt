package com.tgwsproxy19

/** Общее состояние прокси между Activity и Service (один процесс). */
object ProxyRuntime {
    @Volatile var running: Boolean = false
    @Volatile var status: String = "Остановлен"
    @Volatile var error: String? = null
    @Volatile var port: Int = 1443
    @Volatile var secret: String = ""
}
