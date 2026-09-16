package app.auxparty

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.media.MediaBrowserService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Starts a specific track in YouTube Music without bringing its screen up.
 *
 * YouTube Music's media session advertises PLAY_FROM_URI, PLAY_FROM_MEDIA_ID
 * and PLAY_FROM_SEARCH, the same entry points Android Auto and assistants use,
 * so a track can be requested through the session in the background. Each
 * attempt is verified by watching the session's metadata change to the
 * requested track. The method that works is remembered per YouTube Music
 * version. Opening the app with a deep link remains the last resort.
 *
 * Must be called on the main thread.
 */
class BackgroundPlayer private constructor(private val context: Context) {

    enum class Method { Uri, MediaId, Search, Launch }

    sealed class Outcome {
        data class Played(val method: Method) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    companion object {
        private val VIDEO_ID = Regex("^[\\w-]{6,20}$")
        private val BACKGROUND_METHODS = listOf(Method.Uri, Method.MediaId, Method.Search)

        @Volatile private var instance: BackgroundPlayer? = null

        fun get(context: Context): BackgroundPlayer =
            instance ?: synchronized(this) {
                instance ?: BackgroundPlayer(context.applicationContext).also { instance = it }
            }

        /**
         * Whether the session now plays the requested track. YouTube Music uses
         * the video id as the media id, which is exact. Titles are only trusted when
         * no id can be compared ([allowTitle]: after a search, or a session without
         * ids), and must match exactly: "Numb" must not match "Numb (Chill Mix)".
         */
        fun trackMatches(
            mediaId: String?,
            title: String?,
            videoId: String,
            expectedTitle: String?,
            allowTitle: Boolean = true,
        ): Boolean {
            if (mediaId != null && mediaId.contains(videoId)) return true
            if (!allowTitle || title == null || expectedTitle == null) return false
            val a = normalize(title)
            return a.length >= 2 && a == normalize(expectedTitle)
        }

        private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }

    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private var browser: MediaBrowser? = null

    suspend fun play(
        videoId: String,
        title: String?,
        artist: String?,
        log: (String) -> Unit = {},
    ): Outcome {
        if (!VIDEO_ID.matches(videoId)) return Outcome.Failed("Invalid video id")
        val pkg = YtMusicLauncher.findPackage(context) ?: return Outcome.Failed("YouTube Music is not installed")

        // Keyed by app version: an update may change what YouTube Music supports.
        val key = "$pkg@${versionOf(pkg)}"
        val known = prefs.getString("method:$key", null)?.let { runCatching { Method.valueOf(it) }.getOrNull() }
        val backgroundUnsupported = prefs.getBoolean("noBackground:$key", false)

        if (!backgroundUnsupported) {
            val controller = MediaBridge.get(context).controllerFor(pkg) ?: connect(pkg, log)
            if (controller == null) {
                log("YouTube Music has no media session to talk to yet")
            } else {
                val order = (listOfNotNull(known) + BACKGROUND_METHODS).distinct()
                    .filter { it != Method.Launch && (it != Method.Search || title != null) }
                var anythingHappened = false
                for (method in order) {
                    if (!supports(controller, method)) {
                        log("$method: not supported by this YouTube Music")
                        continue
                    }
                    val before = signature(controller)
                    request(controller, method, videoId, title, artist)
                    // The first attempt gets longer: a cold track can take a while to load.
                    val timeout = if (method == order.first()) 8_000L else 5_000L
                    if (waitFor(controller, method, before, videoId, title, timeout)) {
                        prefs.edit().putString("method:$key", method.name).apply()
                        log("$method: playing in the background")
                        return Outcome.Played(method)
                    }
                    if (signature(controller) != before) anythingHappened = true
                    log("$method: didn't start the requested track")
                }
                // Only give up on background playback when the session ignored every
                // request outright, so a slow network can't disable it permanently.
                if (!anythingHappened) prefs.edit().putBoolean("noBackground:$key", true).apply()
            }
        } else {
            log("Background playback previously unsupported by this YouTube Music version")
        }

        // Last resort: open the track, which brings YouTube Music to the front. From
        // the background Android drops this silently without the overlay permission.
        if (!Settings.canDrawOverlays(context)) return Outcome.Failed("overlay_permission_missing")
        return when (val launched = YtMusicLauncher.playVideo(context, videoId)) {
            is YtMusicLauncher.Result.Started -> {
                log("Launch: opened YouTube Music")
                Outcome.Played(Method.Launch)
            }
            is YtMusicLauncher.Result.Failed -> Outcome.Failed(launched.reason)
        }
    }

