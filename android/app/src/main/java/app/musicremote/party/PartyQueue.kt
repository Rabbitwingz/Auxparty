package app.musicremote.party

import org.json.JSONArray
import org.json.JSONObject

/** Who asked for a song. [role] is "guest", "client" (a linked remote) or "host" (this phone). */
data class Requester(val id: String, val name: String, val role: String) {
    val isGuest: Boolean get() = role == ROLE_GUEST

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("role", role)

    companion object {
        const val ROLE_GUEST = "guest"

        /** The phone itself, when the host manages the queue in the app. */
        val HOST = Requester(id = "host", name = "Host", role = "host")

        fun fromJson(o: JSONObject?): Requester? {
            val id = o?.string("id") ?: return null
            return Requester(id, o.string("name") ?: "Remote", o.string("role") ?: "client")
        }
    }
}

data class QueueItem(
    val itemId: String,
    val videoId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val thumbnail: String?,
    /** The first entry added the song; later ones asked for it again. */
    val requestedBy: List<Requester>,
    val addedAt: Long,
) {
    val requester: Requester get() = requestedBy.first()

    fun toJson(): JSONObject = JSONObject()
        .put("itemId", itemId)
        .put("videoId", videoId)
        .put("title", title)
        .put("artist", artist ?: JSONObject.NULL)
        .put("album", album ?: JSONObject.NULL)
        .put("durationMs", durationMs ?: JSONObject.NULL)
        .put("thumbnail", thumbnail ?: JSONObject.NULL)
        .put("requestedBy", JSONArray().also { a -> requestedBy.forEach { a.put(it.toJson()) } })
        .put("addedAt", addedAt)

    companion object {
        fun fromJson(o: JSONObject?): QueueItem? {
            o ?: return null
            val requesters = o.optJSONArray("requestedBy")
                ?.let { a -> (0 until a.length()).mapNotNull { Requester.fromJson(a.optJSONObject(it)) } }
                .orEmpty()
            return QueueItem(
                itemId = o.string("itemId") ?: return null,
                videoId = o.string("videoId") ?: return null,
                title = o.string("title") ?: "Unknown song",
                artist = o.string("artist"),
                album = o.string("album"),
                durationMs = if (o.isNull("durationMs")) null else o.optLong("durationMs").takeIf { it > 0 },
                thumbnail = o.string("thumbnail"),
                requestedBy = requesters.ifEmpty { listOf(Requester.HOST) },
                addedAt = o.optLong("addedAt"),
            )
        }
    }
}

/** A song someone picked in search, as sent with `queue.add`. */
data class SongRequest(
    val videoId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val thumbnail: String?,
) {
    companion object {
        fun fromArgs(args: JSONObject): SongRequest = SongRequest(
            videoId = args.string("videoId").orEmpty(),
            title = args.string("title"),
            artist = args.string("artist"),
            album = args.string("album"),
            durationMs = args.optLong("durationMs").takeIf { it > 0 } ?: parseDuration(args.string("duration")),
            thumbnail = args.string("thumbnail"),
        )

        /** "3:45" or "1:02:03" → milliseconds. */
        fun parseDuration(text: String?): Long? {
            val parts = text?.trim()?.split(":")?.map { it.toLongOrNull() ?: return null } ?: return null
            if (parts.isEmpty() || parts.size > 3) return null
            return parts.fold(0L) { total, n -> total * 60 + n }.takeIf { it > 0 }?.times(1000)
        }
    }
}

/**
 * The party queue: songs friends asked for, in order, plus the requested song
 * playing now. Holds the rules for who may change what. Not thread-safe; the app
 * uses it from the main thread only. See relay/PROTOCOL.md for the wire format.
 */
class PartyQueue(var limitPerGuest: Int = DEFAULT_LIMIT_PER_GUEST) {

    sealed class AddResult {
        /** [position] is 1-based among upcoming songs, or 0 for the song playing now. */
        data class Added(val item: QueueItem, val position: Int, val duplicate: Boolean) : AddResult()
        data class Refused(val error: String) : AddResult()
    }

    /** The requested song playing now; null while something else plays. */
    var current: QueueItem? = null

    private val upcoming = mutableListOf<QueueItem>()
    val items: List<QueueItem> get() = upcoming.toList()

