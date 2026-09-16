package app.musicremote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Phase 1 diagnostics screen. Proves, on a real phone, the things the remote
 * depends on: session access, control, on-phone search, and starting a track —
 * including from the background, which is how the remote will actually use it.
 */
class MainActivity : Activity() {

    private val bridge by lazy { MediaBridge.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var checks: LinearLayout
    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: TextView
    private lateinit var searchBox: EditText
    private lateinit var results: LinearLayout
    private lateinit var logView: TextView

    private var latest: NowPlaying? = null
    private var latestAt = 0L

    private val onState: (NowPlaying) -> Unit = { state ->
        latest = state
        latestAt = SystemClock.elapsedRealtime()
        render()
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickProgress()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(48))
        }

        root.addView(caption("Checks that this phone can be controlled remotely. Long-press a search result to test starting playback from the background."))

        root.addView(section("Setup"))
        checks = vertical()
        root.addView(checks)

        root.addView(section("Now playing"))
        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
        }
        title = text(18f, bold = true)
        subtitle = text(14f)
        progress = text(14f)
        root.addView(art)
        root.addView(title)
        root.addView(subtitle)
        root.addView(progress)

        root.addView(row(
            button("⏮") { bridge.previous() },
            button("⏯") { bridge.playPause() },
            button("⏭") { bridge.next() },
        ))
        root.addView(row(
            button("−30s") { seekBy(-30_000) },
            button("+30s") { seekBy(30_000) },
            button("Vol −") { latest?.let { bridge.setVolume(it.volume - 1) } },
            button("Vol +") { latest?.let { bridge.setVolume(it.volume + 1) } },
        ))

        root.addView(section("Search YouTube Music"))
        searchBox = EditText(this).apply {
            hint = "Song or artist"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
        }
        root.addView(searchBox)
        root.addView(row(
            button("Search") { runSearch() },
            button("Ask app to search") { askAppToSearch() },
        ))
        results = vertical()
        root.addView(results)

        root.addView(section("Log"))
        logView = text(12f).apply { typeface = Typeface.MONOSPACE }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        renderChecks()
        bridge.start()
        bridge.addListener(onState)
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        bridge.removeListener(onState)
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ----------------------------------------------------------------- setup

    private fun renderChecks() {
        checks.removeAllViews()
        val power = getSystemService(PowerManager::class.java)
        val ytm = YtMusicLauncher.findPackage(this)

        addCheck(
            "Notification access",
            bridge.hasNotificationAccess(),
            "Needed to see and control playback. If the switch is greyed out, tap \"App info\", " +
                "open the ⋮ menu, choose \"Allow restricted settings\", then try again.",
            "Grant" to { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            "App info" to { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())) },
        )
        addCheck(
            "Display over other apps",
            Settings.canDrawOverlays(this),
            "Lets the remote start a song while this app is in the background.",
            "Grant" to { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())) },
        )
        addCheck(
            "Unrestricted battery",
            power.isIgnoringBatteryOptimizations(packageName),
            "Stops Android — Samsung especially — from putting the app to sleep.",
            "Allow" to { open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())) },
        )
        addCheck(
            "YouTube Music installed",
            ytm != null,
            ytm ?: "No app on this phone opens music.youtube.com links.",
        )
    }

    private fun addCheck(
        name: String,
        ok: Boolean,
        detail: String,
        vararg actions: Pair<String, () -> Unit>,
    ) {
        checks.addView(text(15f, bold = true).apply { text = "${if (ok) "✅" else "❌"}  $name" })
        checks.addView(caption(detail))
        if (!ok && actions.isNotEmpty()) {
            checks.addView(row(*actions.map { (label, action) -> button(label, action) }.toTypedArray()))
        }
    }

    // ----------------------------------------------------------- now playing

    private fun render() {
        val s = latest
        if (s == null || s.packageName == null) {
            title.text = "Nothing playing"
            subtitle.text = if (bridge.hasNotificationAccess()) "Start music in any app." else "Grant notification access first."
            progress.text = ""
            art.setImageDrawable(null)
            return
        }
        title.text = s.title ?: "(untitled)"
        subtitle.text = listOfNotNull(s.artist, s.album).joinToString(" — ") +
            "\n${s.source} · ${s.state} · volume ${s.volume}/${s.maxVolume}"
        art.setImageBitmap(bridge.artwork())
        tickProgress()
    }

    private fun tickProgress() {
        val s = latest ?: return
        var position = s.positionMs ?: run {
            progress.text = ""
            return
        }
        if (s.state == "playing") position += SystemClock.elapsedRealtime() - latestAt
        progress.text = "${formatTime(position)} / ${s.durationMs?.let { formatTime(it) } ?: "--:--"}"
    }

    private fun seekBy(deltaMs: Long) {
        val s = latest ?: return
        val base = (s.positionMs ?: 0L) +
            if (s.state == "playing") SystemClock.elapsedRealtime() - latestAt else 0L
        bridge.seekTo(base + deltaMs)
        log("Seek to ${formatTime(base + deltaMs)}")
    }

    // ---------------------------------------------------------------- search

    private fun runSearch() {
        val query = searchBox.text.toString().trim()
        if (query.isEmpty()) return
        results.removeAllViews()
        results.addView(caption("Searching…"))
        io.execute {
            val outcome = runCatching { YtMusicSearch.search(query) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                results.removeAllViews()
                outcome
                    .onSuccess { list ->
                        if (list.isEmpty()) results.addView(caption("No results."))
                        list.take(15).forEach { results.addView(resultRow(it)) }
                        log("Search \"$query\": ${list.size} results")
                    }
                    .onFailure {
                        results.addView(caption("Search failed: ${it.message}"))
                        log("Search failed: $it")
                    }
            }
        }
    }

    private fun resultRow(r: SearchResult): View = TextView(this).apply {
        text = "${r.title}\n${listOfNotNull(r.artist, r.duration).joinToString(" · ")}"
        textSize = 15f
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { play(r, fromBackground = false) }
        setOnLongClickListener {
            play(r, fromBackground = true)
            true
        }
    }

    private fun play(r: SearchResult, fromBackground: Boolean) {
        val app = applicationContext
        if (!fromBackground) {
            report(YtMusicLauncher.playVideo(app, r.videoId))
            return
        }
        log("Playing \"${r.title}\" in 8s. Press Home now.")
        main.postDelayed({ report(YtMusicLauncher.playVideo(app, r.videoId)) }, 8_000)
    }

    /** Experiment: can the followed app search-and-play without opening a screen? */
    private fun askAppToSearch() {
        val query = searchBox.text.toString().trim()
        if (query.isEmpty()) return
        bridge.playFromSearch(query)
        log("Asked ${latest?.source ?: "current app"} to play \"$query\"")
    }

    private fun report(result: YtMusicLauncher.Result) = when (result) {
        is YtMusicLauncher.Result.Started -> log("Started in ${result.packageName}")
        is YtMusicLauncher.Result.Failed -> log("Failed: ${result.reason}")
    }

    // --------------------------------------------------------------- helpers

    private fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logView.text = "$stamp  $line\n${logView.text}".take(4000)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            log("This phone has no screen for ${intent.action}")
        }
    }

    private fun packageUri(): Uri = Uri.parse("package:$packageName")

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun caption(value: String) = text(13f).apply {
        text = value
        alpha = 0.7f
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(value: String) = text(13f, bold = true).apply {
        text = value.uppercase()
        alpha = 0.6f
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }
}
