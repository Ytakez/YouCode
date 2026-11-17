package com.opencode.ai.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.opencode.ai.MainActivity

/**
 * Не даёт системе заморозить приложение (и opencode вместе с ним),
 * пока модель отвечает, чтобы длинный ответ не обрывался при выходе из приложения.
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Работа модели", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Модель отвечает")
            .setContentText("YouCode доделает ответ в фоне")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "work"
        private var on = false

        fun start(context: Context) {
            if (on) return
            on = true
            runCatching { context.startForegroundService(Intent(context, EngineService::class.java)) }
        }

        fun stop(context: Context) {
            if (!on) return
            on = false
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
