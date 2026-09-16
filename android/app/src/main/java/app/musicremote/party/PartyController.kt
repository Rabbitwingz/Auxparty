package app.musicremote.party

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.musicremote.BackgroundPlayer
import app.musicremote.BuildConfig
import app.musicremote.Identity
import app.musicremote.MediaBridge
import app.musicremote.NowPlaying
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs party mode on the phone: keeps the queue (saved across restarts), answers
 * queue commands from browsers, watches playback and starts the next request in
 * the background when a song ends. The relay only relays; this is the source of
 * truth. Main thread only.
 */
class PartyController private constructor(private val context: Context) {

    data class Guest(val guestId: String, val name: String, val joinedAt: Long, val lastSeenAt: Long?)

    data class State(
        val active: Boolean,
        /** The shareable link, while a party is running and the relay has told us its secret. */
        val link: String?,
        val guestCount: Int,
        val guests: List<Guest>,
        val current: QueueItem?,
        val upcoming: List<QueueItem>,
        val limitPerGuest: Int,
        /** Why the queue did what it did, newest first. Shown in Diagnostics. */
        val log: List<String>,
    )

    sealed class CommandResult {
        data class Ok(val data: Any?) : CommandResult()
        data class Error(val code: String) : CommandResult()
    }

    fun interface Listener {
        fun onParty(state: State)
    }

