package app.musicremote.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.musicremote.R
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.ktx.themeColors

/** Used when nothing is playing, or the track has no artwork. */
val BrandSeed = Color(0xFF7B5CFF)

/**
 * Auxparty's Material 3 Expressive theme. The whole scheme is generated from the
 * current track's artwork ("album art everywhere"), using the Content palette so
 * the UI stays recognisably close to the cover rather than drifting in hue.
 * Colour changes animate, so a track change re-tints the UI instead of snapping.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuxpartyTheme(
    seed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    DynamicMaterialExpressiveTheme(
        seedColor = seed ?: BrandSeed,
        motionScheme = MotionScheme.expressive(),
        isDark = darkTheme,
        style = PaletteStyle.Content,
        typography = AuxpartyTypography,
        animate = true,
        content = content,
    )
}

/**
 * Seed colour for a piece of artwork: Material Color Utilities' Celebi quantizer
 * plus Score, the same algorithm the web UI runs, so a track tints both alike.
 * Downscaled first; the result doesn't change and it's far cheaper.
 * Call off the main thread.
 */
fun seedFromArtwork(bitmap: Bitmap): Color {
    val small = Bitmap.createScaledBitmap(bitmap, SEED_SAMPLE_PX, SEED_SAMPLE_PX, true)
    return small.asImageBitmap().themeColors(fallback = BrandSeed).first()
}

private const val SEED_SAMPLE_PX = 112

// ---------------------------------------------------------------- type

@OptIn(ExperimentalTextApi::class)
private fun robotoFlex(weight: Int, width: Float = 100f) = Font(
    resId = R.font.roboto_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

/** Roboto Flex, the variable face behind M3 Expressive's emphasized styles. */
val RobotoFlex = FontFamily(
    robotoFlex(400),
    robotoFlex(500),
    robotoFlex(600),
    robotoFlex(700),
    robotoFlex(800),
)

val AuxpartyTypography = Typography(fontFamily = RobotoFlex)
