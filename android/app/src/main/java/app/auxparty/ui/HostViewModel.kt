package app.auxparty.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.auxparty.AppPrefs
import app.auxparty.BackgroundPlayer
import app.auxparty.MediaBridge
import app.auxparty.NowPlaying
import app.auxparty.RelayClient
import app.auxparty.RelayService
import app.auxparty.SearchResult
import app.auxparty.StatePublisher
import app.auxparty.ThemeMode
import app.auxparty.YtMusicLauncher
import app.auxparty.YtMusicSearch
import app.auxparty.party.PartyController
import app.auxparty.party.Requester
import app.auxparty.ui.state.Connection
import app.auxparty.ui.state.GuestUi
import app.auxparty.ui.state.HostUiState
import app.auxparty.ui.state.LinkedBrowserUi
import app.auxparty.ui.state.NowPlayingUi
import app.auxparty.ui.state.PairCodeUi
import app.auxparty.ui.state.PartyUi
import app.auxparty.ui.state.SetupState
import app.auxparty.ui.state.hostErrorMessage
import app.auxparty.ui.state.newlyLinked
import app.auxparty.ui.state.queueAddMessage
import app.auxparty.ui.theme.seedFromArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Adapts the existing data layer (MediaBridge, RelayClient) into one immutable
 * [HostUiState] for Compose. The data layer is unchanged; this only listens.
 * All listener callbacks already arrive on the main thread.
 */
class HostViewModel(app: Application) : AndroidViewModel(app) {

    private val bridge = MediaBridge.get(app)
    private val relay = RelayClient.get(app)
    private val party = PartyController.get(app)
    private val prefs = AppPrefs(app)
    private var partyStartTimeout: Job? = null

    private val _state = MutableStateFlow(
        HostUiState(themeMode = prefs.themeMode, onboardingDone = prefs.onboardingDone),
    )
    val state: StateFlow<HostUiState> = _state.asStateFlow()

    // Artwork colour is computed once per track, off the main thread, and debounced
    // so skipping quickly through tracks doesn't make the whole UI strobe.
    private val seedCache = LruCache<String, Color>(32)
    private var trackKey: String? = null
    private var artworkBitmap: Bitmap? = null
    private var seedJob: Job? = null

    private var clientsSeen = false

    private val onMedia: (NowPlaying) -> Unit = { onMediaState(it) }

    private val relayListener = object : RelayClient.Listener {
        override fun onStatus(status: RelayClient.Status, error: String?) {
            _state.update {
                it.copy(
                    connection = when (status) {
                        RelayClient.Status.CONNECTED -> Connection.Online
                        RelayClient.Status.CONNECTING -> Connection.Connecting
                        RelayClient.Status.OFFLINE -> Connection.Offline
                    },
                    connectionError = error,
                    inviteError = if (status == RelayClient.Status.CONNECTED) null else it.inviteError,
                )
            }
        }

        override fun onPairCode(code: RelayClient.PairCode) {
            _state.update {
                it.copy(
                    pairCode = PairCodeUi(code.code, issuedAt = System.currentTimeMillis(), expiresAt = code.expiresAt),
                    inviteError = null,
                )
            }
        }

        override fun onClients(clients: List<RelayClient.LinkedBrowser>) {
            val mapped = clients.map { LinkedBrowserUi(it.clientId, it.name, it.createdAt, it.lastSeenAt) }
            _state.update { current ->
                // A browser that appears while a code is on screen has just used it.
                val joined = if (clientsSeen && current.pairCode != null) newlyLinked(current.linked, mapped).firstOrNull() else null
                current.copy(
                    linked = mapped,
                    justLinked = joined?.name ?: current.justLinked,
                    pairCode = if (joined != null) null else current.pairCode,
                )
            }
            clientsSeen = true
        }
    }

    private val partyListener = PartyController.Listener { p ->
        _state.update {
            val starting = it.party.starting && !p.active
            if (p.active) partyStartTimeout?.cancel()
            it.copy(
                party = PartyUi(
                    active = p.active,
                    starting = starting,
                    link = p.link,
                    guests = p.guests.map { g -> GuestUi(g.guestId, g.name, g.joinedAt) },
                    current = p.current,
                    upcoming = p.upcoming,
                    limitPerGuest = p.limitPerGuest,
                    log = p.log,
                ),
                partyError = if (p.active) null else it.partyError,
            )
        }
    }

