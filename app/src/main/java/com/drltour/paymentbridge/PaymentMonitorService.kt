package com.drltour.paymentbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * PaymentMonitorService — A foreground service that keeps the app alive
 * in the background.
 *
 * Displays a persistent notification with START/STOP controls.
 * The notification cannot be dismissed while the service is running.
 */
class PaymentMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "payment_bridge_monitor"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.drltour.paymentbridge.START"
        const val ACTION_STOP = "com.drltour.paymentbridge.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PaymentMonitorService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PaymentMonitorService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setMonitoringEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START or null (service restarted)
                Prefs.setMonitoringEnabled(this, true)
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the service is being destroyed by the system (not by user),
        // restart it via START_STICKY in onStartCommand. If user stopped it,
        // monitoring will be false and we won't restart.
        if (Prefs.isMonitoringEnabled(this)) {
            val restartIntent = Intent(this, PaymentMonitorService::class.java)
            restartIntent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }

    private fun buildNotification(): Notification {
        // Main activity intent
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // STOP action intent
        val stopIntent = Intent(this, PaymentMonitorService::class.java)
        stopIntent.action = ACTION_STOP
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // START action intent (used to re-enable from notification if needed)
        val startIntent = Intent(this, PaymentMonitorService::class.java)
        startIntent.action = ACTION_START
        val startPending = PendingIntent.getService(
            this,
            2,
            startIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isMonitoring = Prefs.isMonitoringEnabled(this)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Payment Bridge")
            .setContentText(
                if (isMonitoring) "Monitoring payments — tap to open"
                else "Monitoring is paused"
            )
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openPending)
            .setOngoing(true)          // cannot be dismissed
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isMonitoring) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP",
                stopPending
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "START",
                startPending
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Payment Monitor"
            val descriptionText = "Keeps Payment Bridge running in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
