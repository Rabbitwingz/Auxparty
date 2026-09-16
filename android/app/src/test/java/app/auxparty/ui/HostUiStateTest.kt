package app.auxparty.ui

import app.auxparty.ui.state.LinkedBrowserUi
import app.auxparty.ui.state.SetupState
import app.auxparty.ui.state.SetupStep
import app.auxparty.ui.state.currentStep
import app.auxparty.ui.state.displayCode
import app.auxparty.ui.state.formatRelative
import app.auxparty.ui.state.formatTime
import app.auxparty.ui.state.newlyLinked
import app.auxparty.ui.state.permissionSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUiStateTest {

    private val none = SetupState(notifications = false)

    @Test
    fun welcomeComesFirst() {
        assertEquals(SetupStep.Welcome, currentStep(none, welcomeSeen = false, skipped = emptySet()))
    }

    @Test
    fun notificationAccessCannotBeSkipped() {
        val step = currentStep(none, welcomeSeen = true, skipped = setOf(SetupStep.NotificationAccess))
        assertEquals(SetupStep.NotificationAccess, step)
    }

    @Test
    fun grantingAPermissionAdvancesToTheNextMissingOne() {
        val afterAccess = none.copy(notificationAccess = true)
        assertEquals(SetupStep.Overlay, currentStep(afterAccess, true, emptySet()))
        assertEquals(SetupStep.Battery, currentStep(afterAccess.copy(overlay = true), true, emptySet()))
    }

    @Test
    fun optionalStepsCanBeSkipped() {
        val s = none.copy(notificationAccess = true)
        val skipped = setOf(SetupStep.Overlay, SetupStep.Battery, SetupStep.Notifications)
        assertEquals(SetupStep.Done, currentStep(s, true, skipped))
    }

    @Test
    fun notificationsStepOnlyOnAndroid13AndUp() {
        val old = SetupState(notificationAccess = true, overlay = true, battery = true, notifications = null)
        assertEquals(SetupStep.Done, currentStep(old, true, emptySet()))
        assertFalse(SetupStep.Notifications in permissionSteps(old))
        assertTrue(SetupStep.Notifications in permissionSteps(none))
    }

    @Test
    fun readyNeedsOnlyNotificationAccess() {
        assertTrue(SetupState(notificationAccess = true).ready)
        assertFalse(SetupState(overlay = true, battery = true, notifications = true).ready)
    }

    @Test
    fun formatsTrackTime() {
        assertEquals("0:00", formatTime(0))
        assertEquals("3:51", formatTime(231_000))
        assertEquals("0:00", formatTime(-5_000))
    }

    @Test
    fun formatsRelativeTimes() {
        val now = 10_000_000_000L
        assertEquals("just now", formatRelative(now - 10_000, now))
        assertEquals("5 min ago", formatRelative(now - 5 * 60_000, now))
        assertEquals("2 hr ago", formatRelative(now - 2 * 3_600_000, now))
        assertEquals("yesterday", formatRelative(now - 30 * 3_600_000L, now))
        assertEquals("4 days ago", formatRelative(now - 4 * 86_400_000L, now))
        assertEquals("3 weeks ago", formatRelative(now - 21 * 86_400_000L, now))
        assertEquals("just now", formatRelative(now + 60_000, now)) // clock skew
    }

    @Test
    fun detectsNewlyLinkedBrowsers() {
        val a = LinkedBrowserUi("a", "Chrome", 0, null)
        val b = LinkedBrowserUi("b", "Safari", 0, null)
        assertEquals(listOf(b), newlyLinked(listOf(a), listOf(a, b)))
        assertEquals(emptyList<LinkedBrowserUi>(), newlyLinked(listOf(a, b), listOf(a)))
    }

    @Test
    fun formatsPairingCodes() {
        assertEquals("K7QM-3XPD", displayCode("K7QM3XPD"))
        assertEquals("K7QM-3XPD", displayCode("k7qm-3xpd"))
        assertEquals("ODD", displayCode("ODD"))
    }
}