    fun add(request: SongRequest, from: Requester, now: Long, newId: () -> String): AddResult {
        if (!VIDEO_ID.matches(request.videoId)) return AddResult.Refused(ERROR_BAD_REQUEST)

        // Asking for a song that's already coming (or playing) adds your name to it.
        current?.takeIf { it.videoId == request.videoId }?.let { playing ->
            current = playing.withRequester(from)
            return AddResult.Added(current!!, position = 0, duplicate = true)
        }
        val existing = upcoming.indexOfFirst { it.videoId == request.videoId }
        if (existing >= 0) {
            upcoming[existing] = upcoming[existing].withRequester(from)
            return AddResult.Added(upcoming[existing], position = existing + 1, duplicate = true)
        }

        if (from.isGuest && upcoming.count { it.requester.id == from.id } >= limitPerGuest) {
            return AddResult.Refused(ERROR_LIMIT_REACHED)
        }
        if (upcoming.size >= MAX_ITEMS) return AddResult.Refused(ERROR_QUEUE_FULL)

        val item = QueueItem(
            itemId = newId(),
            videoId = request.videoId,
            title = request.title?.take(MAX_TEXT)?.ifBlank { null } ?: "Unknown song",
            artist = request.artist?.take(MAX_TEXT)?.ifBlank { null },
            album = request.album?.take(MAX_TEXT)?.ifBlank { null },
            durationMs = request.durationMs?.takeIf { it > 0 },
            // Only YouTube's own image hosts: browsers load these, so no arbitrary URLs.
            thumbnail = request.thumbnail?.takeIf { THUMBNAIL.matches(it) },
            requestedBy = listOf(from),
            addedAt = now,
        )
        upcoming += item
        return AddResult.Added(item, position = upcoming.size, duplicate = false)
    }

    /** Returns an error code, or null on success. Guests may only remove songs they added. */
    fun remove(itemId: String, from: Requester): String? {
        val index = upcoming.indexOfFirst { it.itemId == itemId }
        if (index < 0) return ERROR_NOT_FOUND
        if (from.isGuest && upcoming[index].requester.id != from.id) return ERROR_NOT_YOURS
        upcoming.removeAt(index)
        return null
    }

    fun move(itemId: String, toIndex: Int): String? {
        val index = upcoming.indexOfFirst { it.itemId == itemId }
        if (index < 0) return ERROR_NOT_FOUND
        val item = upcoming.removeAt(index)
        upcoming.add(toIndex.coerceIn(0, upcoming.size), item)
        return null
    }

    fun clear() {
        upcoming.clear()
    }

    /** Takes the next song off the queue; it becomes [current]. */
    fun popNext(): QueueItem? {
        val next = upcoming.removeFirstOrNull() ?: return null
        current = next
        return next
    }

    /** Drops a song that couldn't be played. */
    fun drop(itemId: String) {
        if (current?.itemId == itemId) current = null
        upcoming.removeAll { it.itemId == itemId }
    }

    fun hasNext(): Boolean = upcoming.isNotEmpty()

    fun reset() {
        current = null
        upcoming.clear()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("current", current?.toJson() ?: JSONObject.NULL)
        .put("items", JSONArray().also { a -> upcoming.forEach { a.put(it.toJson()) } })
        .put("limitPerGuest", limitPerGuest)

    fun restore(json: JSONObject) {
        current = QueueItem.fromJson(json.optJSONObject("current"))
        upcoming.clear()
        json.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) QueueItem.fromJson(a.optJSONObject(i))?.let { upcoming += it }
        }
        limitPerGuest = json.optInt("limitPerGuest", DEFAULT_LIMIT_PER_GUEST).coerceIn(1, 50)
    }

    private fun QueueItem.withRequester(from: Requester): QueueItem =
        if (requestedBy.any { it.id == from.id }) this else copy(requestedBy = requestedBy + from)

    companion object {
        const val DEFAULT_LIMIT_PER_GUEST = 3
        const val MAX_ITEMS = 150
        private const val MAX_TEXT = 200

        const val ERROR_BAD_REQUEST = "bad_request"
        const val ERROR_LIMIT_REACHED = "limit_reached"
        const val ERROR_QUEUE_FULL = "queue_full"
        const val ERROR_NOT_FOUND = "not_found"
        const val ERROR_NOT_YOURS = "not_yours"

        private val VIDEO_ID = Regex("^[\\w-]{6,20}$")
        private val THUMBNAIL = Regex("^https://([a-z0-9-]+\\.)*(googleusercontent|ytimg|ggpht)\\.com/\\S{1,500}$")
    }
}

/**
 * The value at [key] as a string, or null when missing or JSON null. Android's
 * optString turns JSON null into the text "null", so don't use it for optional fields.
 */
internal fun JSONObject.string(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
