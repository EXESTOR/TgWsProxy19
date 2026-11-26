package com.tgwsproxy19

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/** Виджет 1×1: включает/выключает прокси одним тапом. */
class ToggleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val serviceIntent = Intent(context, ProxyForegroundService::class.java)
            if (ProxyRuntime.running) {
                serviceIntent.action = ProxyForegroundService.ACTION_STOP
                context.startService(serviceIntent)
            } else {
                serviceIntent.action = ProxyForegroundService.ACTION_START
                serviceIntent.putExtra(ProxyForegroundService.EXTRA_PORT, prefs.getInt("port", 1445))
                serviceIntent.putExtra(ProxyForegroundService.EXTRA_SECRET, prefs.getString("secret", "").orEmpty())
                serviceIntent.putExtra(ProxyForegroundService.EXTRA_NO_H2, prefs.getBoolean("no_h2", true))
                serviceIntent.putExtra(ProxyForegroundService.EXTRA_DC_IP, prefs.getString("dc_ip", "").orEmpty())
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.tgwsproxy19.WIDGET_TOGGLE"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ToggleWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_toggle)
            val running = ProxyRuntime.running
            views.setTextViewText(R.id.widgetText, if (running) "Вкл" else "Выкл")
            views.setInt(
                R.id.widgetIcon, "setColorFilter",
                if (running) 0xFF7DE8C0.toInt() else 0xFF8B96AB.toInt()
            )
            val intent = Intent(context, ToggleWidgetProvider::class.java).setAction(ACTION_TOGGLE)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