    init {
        bridge.addListener(onMedia)
        relay.addListener(relayListener)
        party.addListener(partyListener)
        refreshSetup()
    }

    override fun onCleared() {
        bridge.removeListener(onMedia)
        relay.removeListener(relayListener)
        party.removeListener(partyListener)
    }

    // ---------------------------------------------------------------- media

    private fun onMediaState(np: NowPlaying) {
        if (np.packageName == null) {
            trackKey = null
            artworkBitmap = null
            seedJob?.cancel()
            _state.update { it.copy(nowPlaying = null, seed = null) }
            return
        }

        val key = StatePublisher.trackKey(np)
        val bitmap = bridge.artwork()
        // Apps sometimes deliver artwork a moment after the title, so re-check
        // the bitmap even when the track is unchanged.
        val artChanged = key != trackKey || (bitmap != null && bitmap !== artworkBitmap)
        trackKey = key

        val artwork = if (artChanged) bitmap?.asImageBitmap() else _state.value.nowPlaying?.artwork
        if (artChanged) {
            artworkBitmap = bitmap
            updateSeed(key, bitmap)
        }

        _state.update {
            it.copy(
                nowPlaying = NowPlayingUi(
                    title = np.title ?: "Unknown track",
                    artist = np.artist,
                    album = np.album,
                    source = np.source,
                    artwork = artwork,
                    playing = np.state == "playing",
                    positionMs = np.positionMs ?: 0,
                    positionAtElapsed = SystemClock.elapsedRealtime(),
                    durationMs = np.durationMs,
                    volume = np.volume,
                    maxVolume = np.maxVolume,
                ),
            )
        }
    }

    private fun updateSeed(key: String, bitmap: Bitmap?) {
        seedJob?.cancel()
        if (bitmap == null) {
            _state.update { it.copy(seed = null) }
            return
        }
        seedCache.get(key)?.let { cached ->
            _state.update { it.copy(seed = cached) }
            return
        }
        seedJob = viewModelScope.launch {
            delay(SEED_DEBOUNCE_MS)
            val seed = withContext(Dispatchers.Default) { runCatching { seedFromArtwork(bitmap) }.getOrNull() }
            if (seed != null && key == trackKey) {
                seedCache.put(key, seed)
                _state.update { it.copy(seed = seed) }
            }
        }
    }

    fun playPause() = bridge.playPause()

    /** During a party with requests waiting, "next" plays the next request. */
    fun next() {
        if (!party.skip()) bridge.next()
    }
    fun previous() = bridge.previous()
    fun setVolume(value: Int) = bridge.setVolume(value)

