package app.musicremote

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val duration: String?,
    val thumbnail: String?,
)

/**
 * YouTube Music song search via the same internal API the web player uses.
 *
 * Runs on the phone on purpose: each user searches from their own connection,
 * so there is no shared server IP for YouTube to rate-limit or block.
 * Blocking network call — never call it on the main thread.
 */
object YtMusicSearch {
    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20250219.01.00"

    // Restricts results to songs (not videos, albums or artists).
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

    fun search(query: String): List<SearchResult> {
        val client = JSONObject()
            .put("clientName", CLIENT_NAME)
            .put("clientVersion", CLIENT_VERSION)
            // Parsing relies on English separators; the region still follows the user.
            .put("hl", "en")
            .put("gl", Locale.getDefault().country.ifEmpty { "US" })
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)
            .put("params", SONGS_FILTER)

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("YouTube Music search failed: HTTP $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    /** Separated from the request so it can be tested against a saved response. */
    fun parse(root: JSONObject): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return out

        for (i in 0 until sections.length()) {
            val items = sections.optJSONObject(i)
                ?.optJSONObject("musicShelfRenderer")
                ?.optJSONArray("contents")
                ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)
                    ?.optJSONObject("musicResponsiveListItemRenderer")
                    ?: continue
                val columns = item.optJSONArray("flexColumns") ?: continue

                val titleRuns = runs(columns, 0)
                val title = titleRuns.joinToString("") { it.optString("text") }
                val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")?.ifEmpty { null }
                    ?: titleRuns.firstOrNull()
                        ?.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId")
                        ?.ifEmpty { null }
                    ?: continue

                // "Artist • Album • 3:51"
                val meta = runs(columns, 1).joinToString("") { it.optString("text") }.split(" • ")
                val thumbnails = item.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                val thumbnail = thumbnails
                    ?.let { it.optJSONObject(it.length() - 1) }
                    ?.optString("url")
                    ?.ifEmpty { null }

                out += SearchResult(
                    videoId = videoId,
                    title = title,
                    artist = meta.getOrNull(0)?.ifEmpty { null },
                    album = meta.getOrNull(1)?.ifEmpty { null },
                    duration = meta.getOrNull(2)?.ifEmpty { null },
                    thumbnail = thumbnail,
                )
            }
        }
        return out
    }

    private fun runs(columns: JSONArray, index: Int): List<JSONObject> {
        val runs = columns.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return emptyList()
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
    }
}
