package app.musicremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.musicremote.ui.home.Connection
import app.musicremote.ui.home.HomeScreen
import app.musicremote.ui.home.HomeUiState
import app.musicremote.ui.home.NowPlayingUi
import app.musicremote.ui.theme.AuxpartyTheme
import app.musicremote.ui.theme.seedFromArtwork
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders screens to PNGs for design review. CI publishes the output to the
 * `ui-screenshots` release. Run: gradle recordRoborazziDebug
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class DirectionScreenshots {

    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        // Playing screens contain endless animations (the spinning artwork, the
        // wave), so drive the clock by hand instead of waiting for idle.
        compose.mainClock.autoAdvance = false
        compose.setContent(content)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun playing(art: android.graphics.Bitmap, title: String, artist: String, playing: Boolean = true) = NowPlayingUi(
        title = title,
        artist = artist,
        source = "YouTube Music",
        artwork = art.asImageBitmap(),
        playing = playing,
        positionMs = 83_000,
        durationMs = 231_000,
        volume = 9,
        maxVolume = 15,
    )

    @Test
    fun home_playing_sunset_light() {
        val art = SampleArtwork.sunset()
        capture("home_playing_sunset_light") {
            AuxpartyTheme(seed = seedFromArtwork(art), darkTheme = false) {
                HomeScreen(HomeUiState(Connection.Online, playing(art, "Golden Hour Drive", "The Midnight Arcade"), linkedBrowsers = 2))
            }
        }
    }

    @Test
    fun home_playing_sunset_dark() {
        val art = SampleArtwork.sunset()
        capture("home_playing_sunset_dark") {
            AuxpartyTheme(seed = seedFromArtwork(art), darkTheme = true) {
                HomeScreen(HomeUiState(Connection.Online, playing(art, "Golden Hour Drive", "The Midnight Arcade"), linkedBrowsers = 2))
            }
        }
    }

    @Test
    fun home_paused_ocean_light() {
        val art = SampleArtwork.ocean()
        capture("home_paused_ocean_light") {
            AuxpartyTheme(seed = seedFromArtwork(art), darkTheme = false) {
                HomeScreen(HomeUiState(Connection.Online, playing(art, "Tidal", "Harbour Lights", playing = false), linkedBrowsers = 1))
            }
        }
    }

    @Test
    fun home_playing_ocean_dark() {
        val art = SampleArtwork.ocean()
        capture("home_playing_ocean_dark") {
            AuxpartyTheme(seed = seedFromArtwork(art), darkTheme = true) {
                HomeScreen(HomeUiState(Connection.Online, playing(art, "Tidal", "Harbour Lights"), linkedBrowsers = 1))
            }
        }
    }

    @Test
    fun home_nothing_playing_dark() {
        capture("home_nothing_playing_dark") {
            AuxpartyTheme(darkTheme = true) {
                HomeScreen(HomeUiState(Connection.Connecting, nowPlaying = null, linkedBrowsers = 0))
            }
        }
    }
}