    companion object {
        private const val TAG = "Party"
        private const val FILE = "party-queue.json"
        private const val LOG_LINES = 100
        const val ERROR_PARTY_OFF = "party_off"

        @Volatile private var instance: PartyController? = null

        fun get(context: Context): PartyController =
            instance ?: synchronized(this) {
                instance ?: PartyController(context.applicationContext).also { instance = it }
            }

        fun observation(s: NowPlaying, at: Long = SystemClock.elapsedRealtime()) = QueueEngine.Observation(
            mediaId = s.mediaId,
            title = s.title,
            playback = s.state,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            at = at,
        )

        /** Whether the phone is playing [item] (by video id, else by exact title). */
        fun isPlaying(item: QueueItem, s: NowPlaying): Boolean =
            BackgroundPlayer.trackMatches(s.mediaId, s.title, item.videoId, item.title, allowTitle = s.mediaId == null)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val bridge = MediaBridge.get(context)
    private val prefs = context.getSharedPreferences("party", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, FILE)
    private val random = SecureRandom()
    private val queue = PartyQueue()
    private val engine = QueueEngine()
    private val listeners = mutableSetOf<Listener>()
    private val log = ArrayDeque<String>()

    private var active = prefs.getBoolean("active", false)
    private var secret: String? = null
    private var guestCount = 0
    private var guests: List<Guest> = emptyList()
    /** A request is being started; observations are ignored until it settles. */
    private var starting = false
    private var observing = false
    /** The first observation after (re)starting may find the saved current song still playing. */
    private var reclaimCurrent = true

    /** Sends the queue to the relay (null: no party). Set by RelayClient. */
    var sendQueue: (JSONObject?) -> Unit = {}

    private val onMedia: (NowPlaying) -> Unit = { evaluate(it) }
    private val tick = Runnable { evaluate(bridge.snapshot()) }

    init {
        restore()
        if (active) startObserving()
    }

    val isActive: Boolean get() = active

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onParty(state())
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun state() = State(
        active = active,
        link = secret?.takeIf { active }?.let { "${BuildConfig.WEB_URL.trimEnd('/')}/#party=${Identity.get(context).id}.$it" },
        guestCount = guestCount,
        guests = guests,
        current = queue.current,
        upcoming = queue.items,
        limitPerGuest = queue.limitPerGuest,
        log = log.toList(),
    )

    // ----------------------------------------------------------- from relay

    /** The relay's view of the party: on connect and whenever it changes. */
    fun onRelayParty(json: JSONObject?) {
        val nowActive = json?.optBoolean("active") == true
        guestCount = if (nowActive) json?.optInt("guestCount") ?: 0 else 0
        json?.string("secret")?.let { secret = it }

        if (nowActive && !active) {
            active = true
            note("Party started")
            engine.reset()
            reclaimCurrent = false // a new party: whatever plays now is filler
            startObserving()
        } else if (!nowActive && active) {
            active = false
            secret = null
            guests = emptyList()
            queue.reset()
            stopObserving()
            note("Party ended")
        }
        prefs.edit().putBoolean("active", active).apply()
        changed()
    }

    fun onRelayGuests(array: JSONArray?) {
        array ?: return
        guests = (0 until array.length()).mapNotNull { i ->
            val g = array.optJSONObject(i) ?: return@mapNotNull null
            Guest(
                guestId = g.string("guestId") ?: return@mapNotNull null,
                name = g.string("name") ?: "Guest",
                joinedAt = g.optLong("joinedAt"),
                lastSeenAt = if (g.isNull("lastSeenAt")) null else g.optLong("lastSeenAt"),
            )
        }
        guestCount = guests.size
        notifyListeners()
    }

    /** Re-sends the queue, e.g. after reconnecting, so the relay's copy is current. */
    fun republish() {
        sendQueue(if (active) queue.toJson() else null)
    }

    /** A `queue.*` command from a browser. [from] was set by the relay. */
    fun command(action: String, args: JSONObject, from: Requester): CommandResult {
        if (!active) return CommandResult.Error(ERROR_PARTY_OFF)
        return when (action) {
            "queue.add" -> add(SongRequest.fromArgs(args), from)
            "queue.remove" -> {
                val itemId = args.string("itemId").orEmpty()
                val title = queue.items.firstOrNull { it.itemId == itemId }?.title
                queue.remove(itemId, from)?.let { CommandResult.Error(it) } ?: run {
                    note("${from.name} removed \"$title\"")
                    changed()
                    CommandResult.Ok(null)
                }
            }
            "queue.move" -> queue.move(args.string("itemId").orEmpty(), args.optInt("toIndex"))
                ?.let { CommandResult.Error(it) }
                ?: run { changed(); CommandResult.Ok(null) }
            "queue.clear" -> {
                queue.clear()
                note("${from.name} cleared the queue")
                changed()
                CommandResult.Ok(null)
            }
            else -> CommandResult.Error("unknown_action")
        }
    }

    // ------------------------------------------------------------ host (app)

    fun remove(itemId: String) = command("queue.remove", JSONObject().put("itemId", itemId), Requester.HOST)

    fun playNext(itemId: String) = command("queue.move", JSONObject().put("itemId", itemId).put("toIndex", 0), Requester.HOST)

    fun clear() = command("queue.clear", JSONObject(), Requester.HOST)

    fun setLimitPerGuest(limit: Int) {
        queue.limitPerGuest = limit.coerceIn(1, 20)
        changed()
    }

    /**
     * Skip, from the app or a remote's "next". Returns false when there's no request
     * waiting, so the caller skips in YouTube Music as usual.
     */
    fun skip(): Boolean {
        if (!active || !queue.hasNext() || starting) return false
        startNext("skipped")
        return true
    }

    /** The host started a song themselves ("Play now"); let it finish before the queue continues. */
    fun onHostPlayed() {
        if (!active) return
        engine.startedHostPick(observation(bridge.snapshot()))
        if (queue.current != null) {
            queue.current = null
            changed()
        }
    }

    // ---------------------------------------------------------------- engine

    private fun add(request: SongRequest, from: Requester): CommandResult =
        when (val result = queue.add(request, from, System.currentTimeMillis(), ::newId)) {
            is PartyQueue.AddResult.Refused -> CommandResult.Error(result.error)
            is PartyQueue.AddResult.Added -> {
                if (!result.duplicate) note("${from.name} requested \"${result.item.title}\"")
                changed()
                // Nothing requested playing? This may start it right away.
                evaluate(bridge.snapshot())
                // 0 when it's playing now; otherwise its place in line after that.
                val position = if (queue.current?.itemId == result.item.itemId) 0
                else queue.items.indexOfFirst { it.itemId == result.item.itemId } + 1
                CommandResult.Ok(
                    JSONObject()
                        .put("itemId", result.item.itemId)
                        .put("position", position)
                        .put("duplicate", result.duplicate),
                )
            }
        }

    private fun startObserving() {
        if (observing) return
        observing = true
        bridge.addListener(onMedia)
    }

    private fun stopObserving() {
        if (!observing) return
        observing = false
        bridge.removeListener(onMedia)
        main.removeCallbacks(tick)
    }

    private fun evaluate(s: NowPlaying) {
        if (!active || starting) return
        main.removeCallbacks(tick)
        val o = observation(s)

        if (reclaimCurrent) {
            reclaimCurrent = false
            // After the app restarts mid-song, don't treat our own request as filler.
            queue.current?.let { if (isPlaying(it, s)) engine.startedRequested(o) }
        }

        val decision = engine.observe(o, queue.hasNext())
        if (engine.kind != QueueEngine.Kind.Requested && queue.current != null) {
            queue.current = null
            changed()
        }
        when (decision) {
            is QueueEngine.Decision.Wait -> decision.checkInMs?.let { main.postDelayed(tick, it.coerceAtLeast(100)) }
            is QueueEngine.Decision.PlayNext -> startNext(decision.reason)
        }
    }

    private fun startNext(reason: String) {
        val item = queue.popNext() ?: return
        starting = true
        main.removeCallbacks(tick)
        note("Playing \"${item.title}\" for ${item.requester.name}: $reason")
        changed()

        scope.launch {
            val outcome = BackgroundPlayer.get(context).play(item.videoId, item.title, item.artist)
            starting = false
            when (outcome) {
                is BackgroundPlayer.Outcome.Played ->
                    if (active && queue.current?.itemId == item.itemId) engine.startedRequested(observation(bridge.snapshot()))
                is BackgroundPlayer.Outcome.Failed -> {
                    note("Couldn't play \"${item.title}\": ${outcome.reason}")
                    queue.drop(item.itemId)
                    changed()
                    delay(1_500) // don't race through the whole queue if playback is broken
                }
            }
            if (active) evaluate(bridge.snapshot())
        }
    }

    // --------------------------------------------------------------- helpers

    private fun changed() {
        persist()
        republish()
        notifyListeners()
    }

    private fun notifyListeners() {
        val s = state()
        listeners.toList().forEach { it.onParty(s) }
    }

    private fun note(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.addFirst("$stamp  $line")
        while (log.size > LOG_LINES) log.removeLast()
        Log.i(TAG, line)
    }

    private fun persist() {
        runCatching { file.writeText(queue.toJson().toString()) }
            .onFailure { Log.w(TAG, "Could not save the queue: $it") }
    }

    private fun restore() {
        if (!active || !file.exists()) return
        runCatching { queue.restore(JSONObject(file.readText())) }
            .onFailure { Log.w(TAG, "Could not restore the queue: $it") }
    }

    private fun newId(): String {
        val bytes = ByteArray(6).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
