package app.musicremote.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicremote.ui.components.MorphingArtwork
import app.musicremote.ui.icons.AuxIcons

// ------------------------------------------------------------------ state

enum class Connection { Connecting, Online, Offline }

data class NowPlayingUi(
    val title: String,
    val artist: String?,
    val source: String,
    val artwork: ImageBitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val volume: Int,
    val maxVolume: Int,
)

data class HomeUiState(
    val connection: Connection,
    val nowPlaying: NowPlayingUi?,
    val linkedBrowsers: Int,
)

class HomeActions(
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onVolume: (Int) -> Unit = {},
    val onInvite: () -> Unit = {},
    val onDevices: () -> Unit = {},
    val onSettings: () -> Unit = {},
)

// ----------------------------------------------------------------- screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions = HomeActions()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Auxparty", style = MaterialTheme.typography.titleLargeEmphasized) },
                actions = {
                    ConnectionPill(state.connection)
                    IconButton(onClick = actions.onSettings) {
                        Icon(AuxIcons.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val np = state.nowPlaying
            MorphingArtwork(
                artwork = np?.artwork,
                playing = np?.playing == true,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(Modifier.height(28.dp))

            if (np == null) {
                NothingPlaying()
            } else {
                TrackDetails(np)
                Spacer(Modifier.height(20.dp))
                Transport(np, actions)
                Spacer(Modifier.height(12.dp))
                VolumeRow(np, actions)
            }

            Spacer(Modifier.height(24.dp))
            InviteCard(state.linkedBrowsers, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionPill(connection: Connection) {
    val (label, container, content) = when (connection) {
        Connection.Online -> Triple("Online", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        Connection.Connecting -> Triple("Connecting", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        Connection.Offline -> Triple("Offline", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (connection) {
                Connection.Connecting -> LoadingIndicator(Modifier.size(20.dp), color = content)
                Connection.Online -> Icon(AuxIcons.CloudDone, null, Modifier.size(18.dp))
                Connection.Offline -> Icon(AuxIcons.CloudOff, null, Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackDetails(np: NowPlayingUi) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            np.source.uppercase(),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            np.title,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        np.artist?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(20.dp))

        val fraction = np.durationMs?.takeIf { it > 0 }?.let { (np.positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
        LinearWavyProgressIndicator(
            progress = { fraction },
            // The wave is the "it's playing" signal; it flattens to a line when paused.
            amplitude = { if (np.playing) WavyProgressIndicatorDefaults.indicatorAmplitude(it) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(np.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(np.durationMs?.let(::formatTime) ?: "--:--", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Transport(np: NowPlayingUi, actions: HomeActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = actions.onPrevious,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
        ) {
            Icon(AuxIcons.SkipPrevious, "Previous", Modifier.size(IconButtonDefaults.largeIconSize))
        }
        // Round when paused, squarer while playing, and squishes on press: the
        // Expressive way of showing state through shape.
        FilledIconButton(
            onClick = actions.onPlayPause,
            shapes = IconButtonDefaults.shapes(
                shape = if (np.playing) IconButtonDefaults.extraLargeSquareShape else IconButtonDefaults.extraLargeRoundShape,
                pressedShape = IconButtonDefaults.extraLargePressedShape,
            ),
            modifier = Modifier.size(IconButtonDefaults.extraLargeContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)),
        ) {
            Icon(
                if (np.playing) AuxIcons.Pause else AuxIcons.PlayArrow,
                if (np.playing) "Pause" else "Play",
                Modifier.size(IconButtonDefaults.extraLargeIconSize),
            )
        }
        FilledTonalIconButton(
            onClick = actions.onNext,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
        ) {
            Icon(AuxIcons.SkipNext, "Next", Modifier.size(IconButtonDefaults.largeIconSize))
        }
    }
}

@Composable
private fun VolumeRow(np: NowPlayingUi, actions: HomeActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(AuxIcons.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = np.volume.toFloat(),
            onValueChange = { actions.onVolume(it.toInt()) },
            valueRange = 0f..np.maxVolume.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        Icon(AuxIcons.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NothingPlaying() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nothing playing", style = MaterialTheme.typography.headlineMediumEmphasized, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "Start a song in YouTube Music, or invite a friend to pick one.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InviteCard(linkedBrowsers: Int, actions: HomeActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Let friends pick the music", style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.height(4.dp))
            Text(
                when (linkedBrowsers) {
                    0 -> "Share a link. They control it from any browser; it plays here."
                    1 -> "1 browser linked"
                    else -> "$linkedBrowsers browsers linked"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onInvite,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight, hasStartIcon = true),
                    modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(AuxIcons.Share, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                    Text("Invite", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                if (linkedBrowsers > 0) {
                    Box(Modifier.height(ButtonDefaults.MediumContainerHeight), contentAlignment = Alignment.Center) {
                        IconButton(onClick = actions.onDevices) { Icon(AuxIcons.Devices, "Linked browsers") }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
