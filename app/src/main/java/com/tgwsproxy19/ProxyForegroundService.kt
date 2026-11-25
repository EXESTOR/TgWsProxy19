package com.tgwsproxy19

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class ProxyForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val stopping = AtomicBoolean(false)
    private lateinit var prefs: android.content.SharedPreferences
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        createChannel()
        try {
            PyBridge.ensureStarted(this)
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownAsync()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 1443) ?: 1443
        val secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty()
        val noH2 = intent?.getBooleanExtra(EXTRA_NO_H2, true) ?: true
        val dcIp = intent?.getStringExtra(EXTRA_DC_IP).orEmpty()

        ProxyRuntime.port = port
        ProxyRuntime.secret = secret
        ProxyRuntime.running = false
        ProxyRuntime.error = null
        ProxyRuntime.status = "Запуск…"

        startInForeground("Запуск прокси на 127.0.0.1:$port…")
        acquireWakeLock()
        registerPowerWatch()

        Thread {
            val error = PyBridge.start(port, secret, noH2, dcIp)
            if (error.isBlank()) {
                ProxyRuntime.running = true
                ProxyRuntime.status = "Работает на 127.0.0.1:$port"
                notifyStatus("Прокси работает • 127.0.0.1:$port")
                evaluatePowerPolicy()
            } else {
                ProxyRuntime.running = false
                ProxyRuntime.error = error
                ProxyRuntime.status = "Ошибка запуска"
                notifyStatus("Ошибка: $error")
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun shutdownAsync() {
        if (!stopping.compareAndSet(false, true)) return
        notifyStatus("Остановка…")
        Thread {
            try {
                PyBridge.stop()
            } catch (_: Throwable) {
            }
            ProxyRuntime.running = false
            ProxyRuntime.status = "Остановлен"
            ProxyRuntime.error = null
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
        }.start()
    }

    private fun registerPowerWatch() {
        if (batteryReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                evaluatePowerPolicy()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            batteryReceiver = receiver
        } catch (_: Throwable) {
        }
    }

    private fun unregisterPowerWatch() {
        val receiver = batteryReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        batteryReceiver = null
    }

    /** Останавливает прокси по выбранной политике: энергосбережение или порог заряда. */
    private fun evaluatePowerPolicy() {
        if (!ProxyRuntime.running) return
        val mode = prefs.getString("bg_mode", "saver") ?: "saver"
        when (mode) {
            "saver" -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isPowerSaveMode) {
                    stopByPolicy("включено энергосбережение")
                } else if (!pm.isInteractive) {
                    stopByPolicy("экран выключен")
                }
            }
            "percent" -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val threshold = prefs.getInt("bg_percent", 15)
                if (level in 1..threshold) stopByPolicy("заряд $level% ≤ $threshold%")
            }
        }
    }

    private fun stopByPolicy(reason: String) {
        if (!ProxyRuntime.running) return
        ProxyRuntime.error = "Остановлено: $reason"
        ProxyRuntime.status = "Остановлено"
        notifyStatus("Остановлено: $reason")
        Thread {
            try {
                PyBridge.stop()
            } catch (_: Throwable) {
            }
            ProxyRuntime.running = false
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        unregisterPowerWatch()
        releaseWakeLock()
        if (PyBridge.isRunning()) {
            Thread {
                try {
                    PyBridge.stop()
                } catch (_: Throwable) {
                }
                ProxyRuntime.running = false
            }.start()
        }
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun notifyStatus(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("TgWsProxy19")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_proxy, "Остановить", stopIntent)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Прокси",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Статус локального MTProto-прокси"
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy19::proxy").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.tgwsproxy19.action.START"
        const val ACTION_STOP = "com.tgwsproxy19.action.STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_NO_H2 = "no_h2"
        const val EXTRA_DC_IP = "dc_ip"
        private const val CHANNEL_ID = "proxy_status"
        private const val NOTIFICATION_ID = 1001
    }
}
