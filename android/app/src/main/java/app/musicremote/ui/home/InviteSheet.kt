package app.musicremote.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.musicremote.BuildConfig
import app.musicremote.ui.SystemIntents
import app.musicremote.ui.components.ShapeIllustration
import app.musicremote.ui.icons.AuxIcons
import app.musicremote.ui.state.Connection
import app.musicremote.ui.state.HostUiState
import app.musicremote.ui.state.PairCodeUi
import app.musicremote.ui.state.displayCode
import app.musicremote.ui.state.formatTime
import kotlinx.coroutines.delay

/** The link a friend opens: the site pre-fills the code from `?code=`. */
fun inviteLink(code: String): String =
    Uri.parse(BuildConfig.WEB_URL).buildUpon().appendQueryParameter("code", displayCode(code)).build().toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(
    state: HostUiState,
    onDismiss: () -> Unit,
    onNewCode: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        InviteContent(state, onDismiss, onNewCode)
    }
}

private enum class InvitePhase { Waiting, Offline, Code, Joined }

/** Sheet body, separate from the sheet so it can be screenshot-tested directly. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InviteContent(
    state: HostUiState,
    onDone: () -> Unit,
    onNewCode: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    val phase = when {
        state.justLinked != null -> InvitePhase.Joined
        state.connection != Connection.Online || state.inviteError != null -> InvitePhase.Offline
        state.pairCode == null -> InvitePhase.Waiting
        else -> InvitePhase.Code
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .heightIn(min = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Transition lambdas aren't composable, so read the theme's springs first.
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(effects) + scaleIn(spatial, initialScale = 0.92f)).togetherWith(fadeOut(exit))
            },
            label = "invite phase",
        ) { current ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                when (current) {
                    InvitePhase.Waiting -> Centered {
                        LoadingIndicator(Modifier.size(72.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Getting a code…", style = MaterialTheme.typography.titleMedium)
                    }
                    InvitePhase.Offline -> Centered {
                        ShapeIllustration(
                            shape = MaterialShapes.Puffy,
                            icon = AuxIcons.CloudOff,
                            size = 120.dp,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text("You're offline", style = MaterialTheme.typography.headlineSmallEmphasized)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Invites need a connection to Auxparty. It's retrying in the background; try again in a moment.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        FilledTonalButton(onClick = onNewCode, shapes = ButtonDefaults.shapes()) {
                            Icon(AuxIcons.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Try again")
                        }
                    }
                    InvitePhase.Code -> CodePanel(state.pairCode!!, onNewCode, now)
                    InvitePhase.Joined -> Centered {
                        ShapeIllustration(
                            shape = MaterialShapes.Cookie12Sided,
                            icon = AuxIcons.CheckCircle,
                            size = 140.dp,
                            spinMillis = 12_000,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "${state.justLinked} joined",
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "They can pick songs from their browser now. Manage access anytime in Settings.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
                            Text("Done", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CodePanel(code: PairCodeUi, onNewCode: () -> Unit, now: () -> Long) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val host = Uri.parse(BuildConfig.WEB_URL).host ?: BuildConfig.WEB_URL

    var current by remember { mutableLongStateOf(now()) }
    LaunchedEffect(code.expiresAt) {
        while (current < code.expiresAt) {
            delay(1_000)
            current = now()
        }
    }
    val lifetime = (code.expiresAt - code.issuedAt).coerceAtLeast(1)
    val remaining = (code.expiresAt - current).coerceAtLeast(0)
    val expired = remaining == 0L

    Text("Invite a friend", style = MaterialTheme.typography.headlineSmallEmphasized)
    Spacer(Modifier.height(8.dp))
    Text(
        "They open $host on any phone or computer and enter this code. Or just send them the link.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))

    CodeTiles(displayCode(code.code), dimmed = expired)
    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularWavyProgressIndicator(
            progress = { remaining.toFloat() / lifetime },
            modifier = Modifier.size(28.dp),
            amplitude = { 0.6f },
        )
        Text(
            if (expired) "This code has expired" else "Expires in ${formatTime(remaining)}",
            style = MaterialTheme.typography.labelLarge,
            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(24.dp))

    if (expired) {
        Button(onClick = onNewCode, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
            Icon(AuxIcons.Refresh, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
            Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
            Text("Get a new code", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    SystemIntents.open(context, SystemIntents.share("Pick the music with me on Auxparty: ${inviteLink(code.code)}"))
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(AuxIcons.Share, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                Text("Share link", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
            FilledTonalButton(
                onClick = {
                    copyToClipboard(context, displayCode(code.code))
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(AuxIcons.ContentCopy, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                Text("Copy", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNewCode) { Text("New code") }
    }
}

/** The code as big individual tiles, easy to read across a room. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CodeTiles(code: String, dimmed: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "Code ${code.toList().joinToString(" ")}" },
    ) {
        code.forEach { ch ->
            if (ch == '-') {
                Spacer(Modifier.width(8.dp))
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(width = 36.dp, height = 52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            ch.toString(),
                            style = MaterialTheme.typography.headlineMediumEmphasized,
                            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Auxparty code", text))
}
