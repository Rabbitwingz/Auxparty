package app.musicremote.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.musicremote.ui.state.NowPlayingUi
import kotlinx.coroutines.delay

/**
 * An Expressive shape holding an icon, turning slowly. Used as the illustration
 * for onboarding steps, empty states and confirmations.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeIllustration(
    shape: RoundedPolygon,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    spinMillis: Int = 30_000,
) {
    val turn = rememberInfiniteTransition(label = "illustration")
    val rotation by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(spinMillis, easing = LinearEasing)),
        label = "rotation",
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .background(container, shape.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.36f).graphicsLayer { rotationZ = -rotation },
        )
    }
}

// ------------------------------------------------------------ grouped rows

private val OuterCorner = 20.dp
private val InnerCorner = 4.dp

/** M3 Expressive segmented list: the group is rounded, rows inside are nearly square. */
fun segmentShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(OuterCorner)
    index == 0 -> RoundedCornerShape(OuterCorner, OuterCorner, InnerCorner, InnerCorner)
    index == count - 1 -> RoundedCornerShape(InnerCorner, InnerCorner, OuterCorner, OuterCorner)
    else -> RoundedCornerShape(InnerCorner)
}

@Composable
fun GroupedColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** One row in a grouped list. */
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = segmentShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke(this)
        }
    }
}

/** A tinted rounded tile for a leading row icon. */
@Composable
fun IconTile(
    icon: ImageVector,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier = Modifier.size(40.dp).background(container, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

// ------------------------------------------------------------ live clock

/**
 * The track position right now. MediaBridge samples position every couple of
 * seconds; between samples it advances locally so the progress bar moves smoothly.
 */
@Composable
fun rememberLivePosition(np: NowPlayingUi): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.playing, np.positionAtElapsed) {
        while (np.playing) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val position = if (np.playing) np.positionMs + (now - np.positionAtElapsed).coerceAtLeast(0) else np.positionMs
    return np.durationMs?.let { position.coerceAtMost(it) } ?: position
}
