package app.musicremote

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
import android.util.Log

/**
 * Keeps the relay connection alive while the app isn't on screen. A foreground
 * service is what Android — and Samsung's aggressive background limits — will
 * reliably leave running, and its notification tells the user why.
 */
class RelayService : Service() {

    companion object {
        private const val TAG = "RelayService"
        private const val CHANNEL_ID = "relay"
        private const val NOTIFICATION_ID = 1

        /** Safe to call from anywhere; starting twice is a no-op. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, RelayService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses background starts unless an exemption applies
                // (boot, battery-optimisation exemption, visible activity). The next
                // qualifying moment — opening the app, a reboot — starts it instead.
                Log.w(TAG, "Could not start relay service: $e")
            }
        }
    }

    private val client by lazy { RelayClient.get(this) }

    private val listener = object : RelayClient.Listener {
        override fun onStatus(status: RelayClient.Status, error: String?) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(status))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        client.addListener(listener)
        client.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        client.removeListener(listener)
        client.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val notification = notification(client.status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Remote connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while this phone can be controlled from your other devices."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notification(status: RelayClient.Status): Notification {
        val text = when (status) {
            RelayClient.Status.CONNECTED -> "Ready for remote control"
            RelayClient.Status.CONNECTING -> "Connecting…"
            RelayClient.Status.OFFLINE -> "Offline, retrying"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Auxparty")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
