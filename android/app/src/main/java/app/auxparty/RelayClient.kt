package app.auxparty

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import app.auxparty.party.PartyController
import app.auxparty.party.Requester
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * The phone's connection to the relay: keeps it open, carries out commands from
 * browsers, and publishes playback state. See relay/PROTOCOL.md.
 *
 * All mutable state lives on the main thread; OkHttp callbacks hop onto it.
 */
class RelayClient private constructor(private val context: Context) {

    enum class Status { OFFLINE, CONNECTING, CONNECTED }

    data class LinkedBrowser(val clientId: String, val name: String, val createdAt: Long, val lastSeenAt: Long?)
    data class PairCode(val code: String, val expiresAt: Long)

    interface Listener {
        fun onStatus(status: Status, error: String?) {}
        fun onPairCode(code: PairCode) {}
        fun onClients(clients: List<LinkedBrowser>) {}
    }

    companion object {
        private const val MAX_BACKOFF_MS = 60_000L
        private const val SEARCH_LIMIT = 20
        private const val ARTWORK_MAX_PX = 320
        /** Told to the relay so browsers know this build can run a party. */
        private val FEATURES = listOf("queue")
        /** What a party guest may ask for; the relay enforces this too. */
        private val GUEST_ACTIONS = setOf("search", "queue.add", "queue.remove")

        @Volatile private var instance: RelayClient? = null

        fun get(context: Context): RelayClient =
            instance ?: synchronized(this) {
                instance ?: RelayClient(context.applicationContext).also { instance = it }
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val scope = MainScope()
    private val bridge = MediaBridge.get(context)
    private val publisher = StatePublisher()
    private val party: PartyController by lazy {
        PartyController.get(context).also { p ->
            p.sendQueue = { queue -> send(JSONObject().put("type", "queue").put("queue", queue ?: JSONObject.NULL)) }
        }
    }

    // Protocol-level pings every 25s notice a dead connection (e.g. a NAT
    // mapping dropped while the phone slept) and trigger a reconnect.
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var generation = 0 // callbacks from superseded sockets are ignored
    private var wanted = false
    private var attempt = 0
    private var currentNetwork: Network? = null
    private var networkCallbackRegistered = false
    private val listeners = mutableSetOf<Listener>()

    var status = Status.OFFLINE
        private set
    var lastError: String? = null
        private set
    var clients: List<LinkedBrowser> = emptyList()
        private set
    var pairCode: PairCode? = null
        private set

    private val reconnect = Runnable { if (wanted && socket == null) open() }
    private val onMedia: (NowPlaying) -> Unit = { publish(it) }

    // ------------------------------------------------------------- lifecycle

    fun start() = main.post {
        if (wanted) return@post
        wanted = true
        registerNetworkCallback()
        bridge.start()
        bridge.addListener(onMedia)
        // A party that was running before the app restarted resumes watching playback now.
        party.isActive
        open()
    }

    fun stop() = main.post {
        if (!wanted) return@post
        wanted = false
        main.removeCallbacks(reconnect)
        bridge.removeListener(onMedia)
        generation++
        socket?.close(1000, "Stopped")
        socket = null
        setStatus(Status.OFFLINE, null)
    }

    fun addListener(listener: Listener) = main.post {
        listeners += listener
        listener.onStatus(status, lastError)
        listener.onClients(clients)
        pairCode?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { listener.onPairCode(it) }
    }

    fun removeListener(listener: Listener) = main.post { listeners -= listener }

    // -------------------------------------------------------------- requests

    /** Asks the relay for a pairing code; the answer arrives via [Listener.onPairCode]. */
    fun requestPairCode(): Boolean = send(JSONObject().put("type", "pair.create"))

    fun revoke(clientId: String): Boolean = send(JSONObject().put("type", "clients.revoke").put("clientId", clientId))

    /** Party mode. Replies arrive through [PartyController]'s listeners. */
    fun startParty(): Boolean = send(JSONObject().put("type", "party.start"))

    fun endParty(): Boolean = send(JSONObject().put("type", "party.end"))

    fun newPartyLink(): Boolean = send(JSONObject().put("type", "party.newLink"))

    fun removeGuest(guestId: String): Boolean = send(JSONObject().put("type", "guests.remove").put("guestId", guestId))

    // ------------------------------------------------------------ connection

    private fun open() {
        main.removeCallbacks(reconnect)
        val device = Identity.get(context)
        val url = BuildConfig.RELAY_URL.trimEnd('/') + "/v1/ws?device=" + device.id
        val gen = ++generation
        setStatus(Status.CONNECTING, lastError)

        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // First message authenticates; the secret never goes in the URL.
                webSocket.send(
                    JSONObject()
                        .put("type", "auth")
                        .put("role", "device")
                        .put("secret", device.secret)
                        .put("features", JSONArray(FEATURES))
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (gen == generation) handle(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { if (gen == generation) dropped(code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (gen == generation) dropped(null, t.message ?: t.javaClass.simpleName) }
            }
        })
    }

    private fun dropped(code: Int?, reason: String) {
        socket = null
        publisher.reset()
        val error = when (code) {
            4001 -> "The relay rejected this phone's credentials"
            4000 -> "Replaced by another connection from this phone"
            null -> reason
            else -> "Disconnected ($code${if (reason.isNotEmpty()) ": $reason" else ""})"
        }
        if (!wanted) {
            setStatus(Status.OFFLINE, null)
            return
        }
        setStatus(Status.OFFLINE, error)

        val base = min(MAX_BACKOFF_MS, 1_000L shl min(attempt, 6))
        attempt++
        // Jitter, so a relay restart doesn't get every phone back at once.
        main.postDelayed(reconnect, base / 2 + Random.nextLong(base / 2 + 1))
    }

    /** Reconnect immediately when the network changes, instead of waiting for a ping to time out. */
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val changed = currentNetwork != null && currentNetwork != network
                    currentNetwork = network
                    if (!wanted) return@post
                    if (changed && socket != null) {
                        generation++
                        socket?.cancel()
                        socket = null
                    }
                    if (socket == null) {
                        attempt = 0
                        open()
                    }
                }
            }
        }, main)
        networkCallbackRegistered = true
    }

    // -------------------------------------------------------------- messages

    private fun handle(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return
        }
        when (msg.optString("type")) {
            "ready" -> {
                attempt = 0
                setStatus(Status.CONNECTED, null)
                updateClients(msg.optJSONArray("clients"))
                publisher.reset()
                publish(bridge.snapshot())
                // The phone owns the queue: after reconnecting, the relay's copy may be stale.
                party.onRelayParty(msg.optJSONObject("party"))
                party.onRelayGuests(msg.optJSONArray("guests"))
                party.republish()
            }
            "party" -> party.onRelayParty(msg.optJSONObject("party"))
            "guests" -> party.onRelayGuests(msg.optJSONArray("guests"))
            "cmd" -> execute(msg)
            "pair.code" -> {
                val code = PairCode(msg.optString("code"), msg.optLong("expiresAt"))
                pairCode = code
                listeners.toList().forEach { it.onPairCode(code) }
            }
            "clients" -> updateClients(msg.optJSONArray("clients"))
        }
    }

    private fun execute(msg: JSONObject) {
        val id = msg.optString("id")
        val args = msg.optJSONObject("args") ?: JSONObject()
        val ok = { data: Any? -> reply(id, true, data, null) }
        val fail = { error: String -> reply(id, false, null, error) }

        fun transport(action: () -> Unit) {
            if (!bridge.hasSession()) return fail("nothing_playing")
            action()
            ok(null)
        }

        val action = msg.optString("action")
        // Set by the relay. Older relays don't send it: then it's a linked browser.
        val from = Requester.fromJson(msg.optJSONObject("from")) ?: Requester("unknown", "Remote", "client")
        if (from.isGuest && action !in GUEST_ACTIONS) return fail("forbidden")

        when (action) {
            "play" -> transport { bridge.play() }
            "pause" -> transport { bridge.pause() }
            "playPause" -> transport { bridge.playPause() }
            // During a party, "next" means the next request, when there is one.
            "next" -> if (party.skip()) ok(null) else transport { bridge.next() }
            "previous" -> transport { bridge.previous() }
            "seek" -> {
                val position = args.optLong("positionMs", -1)
                if (position < 0) fail("bad_args") else transport { bridge.seekTo(position) }
            }
            "volume" -> {
                if (!args.has("value")) {
                    fail("bad_args")
                } else {
                    bridge.setVolume(args.optInt("value"))
                    ok(null)
                }
            }
            "playVideo" -> {
                // Starts the track through YouTube Music's media session so its
                // screen doesn't open; only falls back to launching the app.
                val videoId = args.optString("videoId")
                val title = args.optString("title").ifEmpty { null }
                val artist = args.optString("artist").ifEmpty { null }
                scope.launch {
                    when (val outcome = BackgroundPlayer.get(context).play(videoId, title, artist)) {
                        is BackgroundPlayer.Outcome.Played -> {
                            party.onHostPlayed()
                            ok(JSONObject().put("method", outcome.method.name))
                        }
                        is BackgroundPlayer.Outcome.Failed -> fail(outcome.reason)
                    }
                }
            }
            "playPlaylist" -> {
                // Without this permission Android silently ignores the launch
                // while the app is in the background, so say so instead.
                if (!Settings.canDrawOverlays(context)) return fail("overlay_permission_missing")
                when (val result = YtMusicLauncher.playPlaylist(context, args.optString("playlistId"))) {
                    is YtMusicLauncher.Result.Started -> ok(null)
                    is YtMusicLauncher.Result.Failed -> fail(result.reason)
                }
            }
            "search" -> {
                val query = args.optString("query").trim()
                if (query.isEmpty()) return fail("bad_args")
                workers.execute {
                    val outcome = runCatching { YtMusicSearch.search(query) }
                    main.post {
                        outcome
                            .onSuccess { ok(searchJson(it.take(SEARCH_LIMIT))) }
                            .onFailure { fail("search_failed: ${it.message}") }
                    }
                }
            }
            "queue.add", "queue.remove", "queue.move", "queue.clear" ->
                when (val result = party.command(action, args, from)) {
                    is PartyController.CommandResult.Ok -> ok(result.data)
                    is PartyController.CommandResult.Error -> fail(result.code)
                }
            else -> fail("unknown_action")
        }
    }

    private fun reply(id: String, ok: Boolean, data: Any?, error: String?) {
        val message = JSONObject().put("type", "result").put("id", id).put("ok", ok)
        if (ok) message.put("data", data ?: JSONObject.NULL) else message.put("error", error)
        socket?.send(message.toString())
    }

    private fun updateClients(array: JSONArray?) {
        array ?: return
        clients = (0 until array.length()).mapNotNull { i ->
            val c = array.optJSONObject(i) ?: return@mapNotNull null
            LinkedBrowser(
                clientId = c.optString("clientId"),
                name = c.optString("name", "Browser"),
                createdAt = c.optLong("createdAt"),
                lastSeenAt = if (c.isNull("lastSeenAt")) null else c.optLong("lastSeenAt"),
            )
        }
        // A new browser just used the code, so it's spent.
        pairCode = null
        listeners.toList().forEach { it.onClients(clients) }
    }

    // ----------------------------------------------------------------- state

    private fun publish(s: NowPlaying) {
        if (status != Status.CONNECTED) return
        val now = System.currentTimeMillis()
        val decision = publisher.decide(s, now)
        val artworkKey = Integer.toHexString(StatePublisher.trackKey(s).hashCode())

        if (decision.state) {
            val state = JSONObject()
                .put("source", s.source)
                .put("packageName", s.packageName ?: JSONObject.NULL)
                .put("title", s.title ?: JSONObject.NULL)
                .put("artist", s.artist ?: JSONObject.NULL)
                .put("album", s.album ?: JSONObject.NULL)
                .put("durationMs", s.durationMs ?: JSONObject.NULL)
                .put("positionMs", s.positionMs ?: JSONObject.NULL)
                .put("positionAt", now)
                .put("playback", s.state)
                .put("volume", s.volume)
                .put("maxVolume", s.maxVolume)
                .put("artworkKey", artworkKey)
            socket?.send(JSONObject().put("type", "state").put("state", state).toString())
        }

        if (decision.artwork) {
            val bitmap = bridge.artwork()
            workers.execute {
                val data = bitmap?.let { runCatching { encodeArtwork(it) }.getOrNull() }
                main.post {
                    val artwork = JSONObject()
                        .put("key", artworkKey)
                        .put("mime", "image/jpeg")
                        .put("data", data ?: JSONObject.NULL)
                    socket?.send(JSONObject().put("type", "artwork").put("artwork", artwork).toString())
                }
            }
        }
    }

    private fun encodeArtwork(source: Bitmap): String {
        val scale = min(1f, ARTWORK_MAX_PX.toFloat() / maxOf(source.width, source.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else {
            source
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun searchJson(results: List<SearchResult>): JSONArray {
        val array = JSONArray()
        for (r in results) {
            array.put(
                JSONObject()
                    .put("videoId", r.videoId)
                    .put("title", r.title)
                    .put("artist", r.artist ?: JSONObject.NULL)
                    .put("album", r.album ?: JSONObject.NULL)
                    .put("duration", r.duration ?: JSONObject.NULL)
                    .put("thumbnail", r.thumbnail ?: JSONObject.NULL)
            )
        }
        return array
    }

    // --------------------------------------------------------------- helpers

    private fun send(message: JSONObject): Boolean {
        if (status != Status.CONNECTED) return false
        return socket?.send(message.toString()) ?: false
    }

    private fun setStatus(next: Status, error: String?) {
        if (next == status && error == lastError) return
        status = next
        lastError = error
        listeners.toList().forEach { it.onStatus(status, lastError) }
    }
}
