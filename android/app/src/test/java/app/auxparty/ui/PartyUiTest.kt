package app.auxparty.ui

import app.auxparty.party.QueueItem
import app.auxparty.party.Requester
import app.auxparty.ui.state.GuestUi
import app.auxparty.ui.state.PartyUi
import app.auxparty.ui.state.partyShareText
import app.auxparty.ui.state.partySummary
import app.auxparty.ui.state.requestedByFor
import app.auxparty.ui.state.requesterNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyUiTest {

    private fun item(title: String, vararg names: String) = QueueItem(
        itemId = title,
        videoId = "RvegizX3GqY",
        title = title,
        artist = "Elderbrook",
        album = null,
        durationMs = 231_000,
        thumbnail = null,
        requestedBy = names.map { Requester("id-$it", it, "guest") },
        addedAt = 0,
    )

    @Test
    fun requesterNamesCountsExtraRequests() {
        assertEquals("Sam", requesterNames(item("Numb", "Sam")))
        assertEquals("Sam +2", requesterNames(item("Numb", "Sam", "Priya", "Jo")))
    }

    @Test
    fun summaryPluralises() {
        assertEquals("0 guests · queue empty", partySummary(PartyUi(active = true)))
        val party = PartyUi(active = true, guests = listOf(GuestUi("g", "Sam", 0)), upcoming = listOf(item("A", "Sam"), item("B", "Sam")))
        assertEquals("1 guest · 2 in queue", partySummary(party))
    }

    @Test
    fun requestedByOnlyForTheQueuedSongOnScreen() {
        val party = PartyUi(active = true, current = item("Instant Crush (feat. Julian Casablancas)", "Priya"))
        assertEquals("Priya", requestedByFor(party, "Instant Crush (feat. Julian Casablancas)"))
        // YouTube Music's title punctuation can differ from the search result's.
        assertEquals("Priya", requestedByFor(party, "Instant Crush feat. Julian Casablancas"))
        assertNull(requestedByFor(party, "Get Lucky"))
        assertNull(requestedByFor(party.copy(active = false), "Instant Crush (feat. Julian Casablancas)"))
        assertNull(requestedByFor(PartyUi(active = true), "Anything"))
    }

    @Test
    fun shareTextCarriesTheLink() {
        assertTrue(partyShareText("https://auxparty.vercel.app/#party=a.b").endsWith("https://auxparty.vercel.app/#party=a.b"))
    }
}