    /** Clears remembered results, e.g. from Diagnostics. */
    fun forget() {
        prefs.edit().clear().apply()
    }

    private fun supports(controller: MediaController, method: Method): Boolean {
        // No playback state yet (e.g. a freshly connected service): just try.
        val actions = controller.playbackState?.actions ?: return true
        val flag = when (method) {
            Method.Uri -> PlaybackState.ACTION_PLAY_FROM_URI
            Method.MediaId -> PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
            Method.Search -> PlaybackState.ACTION_PLAY_FROM_SEARCH
            Method.Launch -> return false
        }
        return (actions and flag) != 0L
    }

    private fun request(controller: MediaController, method: Method, videoId: String, title: String?, artist: String?) {
        val controls = controller.transportControls
        when (method) {
            Method.Uri -> controls.playFromUri(Uri.parse("https://music.youtube.com/watch?v=$videoId"), Bundle())
            Method.MediaId -> controls.playFromMediaId(videoId, Bundle())
            Method.Search -> controls.playFromSearch(
                listOfNotNull(title, artist).joinToString(" "),
                Bundle().apply {
                    putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    title?.let { putString(MediaStore.EXTRA_MEDIA_TITLE, it) }
                    artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                },
            )
            Method.Launch -> Unit
        }
    }

    private suspend fun waitFor(
        controller: MediaController,
        method: Method,
        before: String,
        videoId: String,
        title: String?,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val md = controller.metadata
            val mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            // Titles are trusted after a search, when there's no id to compare, or when
            // the track demonstrably changed (in case this build's media ids aren't
            // video ids): then an exact title match means our request took effect.
            val allowTitle = method == Method.Search || mediaId == null || signature(controller) != before
            if (trackMatches(mediaId, md?.getString(MediaMetadata.METADATA_KEY_TITLE), videoId, title, allowTitle)) {
                return true
            }
            delay(200)
        }
        return false
    }

    private fun signature(controller: MediaController): String {
        val md = controller.metadata
        return "${md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)}|${md?.getString(MediaMetadata.METADATA_KEY_TITLE)}"
    }

    /**
     * When YouTube Music isn't running there's no active session, so connect to its
     * media browser service (as Android Auto does), which starts it headless. It may
     * refuse unknown callers; then we fall back to opening the app.
     */
    private suspend fun connect(pkg: String, log: (String) -> Unit): MediaController? {
        browser?.takeIf { it.isConnected }?.let { return MediaController(context, it.sessionToken) }

        @Suppress("DEPRECATION")
        val service = context.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo
        if (service == null) {
            log("YouTube Music exposes no media browser service")
            return null
        }

        val controller = withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine<MediaController?> { cont ->
                var created: MediaBrowser? = null
                val callback = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        val b = created ?: return
                        browser = b
                        if (cont.isActive) cont.resume(MediaController(context, b.sessionToken))
                    }

                    override fun onConnectionFailed() {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onConnectionSuspended() {
                        browser = null
                    }
                }
                val b = MediaBrowser(context, ComponentName(pkg, service.name), callback, null)
                created = b
                b.connect()
                cont.invokeOnCancellation { if (!b.isConnected) b.disconnect() }
            }
        }
        log(if (controller != null) "Connected to YouTube Music's media service" else "YouTube Music refused a media service connection")
        return controller
    }

    private fun versionOf(pkg: String): Long = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }
}
