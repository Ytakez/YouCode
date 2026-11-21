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
import android.os.PowerManager
import com.opencode.ai.MainActivity

/**
 * Фоновый сервис с уведомлением: пока модель отвечает, система не замораживает приложение
 * вместе со встроенным OpenCode, и длинный ответ не обрывается при выходе из приложения.
 */
class EngineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var wake: PowerManager.WakeLock? = null

    /** Пока агент ждёт (sleep) с погашенным экраном, процессор не должен засыпать. */
    private fun holdCpu() {
        if (wake?.isHeld == true) return
        wake = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "youcode:work")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        }.getOrNull()
    }

    override fun onDestroy() {
        active = false
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        super.onDestroy()
    }

    /** Android 15: у foreground-сервиса типа dataSync лимит ~6 часов, по сигналу нужно остановиться. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        holdCpu()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Работа модели", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Модель отвечает")
            .setContentText("YouCode доделает ответ в фоне")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "work"
        private const val NOTIFICATION_ID = 1
        @Volatile private var active = false

        fun start(context: Context) {
            if (active) return
            active = runCatching { context.startForegroundService(Intent(context, EngineService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            if (!active) return
            active = false
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