    fun seekTo(fraction: Float) {
        val duration = _state.value.nowPlaying?.durationMs ?: return
        bridge.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    // --------------------------------------------------------------- invite

    fun requestInvite() {
        _state.update { it.copy(justLinked = null, inviteError = null) }
        if (!relay.requestPairCode()) {
            _state.update { it.copy(inviteError = "offline") }
        }
    }

    fun dismissInvite() {
        _state.update { it.copy(pairCode = null, justLinked = null, inviteError = null) }
    }

    fun revoke(id: String) {
        relay.revoke(id)
    }

    // ---------------------------------------------------------------- party

    fun startParty() {
        if (_state.value.party.active) return
        if (!relay.startParty()) {
            _state.update { it.copy(partyError = "offline") }
            return
        }
        _state.update { it.copy(party = it.party.copy(starting = true), partyError = null) }
        // If the relay never answers, don't leave the switch stuck halfway.
        partyStartTimeout?.cancel()
        partyStartTimeout = viewModelScope.launch {
            delay(PARTY_START_TIMEOUT_MS)
            _state.update {
                if (it.party.active) it
                else it.copy(party = it.party.copy(starting = false), partyError = "timeout")
            }
        }
    }

    fun endParty() {
        if (!relay.endParty()) _state.update { it.copy(partyError = "offline") }
    }

    fun newPartyLink() {
        if (!relay.newPartyLink()) _state.update { it.copy(partyError = "offline") }
    }

    fun removeGuest(id: String) {
        if (!relay.removeGuest(id)) _state.update { it.copy(partyError = "offline") }
    }

    fun removeFromQueue(itemId: String) {
        party.remove(itemId)
    }

    fun playNextInQueue(itemId: String) {
        party.playNext(itemId)
    }

    fun clearQueue() {
        party.clear()
    }

    fun setGuestLimit(limit: Int) = party.setLimitPerGuest(limit)

    fun dismissPartyError() {
        _state.update { it.copy(partyError = null) }
    }

    // --------------------------------------------------------------- search

    private var searchJob: Job? = null

    /** Typing searches after a short pause; [now] searches immediately (keyboard search key). */
    fun setSearchQuery(query: String, now: Boolean = false) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _state.update { it.copy(search = it.search.copy(searching = false, results = emptyList(), searched = false, error = null)) }
            return
        }
        searchJob = viewModelScope.launch {
            if (!now) delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(search = it.search.copy(searching = true, error = null)) }
            val outcome = withContext(Dispatchers.IO) { runCatching { YtMusicSearch.search(q).take(SEARCH_LIMIT) } }
            _state.update {
                it.copy(
                    search = it.search.copy(
                        searching = false,
                        searched = true,
                        results = outcome.getOrDefault(emptyList()),
                        error = outcome.exceptionOrNull()?.let { "Search failed. Check the connection and try again." },
                    ),
                )
            }
        }
    }

    /** Plays a result straight away, in the background like a browser's pick. */
    fun playNow(result: SearchResult) {
        if (_state.value.search.busyVideoId != null) return
        _state.update { it.copy(search = it.search.copy(busyVideoId = result.videoId)) }
        viewModelScope.launch {
            val outcome = BackgroundPlayer.get(getApplication<Application>()).play(result.videoId, result.title, result.artist)
            val message = when (outcome) {
                is BackgroundPlayer.Outcome.Played -> {
                    // During a party this is the host's own pick: it plays to the end, then the queue continues.
                    party.onHostPlayed()
                    "Playing “${result.title}”"
                }
                is BackgroundPlayer.Outcome.Failed -> hostErrorMessage(outcome.reason)
            }
            _state.update { it.copy(search = it.search.copy(busyVideoId = null, message = message)) }
        }
    }

    /** Adds a result to the party queue as the host (no per-guest limit). */
    fun addToQueue(result: SearchResult) {
        val args = JSONObject()
            .put("videoId", result.videoId)
            .put("title", result.title)
            .put("artist", result.artist ?: JSONObject.NULL)
            .put("album", result.album ?: JSONObject.NULL)
            .put("duration", result.duration ?: JSONObject.NULL)
            .put("thumbnail", result.thumbnail ?: JSONObject.NULL)
        val message = when (val outcome = party.command("queue.add", args, Requester.HOST)) {
            is PartyController.CommandResult.Ok -> {
                val data = outcome.data as? JSONObject
                queueAddMessage(result.title, data?.optInt("position", 1) ?: 1, data?.optBoolean("duplicate") ?: false)
            }
            is PartyController.CommandResult.Error -> hostErrorMessage(outcome.code)
        }
        _state.update { it.copy(search = it.search.copy(message = message)) }
    }

    fun consumeSearchMessage() {
        _state.update { it.copy(search = it.search.copy(message = null)) }
    }

    // ---------------------------------------------------------------- setup

    /** Call whenever the app comes to the foreground: permissions change in Settings. */
    fun refreshSetup() {
        val app = getApplication<Application>()
        val power = app.getSystemService(PowerManager::class.java)
        val setup = SetupState(
            notificationAccess = bridge.hasNotificationAccess(),
            overlay = Settings.canDrawOverlays(app),
            battery = power?.isIgnoringBatteryOptimizations(app.packageName) == true,
            notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                null
            },
            ytmPackage = YtMusicLauncher.findPackage(app),
        )
        _state.update { it.copy(setup = setup) }
        bridge.start()
        // Coming to the foreground is always an allowed moment to start the service.
        RelayService.start(app)
    }

    fun completeOnboarding() {
        prefs.onboardingDone = true
        _state.update { it.copy(onboardingDone = true) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    private companion object {
        const val SEED_DEBOUNCE_MS = 300L
        const val PARTY_START_TIMEOUT_MS = 10_000L
        const val SEARCH_DEBOUNCE_MS = 400L
        const val SEARCH_LIMIT = 20
    }
}
