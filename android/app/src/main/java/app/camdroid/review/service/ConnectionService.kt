package app.camdroid.review.service

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
import app.camdroid.review.MainActivity
import app.camdroid.review.R

/**
 * Minimal foreground service whose job is to keep the app's process alive
 * while the user is actively using CamDroid Review. Without this, the OS
 * is free to throttle/kill the process during brief backgrounds (notification
 * shade, phone call, app switcher), which would tear down the WebSocket and
 * lose any captures that arrived during the gap.
 *
 * The WS connection itself stays in MainViewModel — this service just owns
 * the "keep me alive" claim. When the user swipes the app away from recents,
 * onTaskRemoved cleans up. The notification has a Stop action for explicit
 * shutdown.
 */
class ConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away from recents — explicit "I'm done" signal.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection",
            NotificationManager.IMPORTANCE_LOW,  // no sound, no vibrate
        ).apply {
            description = "Keeps the connection to the Pi alive while CamDroid Review is open"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamDroid Review")
            .setContentText("Keeping the connection to the Pi alive")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "camdroid_connection"
        const val ACTION_STOP = "app.camdroid.review.action.STOP_CONNECTION_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
