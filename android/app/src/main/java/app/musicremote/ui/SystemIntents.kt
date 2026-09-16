package app.musicremote.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.musicremote.MediaListenerService

/** Deep links into the system screens where each permission is granted. */
object SystemIntents {

    fun notificationAccess(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Straight to this app's switch rather than the full list.
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, MediaListenerService::class.java).flattenToString(),
                )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun overlay(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun battery(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun web(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    fun share(text: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
        null,
    )

    /**
     * Opens [intent]; if this device has no such screen (some OEMs strip them),
     * falls back to [fallback], usually App info. Returns false if neither exists.
     */
    fun open(context: Context, intent: Intent, fallback: Intent? = null): Boolean {
        for (candidate in listOfNotNull(intent, fallback)) {
            try {
                context.startActivity(candidate)
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // try the next one
            }
        }
        return false
    }
}
