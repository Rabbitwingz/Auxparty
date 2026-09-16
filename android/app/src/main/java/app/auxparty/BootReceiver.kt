package app.auxparty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects after a reboot, and after an app update (which kills the service). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> RelayService.start(context)
        }
    }
}
