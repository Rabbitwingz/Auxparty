package app.auxparty

import android.service.notification.NotificationListenerService

/**
 * Exists so the system grants MediaSessionManager.getActiveSessions() for other
 * apps' players. We never read notifications themselves.
 *
 * As a side benefit the system binds and rebinds notification listeners itself,
 * including after a reboot, which keeps the process alive far more reliably than
 * a plain background service.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaBridge.get(applicationContext).start()
        // The system rebinds listeners on its own (after boot, after the process
        // is killed), which makes this a dependable moment to reconnect.
        RelayService.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaBridge.get(applicationContext).stop()
    }
}
