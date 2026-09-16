package app.musicremote

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

data class NowPlaying(
    val packageName: String?,
    val source: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    /** Live position as of the moment this snapshot was taken. */
    val positionMs: Long?,
    val state: String,
    val volume: Int,
    val maxVolume: Int,
)

/**
 * Watches every media session on the phone, follows the one worth showing, and
 * controls it. All state is touched on the main thread only.
 */
class MediaBridge private constructor(private val context: Context) {

    companion object {
        private const val POLL_MS = 2000L

        @Volatile private var instance: MediaBridge? = null

        fun get(context: Context): MediaBridge =
            instance ?: synchronized(this) {
                instance ?: MediaBridge(context.applicationContext).also { instance = it }
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)

    private var controller: MediaController? = null
    private var started = false
    private var polling = false
    private val listeners = mutableSetOf<(NowPlaying) -> Unit>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        rebind(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            refresh()
        }
    }

    // Callbacks only cover the session we follow. If a different app starts
    // playing, the session list doesn't necessarily change, so re-pick on a timer
    // while anyone is listening.
    private val poll = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) {
                polling = false
                return
            }
            refresh()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == listenerComponent }
    }

    fun start() = main.post {
        if (started || !hasNotificationAccess()) return@post
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, main)
            started = true
        } catch (e: SecurityException) {
            // Access was revoked between the check and the call.
        }
        refresh()
    }

    fun stop() = main.post {
        if (!started) return@post
        sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller?.unregisterCallback(callback)
        controller = null
        started = false
        publish()
    }

    fun refresh() = main.post {
        val list = try {
            if (hasNotificationAccess()) sessionManager.getActiveSessions(listenerComponent) else emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        rebind(list)
    }

    fun addListener(listener: (NowPlaying) -> Unit) = main.post {
        listeners += listener
        listener(snapshot())
        if (!polling) {
            polling = true
            main.postDelayed(poll, POLL_MS)
        }
    }

    fun removeListener(listener: (NowPlaying) -> Unit) = main.post {
        listeners -= listener
    }

    // ---------------------------------------------------------------- control

    fun hasSession(): Boolean = controller != null

    /**
     * The active session of a specific app, which isn't necessarily the one being
     * followed (e.g. starting YouTube Music while Plex is playing).
     */
    fun controllerFor(pkg: String): MediaController? = try {
        if (hasNotificationAccess()) sessionManager.getActiveSessions(listenerComponent).firstOrNull { it.packageName == pkg } else null
    } catch (e: SecurityException) {
        null
    }

    fun play() {
        controller?.transportControls?.play()
    }

    fun pause() {
        controller?.transportControls?.pause()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Asks the followed app to search and play; only works if it implements it. */
    fun playFromSearch(query: String) {
        controller?.transportControls?.playFromSearch(query, Bundle())
    }

    fun setVolume(index: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max), 0)
        publish()
    }

    // ------------------------------------------------------------------ state

    fun snapshot(): NowPlaying {
        val c = controller
        val md = c?.metadata
        val ps = c?.playbackState
        return NowPlaying(
            packageName = c?.packageName,
            source = Packages.label(c?.packageName),
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            // getLong returns 0 when the key is absent.
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = ps?.let { livePosition(it) },
            state = stateName(ps?.state),
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }

    fun artwork(): Bitmap? {
        val md = controller?.metadata ?: return null
        return md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun rebind(list: List<MediaController>) {
        val best = list.filter { score(it) > 0 }.maxByOrNull { score(it) }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        if (listeners.isEmpty()) return
        val s = snapshot()
        listeners.toList().forEach { it(s) }
    }

    private fun score(c: MediaController): Int {
        var n = 0
        when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> n += 100
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING -> n += 50
        }
        n += maxOf(0, 20 - Packages.rank(c.packageName) * 4)
        if (c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null) n += 3
        return n
    }

    /**
     * PlaybackState.position is only valid as of lastPositionUpdateTime and
     * advances at playbackSpeed from there. YouTube Music publishes it once per
     * track, so reading position alone reports 0 for a song minutes in.
     */
    private fun livePosition(ps: PlaybackState): Long? {
        if (ps.position < 0) return null
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        val elapsed = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
        return (ps.position + elapsed * ps.playbackSpeed).toLong()
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "buffering"
        PlaybackState.STATE_ERROR -> "error"
        null, PlaybackState.STATE_NONE -> "none"
        else -> "other"
    }
}
