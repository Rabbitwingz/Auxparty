package app.musicremote.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicremote.ui.components.MorphingArtwork
import app.musicremote.ui.components.ShapeIllustration
import app.musicremote.ui.components.rememberLivePosition
import app.musicremote.ui.icons.AuxIcons
import app.musicremote.ui.state.Connection
import app.musicremote.ui.state.HostUiState
import app.musicremote.ui.state.NowPlayingUi
import app.musicremote.ui.state.formatTime
import app.musicremote.ui.state.partySummary
import app.musicremote.ui.state.requestedByFor
import app.musicremote.ui.state.requesterNames

class HomeActions(
    val onPlayPause: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onSeek: (Float) -> Unit = {},
    val onVolume: (Int) -> Unit = {},
    val onInvite: () -> Unit = {},
    val onDevices: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onFinishSetup: () -> Unit = {},
    val onStartParty: () -> Unit = {},
    val onEndParty: () -> Unit = {},
    val onOpenParty: () -> Unit = {},
    val onShareParty: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(state: HostUiState, actions: HomeActions = HomeActions()) {
    var confirmEnd by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Auxparty", style = MaterialTheme.typography.titleLargeEmphasized) },
                actions = {
                    ConnectionPill(state.connection)
                    IconButton(onClick = actions.onSearch) {
                        Icon(AuxIcons.Search, contentDescription = if (state.party.active) "Add songs" else "Search")
                    }
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
            if (!state.setup.ready) {
                SetupBanner(actions.onFinishSetup)
                Spacer(Modifier.height(16.dp))
            }

            val np = state.nowPlaying
            if (np == null) {
                Spacer(Modifier.height(32.dp))
                ShapeIllustration(
                    shape = MaterialShapes.SoftBurst,
                    icon = AuxIcons.MusicNote,
                    size = 168.dp,
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    spinMillis = 40_000,
                )
                Spacer(Modifier.height(28.dp))
                NothingPlaying()
            } else {
                MorphingArtwork(
                    artwork = np.artwork,
                    playing = np.playing,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(28.dp))
                TrackDetails(np, actions.onSeek, requestedBy = requestedByFor(state.party, np.title))
                Spacer(Modifier.height(20.dp))
                Transport(np, actions)
                Spacer(Modifier.height(12.dp))
                VolumeRow(np, actions)
            }

            Spacer(Modifier.height(24.dp))
            ModeToggle(state, onRemote = { confirmEnd = true }, onParty = actions.onStartParty)
            Spacer(Modifier.height(16.dp))
            if (state.party.active) PartyCard(state, actions) else InviteCard(state, actions)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            icon = { Icon(AuxIcons.Celebration, null) },
            title = { Text("End the party?") },
            text = { Text("Guests are disconnected, the link stops working and the queue is cleared. Linked browsers go back to playing picks straight away.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    actions.onEndParty()
                }) { Text("End party") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep partying") } },
        )
    }
}

/**
 * Remote: a pick from a linked browser plays straight away. Party: friends request
 * songs through a link and they play in order.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeToggle(state: HostUiState, onRemote: () -> Unit, onParty: () -> Unit) {
    val party = state.party
    val partySelected = party.active || party.starting
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            ToggleButton(
                checked = !partySelected,
                onCheckedChange = { if (party.active) onRemote() },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(AuxIcons.Devices, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Remote", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
            ToggleButton(
                checked = partySelected,
                onCheckedChange = { if (!partySelected) onParty() },
                enabled = state.connection == Connection.Online || party.active,
                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                modifier = Modifier.weight(1f).height(ButtonDefaults.MediumContainerHeight),
            ) {
                if (party.starting) {
                    LoadingIndicator(Modifier.size(24.dp))
                } else {
                    Icon(AuxIcons.Celebration, null, Modifier.size(20.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text("Party", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
        Spacer(Modifier.height(8.dp))
        val error = state.partyError
        Text(
            when {
                error == "offline" -> "Party mode needs a connection. Auxparty keeps retrying in the background."
                error == "timeout" -> "Couldn't start the party. Check the connection and try again."
                partySelected -> "Friends request songs with a link. They play in order."
                else -> "Songs picked in linked browsers play straight away."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartyCard(state: HostUiState, actions: HomeActions) {
    val party = state.party
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Up next", style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.height(4.dp))
            Text(partySummary(party), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            if (party.upcoming.isEmpty()) {
                Text(
                    "No requests yet. Add songs with search, or share the link so friends can.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                party.upcoming.take(3).forEachIndexed { i, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(28.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Requested by ${requesterNames(item)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (party.upcoming.size > 3) {
                    Text(
                        "and ${party.upcoming.size - 3} more",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { party.link?.let(actions.onShareParty) },
                    enabled = party.link != null,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight, hasStartIcon = true),
                    modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(AuxIcons.Share, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                    Text("Share", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                OutlinedButton(
                    onClick = actions.onOpenParty,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text("Manage", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
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
private fun SetupBanner(onFinish: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AuxIcons.LockOpen, null, Modifier.size(20.dp))
            Text(
                "Auxparty needs notification access to see and control music.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            TextButton(onClick = onFinish) { Text("Fix") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrackDetails(np: NowPlayingUi, onSeek: (Float) -> Unit, requestedBy: String? = null) {
    val haptics = LocalHapticFeedback.current
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
        requestedBy?.let {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = CircleShape,
            ) {
                Row(
                    Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AuxIcons.Person, null, Modifier.size(18.dp))
                    Text("Requested by $it", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        val position = rememberLivePosition(np)
        // A seek shows immediately instead of waiting for the next sample.
        var pendingSeek by remember(np.positionAtElapsed) { mutableStateOf<Float?>(null) }
        val duration = np.durationMs?.takeIf { it > 0 }
        val fraction = pendingSeek ?: duration?.let { (position.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics { contentDescription = "Seek. ${formatTime(position)} of ${duration?.let(::formatTime) ?: "unknown"}" }
                .then(
                    if (duration != null) {
                        Modifier.pointerInput(duration) {
                            detectTapGestures { offset ->
                                val f = (offset.x / size.width).coerceIn(0f, 1f)
                                pendingSeek = f
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onSeek(f)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                // The wave is the "it's playing" signal; it flattens to a line when paused.
                amplitude = { if (np.playing) WavyProgressIndicatorDefaults.indicatorAmplitude(it) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownPosition = pendingSeek?.let { f -> duration?.let { (it * f).toLong() } } ?: position
            Text(formatTime(shownPosition), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(duration?.let(::formatTime) ?: "--:--", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Transport(np: NowPlayingUi, actions: HomeActions) {
    val haptics = LocalHapticFeedback.current
    val tap = { action: () -> Unit ->
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        action()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { tap(actions.onPrevious) },
            shapes = IconButtonDefaults.shapes(),
            // Narrow keeps all three controls inside a 360dp-wide phone.
            modifier = Modifier.size(IconButtonDefaults.largeContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)),
        ) {
            Icon(AuxIcons.SkipPrevious, "Previous", Modifier.size(IconButtonDefaults.largeIconSize))
        }
        // Round when paused, squarer while playing, and squishes on press: the
        // Expressive way of showing state through shape.
        FilledIconButton(
            onClick = { tap(actions.onPlayPause) },
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
            onClick = { tap(actions.onNext) },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.largeContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)),
        ) {
            Icon(AuxIcons.SkipNext, "Next", Modifier.size(IconButtonDefaults.largeIconSize))
        }
    }
}

@Composable
private fun VolumeRow(np: NowPlayingUi, actions: HomeActions) {
    // Follow the phone's volume, but don't fight the finger while dragging.
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(np.volume.toFloat()) }
    val shown = if (dragging) local else np.volume.toFloat()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(AuxIcons.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                local = it
                actions.onVolume(it.toInt())
            },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..np.maxVolume.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Volume" },
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
            "Search for a song, or invite a friend to pick one.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InviteCard(state: HostUiState, actions: HomeActions) {
    val linked = state.linked.size
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Let friends pick the music", style = MaterialTheme.typography.titleLargeEmphasized)
            Spacer(Modifier.height(4.dp))
            Text(
                when (linked) {
                    0 -> "Share a link. They choose songs from any browser; it plays here."
                    1 -> "1 browser can control the music"
                    else -> "$linked browsers can control the music"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = actions.onInvite,
                    enabled = state.connection == Connection.Online,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight, hasStartIcon = true),
                    modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(AuxIcons.Share, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                    Text("Invite", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                if (linked > 0) {
                    OutlinedButton(
                        onClick = actions.onDevices,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                    ) {
                        Text("Manage", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                    }
                }
            }
            if (state.connection == Connection.Offline) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Invites need a connection. Auxparty keeps retrying in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
