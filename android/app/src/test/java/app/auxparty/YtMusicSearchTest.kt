package app.auxparty

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicSearchTest {

    // A real (trimmed) response for "elderbrook numb". If YouTube changes its
    // response shape, re-capture this and update the parser together.
    private fun fixture(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("search_songs.json")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun parsesEveryResult() {
        val results = YtMusicSearch.parse(fixture())
        assertEquals(3, results.size)
    }

    @Test
    fun readsIdTitleAndMetadata() {
        val first = YtMusicSearch.parse(fixture()).first()
        assertEquals("RvegizX3GqY", first.videoId)
        assertEquals("Numb", first.title)
        assertEquals("Elderbrook", first.artist)
        assertEquals("Why Do We Shake In The Cold?", first.album)
        assertEquals("3:51", first.duration)
        assertTrue(first.thumbnail!!.startsWith("https://"))
    }

    @Test
    fun keepsJoinedArtistNamesIntact() {
        val third = YtMusicSearch.parse(fixture())[2]
        assertEquals("Elderbrook & Bob Moses", third.artist)
    }

    @Test
    fun unexpectedShapeYieldsNoResultsRatherThanCrashing() {
        assertEquals(0, YtMusicSearch.parse(JSONObject("{}")).size)
        assertEquals(0, YtMusicSearch.parse(JSONObject("""{"contents":{"somethingNew":{}}}""")).size)
    }

    @Test
    fun missingOptionalFieldsBecomeNull() {
        val json = JSONObject(
            """
            {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
              "sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"abcdefghijk"},
                  "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Solo"}]}}}]
                }}
              ]}}]}}}}]}}}
            """.trimIndent()
        )
        val r = YtMusicSearch.parse(json).single()
        assertEquals("abcdefghijk", r.videoId)
        assertEquals("Solo", r.title)
        assertNull(r.artist)
        assertNull(r.duration)
        assertNull(r.thumbnail)
    }
}
