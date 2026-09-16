package app.musicremote.party

import app.musicremote.party.PartyQueue.AddResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyQueueTest {

    private val sam = Requester("gsam", "Sam", "guest")
    private val priya = Requester("gpriya", "Priya", "guest")
    private val pc = Requester("pc", "PC", "client")

    private var ids = 0
    private val queue = PartyQueue(limitPerGuest = 3)

    private fun song(videoId: String, title: String = "Song $videoId", thumbnail: String? = null) =
        SongRequest(videoId, title, "Artist", "Album", 180_000, thumbnail)

    private fun add(videoId: String, from: Requester) = queue.add(song(videoId), from, now = 1_000) { "id${++ids}" }

    private fun added(result: AddResult) = result as? AddResult.Added ?: error("expected Added, got $result")

    @Test
    fun songsQueueInOrderWithTheirPlace() {
        assertEquals(1, added(add("aaaaaaaaaaa", sam)).position)
        assertEquals(2, added(add("bbbbbbbbbbb", priya)).position)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), queue.items.map { it.videoId })
        assertEquals("Sam", queue.items[0].requester.name)
    }

    @Test
    fun askingForAQueuedSongAgainAddsYourNameInsteadOfADuplicate() {
        add("aaaaaaaaaaa", sam)
        val again = added(add("aaaaaaaaaaa", priya))
        assertTrue(again.duplicate)
        assertEquals(1, again.position)
        assertEquals(1, queue.items.size)
        assertEquals(listOf("Sam", "Priya"), queue.items[0].requestedBy.map { it.name })

        // The same person asking twice isn't listed twice.
        add("aaaaaaaaaaa", priya)
        assertEquals(2, queue.items[0].requestedBy.size)
    }

    @Test
    fun askingForTheSongPlayingNowIsADuplicateAtPositionZero() {
        add("aaaaaaaaaaa", sam)
        queue.popNext()
        val again = added(add("aaaaaaaaaaa", priya))
        assertEquals(0, again.position)
        assertTrue(again.duplicate)
        assertTrue(queue.items.isEmpty())
        assertEquals(2, queue.current!!.requestedBy.size)
    }

    @Test
    fun guestsAreLimitedToThreeWaitingSongsButRemotesAreNot() {
        listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").forEach { added(add(it, sam)) }
        assertEquals(AddResult.Refused(PartyQueue.ERROR_LIMIT_REACHED), add("ddddddddddd", sam))
        // Songs someone else added don't count against Sam, even if Sam asked for them too.
        added(add("eeeeeeeeeee", priya))
        added(add("eeeeeeeeeee", sam))

        // Once one of Sam's songs starts playing, Sam can request another.
        queue.popNext()
        added(add("ddddddddddd", sam))

        repeat(5) { added(add("remote00000$it", pc)) }
    }

    @Test
    fun badVideoIdsAreRefused() {
        assertEquals(AddResult.Refused(PartyQueue.ERROR_BAD_REQUEST), add("", sam))
        assertEquals(AddResult.Refused(PartyQueue.ERROR_BAD_REQUEST), add("../../etc", sam))
    }

    @Test
    fun onlyYouTubeImageHostsAreKeptAsThumbnails() {
        val ok = "https://lh3.googleusercontent.com/abc=w120-h120"
        added(queue.add(song("aaaaaaaaaaa", thumbnail = ok), sam, 0) { "a" })
        added(queue.add(song("bbbbbbbbbbb", thumbnail = "https://evil.example/track.png"), sam, 0) { "b" })
        added(queue.add(song("ccccccccccc", thumbnail = "http://i.ytimg.com/vi/x/default.jpg"), sam, 0) { "c" })
        assertEquals(listOf(ok, null, null), queue.items.map { it.thumbnail })
    }

    @Test
    fun guestsRemoveOnlyTheirOwnSongsRemotesRemoveAny() {
        add("aaaaaaaaaaa", sam)
        add("bbbbbbbbbbb", priya)
        val samsSong = queue.items[0].itemId
        val priyasSong = queue.items[1].itemId

        assertEquals(PartyQueue.ERROR_NOT_YOURS, queue.remove(samsSong, priya))
        assertNull(queue.remove(priyasSong, priya))
        assertEquals(PartyQueue.ERROR_NOT_FOUND, queue.remove(priyasSong, priya))
        assertNull(queue.remove(samsSong, pc))
        assertTrue(queue.items.isEmpty())
    }

    @Test
    fun moveClearAndPopNext() {
        add("aaaaaaaaaaa", sam)
        add("bbbbbbbbbbb", sam)
        add("ccccccccccc", priya)
        assertNull(queue.move(queue.items[2].itemId, 0))
        assertEquals(listOf("ccccccccccc", "aaaaaaaaaaa", "bbbbbbbbbbb"), queue.items.map { it.videoId })
        assertNull(queue.move(queue.items[0].itemId, 99))
        assertEquals("ccccccccccc", queue.items.last().videoId)
        assertEquals(PartyQueue.ERROR_NOT_FOUND, queue.move("missing", 0))

        val next = queue.popNext()!!
        assertEquals("aaaaaaaaaaa", next.videoId)
        assertEquals(next, queue.current)
        assertEquals(2, queue.items.size)

        queue.clear()
        assertTrue(queue.items.isEmpty())
        assertEquals(next, queue.current)
        assertNull(queue.popNext())
    }

    @Test
    fun survivesASaveAndRestore() {
        add("aaaaaaaaaaa", sam)
        add("bbbbbbbbbbb", priya)
        add("bbbbbbbbbbb", pc)
        queue.popNext()
        queue.limitPerGuest = 5

        val restored = PartyQueue()
        restored.restore(JSONObject(queue.toJson().toString()))
        assertEquals(queue.current, restored.current)
        assertEquals(queue.items, restored.items)
        assertEquals(5, restored.limitPerGuest)
    }

    @Test
    fun requestsFromBrowserArgs() {
        val args = JSONObject()
            .put("videoId", "RvegizX3GqY")
            .put("title", "Numb")
            .put("artist", JSONObject.NULL)
            .put("duration", "3:51")
        val request = SongRequest.fromArgs(args)
        assertEquals("RvegizX3GqY", request.videoId)
        assertNull(request.artist)
        assertEquals(231_000L, request.durationMs)
    }

    @Test
    fun parsesDurations() {
        assertEquals(231_000L, SongRequest.parseDuration("3:51"))
        assertEquals(3_723_000L, SongRequest.parseDuration("1:02:03"))
        assertNull(SongRequest.parseDuration("soon"))
        assertNull(SongRequest.parseDuration(null))
        assertNull(SongRequest.parseDuration("0:00"))
    }

    @Test
    fun requestersFromTheRelay() {
        assertEquals(sam, Requester.fromJson(sam.toJson()))
        assertEquals(Requester("pc", "Remote", "client"), Requester.fromJson(JSONObject().put("id", "pc").put("name", JSONObject.NULL)))
        assertNull(Requester.fromJson(JSONObject()))
    }
}
