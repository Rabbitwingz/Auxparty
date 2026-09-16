package app.musicremote.ui

import app.musicremote.SearchResult
import app.musicremote.ui.state.hostErrorMessage
import app.musicremote.ui.state.queueAddMessage
import app.musicremote.ui.state.resultSupporting
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchUiTest {

    @Test
    fun supportingLineSkipsMissingParts() {
        assertEquals("Elderbrook · Numb · 3:51", resultSupporting(SearchResult("id", "Numb", "Elderbrook", "Numb", "3:51", null)))
        assertEquals("3:51", resultSupporting(SearchResult("id", "Numb", null, null, "3:51", null)))
    }

    @Test
    fun queueFeedbackSaysWhereTheSongLanded() {
        assertEquals("Playing “Numb” now", queueAddMessage("Numb", 0, duplicate = false))
        assertEquals("“Numb” is playing now", queueAddMessage("Numb", 0, duplicate = true))
        assertEquals("Added “Numb” · #3 in line", queueAddMessage("Numb", 3, duplicate = false))
        assertEquals("“Numb” is already in the queue (#2)", queueAddMessage("Numb", 2, duplicate = true))
    }

    @Test
    fun errorsReadAsSentences() {
        assertEquals("Allow Auxparty to display over other apps so it can start songs.", hostErrorMessage("overlay_permission_missing"))
        assertEquals("something_else", hostErrorMessage("something_else"))
    }
}
