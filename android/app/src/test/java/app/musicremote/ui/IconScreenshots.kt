package app.musicremote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.musicremote.R
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The launcher icon as it appears in a circle mask, a squircle mask, and themed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w600dp-h240dp-xhdpi")
class IconScreenshots {

    @get:Rule val compose = createComposeRule()

    @Test
    fun launcher_icon() {
        compose.setContent {
            Row(
                Modifier.background(Color(0xFFE8E4F0)).padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Adaptive layers are 108dp; launchers show the centre 72dp.
                listOf(CircleShape, RoundedCornerShape(28.dp)).forEach { mask ->
                    Box(Modifier.size(108.dp).clip(mask)) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(108.dp))
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(108.dp))
                    }
                }
                // Themed icon: the monochrome layer tinted by the wallpaper palette.
                Box(Modifier.size(108.dp).clip(CircleShape).background(Color(0xFF2E2A3B))) {
                    Image(
                        painterResource(R.drawable.ic_launcher_monochrome),
                        null,
                        Modifier.size(108.dp),
                        colorFilter = ColorFilter.tint(Color(0xFFD7CCFF)),
                    )
                }
                Box(Modifier.size(48.dp).background(Color(0xFF1C1B1F), RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Image(painterResource(R.drawable.ic_notification), null, Modifier.size(24.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/launcher_icon.png")
    }
}
