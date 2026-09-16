package app.musicremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.musicremote.ThemeMode
import app.musicremote.ui.Fixtures.playing
import app.musicremote.ui.Fixtures.state
import app.musicremote.ui.devices.DevicesScreen
import app.musicremote.ui.home.HomeScreen
import app.musicremote.ui.home.InviteContent
import app.musicremote.ui.onboarding.OnboardingStep
import app.musicremote.ui.settings.SettingsScreen
import app.musicremote.ui.state.Connection
import app.musicremote.ui.state.PairCodeUi
import app.musicremote.ui.state.SetupState
import app.musicremote.ui.state.SetupStep
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen and important state, light and dark, for design review.
 * CI publishes the PNGs to the `ui-screenshots` release. Run: gradle recordRoborazziDebug
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenScreenshots {

    @get:Rule val compose = createComposeRule()

    // ----------------------------------------------------------------- home

    @Test fun home_playing_sunset_light() {
        val art = SampleArtwork.sunset()
        val s = state(art, playing(art, "Golden Hour Drive", "The Midnight Arcade"))
        compose.shoot("home_playing_sunset_light", s, dark = false) { HomeScreen(s) }
    }

    @Test fun home_playing_ocean_dark() {
        val art = SampleArtwork.ocean()
        val s = state(art, playing(art, "Tidal", "Harbour Lights"))
        compose.shoot("home_playing_ocean_dark", s, dark = true) { HomeScreen(s) }
    }

    @Test fun home_paused_ocean_light() {
        val art = SampleArtwork.ocean()
        val s = state(art, playing(art, "Tidal", "Harbour Lights", playing = false), linked = emptyList())
        compose.shoot("home_paused_ocean_light", s, dark = false) { HomeScreen(s) }
    }

    @Test fun home_nothing_playing_dark() {
        val s = state(connection = Connection.Connecting, linked = emptyList())
        compose.shoot("home_nothing_playing_dark", s, dark = true) { HomeScreen(s) }
    }

    @Test fun home_setup_needed_offline_light() {
        val s = state(connection = Connection.Offline, linked = emptyList(), setup = SetupState(notifications = true))
        compose.shoot("home_setup_needed_offline_light", s, dark = false) { HomeScreen(s) }
    }

    // --------------------------------------------------------------- invite

    @androidx.compose.runtime.Composable
    private fun sheet(content: @androidx.compose.runtime.Composable () -> Unit) {
        // The sheet body on a sheet-like surface, anchored to the bottom as on a phone.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.padding(top = 24.dp)) { content() }
            }
        }
    }

    @Test fun invite_code_light() {
        val art = SampleArtwork.sunset()
        val code = PairCodeUi("K7QM3XPD", issuedAt = NOW - 42_000, expiresAt = NOW + 258_000)
        val s = state(art, playing(art, "Golden Hour Drive", "The Midnight Arcade"), pairCode = code)
        compose.shoot("invite_code_light", s, dark = false) { sheet { InviteContent(s, {}, {}, now = { NOW }) } }
    }

    @Test fun invite_code_expired_dark() {
        val code = PairCodeUi("K7QM3XPD", issuedAt = NOW - 300_000, expiresAt = NOW)
        val s = state(pairCode = code)
        compose.shoot("invite_code_expired_dark", s, dark = true) { sheet { InviteContent(s, {}, {}, now = { NOW }) } }
    }

    @Test fun invite_joined_dark() {
        val art = SampleArtwork.ocean()
        val s = state(art, playing(art, "Tidal", "Harbour Lights"), justLinked = "Chrome on Windows")
        compose.shoot("invite_joined_dark", s, dark = true) { sheet { InviteContent(s, {}, {}, now = { NOW }) } }
    }

    @Test fun invite_offline_light() {
        val s = state(connection = Connection.Offline)
        compose.shoot("invite_offline_light", s, dark = false) { sheet { InviteContent(s, {}, {}, now = { NOW }) } }
    }

    // ----------------------------------------------------------- onboarding

    @Test fun onboarding_welcome_light() {
        val s = state(setup = SetupState(notifications = false))
        compose.shoot("onboarding_welcome_light", s, dark = false) { OnboardingStep(SetupStep.Welcome, s.setup) }
    }

    @Test fun onboarding_notification_access_dark() {
        val s = state(setup = SetupState(notifications = false))
        compose.shoot("onboarding_notification_access_dark", s, dark = true) { OnboardingStep(SetupStep.NotificationAccess, s.setup) }
    }

    @Test fun onboarding_battery_light() {
        val s = state(setup = SetupState(notificationAccess = true, overlay = true, notifications = false))
        compose.shoot("onboarding_battery_light", s, dark = false) { OnboardingStep(SetupStep.Battery, s.setup) }
    }

    @Test fun onboarding_done_dark() {
        val s = state(setup = Fixtures.allGranted)
        compose.shoot("onboarding_done_dark", s, dark = true) { OnboardingStep(SetupStep.Done, s.setup) }
    }

    // ---------------------------------------------------- devices, settings

    @Test fun devices_list_light() {
        val art = SampleArtwork.sunset()
        val s = state(art, playing(art, "Golden Hour Drive", "The Midnight Arcade"))
        compose.shoot("devices_list_light", s, dark = false) { DevicesScreen(s.linked, {}, {}, {}, now = NOW) }
    }

    @Test fun devices_empty_dark() {
        val s = state(linked = emptyList())
        compose.shoot("devices_empty_dark", s, dark = true) { DevicesScreen(emptyList(), {}, {}, {}, now = NOW) }
    }

    @Test fun settings_light() {
        val art = SampleArtwork.ocean()
        val s = state(art, playing(art, "Tidal", "Harbour Lights"), setup = Fixtures.allGranted.copy(battery = false))
        compose.shoot("settings_light", s, dark = false) { SettingsScreen(s) }
    }

    @Test fun settings_dark() {
        val s = state(connection = Connection.Offline).copy(themeMode = ThemeMode.Dark, connectionError = "The relay rejected this phone's credentials")
        compose.shoot("settings_dark", s, dark = true) { SettingsScreen(s) }
    }
}

/** Large text: layouts must survive the biggest system font size. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, fontScale = 2.0f)
class LargeFontScreenshots {

    @get:Rule val compose = createComposeRule()

    @Test fun home_playing_font200() {
        val art = SampleArtwork.sunset()
        val s = state(art, playing(art, "Golden Hour Drive (Extended Sunset Mix)", "The Midnight Arcade"))
        compose.shoot("home_playing_font200", s, dark = false) { HomeScreen(s) }
    }

    @Test fun invite_code_font200() {
        val code = PairCodeUi("K7QM3XPD", issuedAt = NOW - 42_000, expiresAt = NOW + 258_000)
        val s = state(pairCode = code)
        compose.shoot("invite_code_font200", s, dark = true) { InviteContent(s, {}, {}, now = { NOW }) }
    }

    @Test fun onboarding_notification_access_font200() {
        val s = state(setup = SetupState(notifications = false))
        compose.shoot("onboarding_notification_access_font200", s, dark = false) { OnboardingStep(SetupStep.NotificationAccess, s.setup) }
    }
}
