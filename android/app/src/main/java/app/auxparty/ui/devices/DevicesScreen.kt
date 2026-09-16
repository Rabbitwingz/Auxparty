package app.auxparty.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.auxparty.ui.components.GroupedRow
import app.auxparty.ui.components.IconTile
import app.auxparty.ui.components.ShapeIllustration
import app.auxparty.ui.icons.AuxIcons
import app.auxparty.ui.state.LinkedBrowserUi
import app.auxparty.ui.state.formatRelative

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DevicesScreen(
    linked: List<LinkedBrowserUi>,
    onBack: () -> Unit,
    onRevoke: (String) -> Unit,
    onInvite: () -> Unit,
    inviteEnabled: Boolean = true,
    now: Long = System.currentTimeMillis(),
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = LocalHapticFeedback.current
    var pendingRevoke by remember { mutableStateOf<LinkedBrowserUi?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Linked browsers") },
                subtitle = { Text(if (linked.size == 1) "1 can control the music" else "${linked.size} can control the music") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AuxIcons.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (linked.isNotEmpty() && inviteEnabled) {
                ExtendedFloatingActionButton(
                    onClick = onInvite,
                    icon = { Icon(AuxIcons.AddLink, null) },
                    text = { Text("Invite") },
                )
            }
        },
    ) { padding ->
        if (linked.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ShapeIllustration(shape = MaterialShapes.Flower, icon = AuxIcons.Devices, size = 168.dp)
                Spacer(Modifier.height(28.dp))
                Text("No one's linked yet", style = MaterialTheme.typography.headlineSmallEmphasized)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Invite a friend and they can pick songs from their phone or laptop.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onInvite,
                    enabled = inviteEnabled,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(AuxIcons.AddLink, null, Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                    Text("Invite", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(linked, key = { _, b -> b.id }) { index, browser ->
                    GroupedRow(
                        index = index,
                        count = linked.size,
                        headline = browser.name,
                        supporting = browser.lastSeenAt?.let { "Last used ${formatRelative(it, now)}" }
                            ?: "Linked ${formatRelative(browser.createdAt, now)}, not used yet",
                        leading = { IconTile(deviceIcon(browser.name)) },
                        trailing = {
                            IconButton(onClick = { pendingRevoke = browser }) {
                                Icon(AuxIcons.Delete, "Remove ${browser.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    pendingRevoke?.let { browser ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            icon = { Icon(AuxIcons.LinkOff, null) },
            title = { Text("Remove ${browser.name}?") },
            text = { Text("It will stop controlling your music straight away. To link it again, send a new invite.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onRevoke(browser.id)
                    pendingRevoke = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text("Cancel") } },
        )
    }
}

/** Browsers name themselves like "Chrome on Android" (see the web UI). */
fun deviceIcon(name: String) =
    if (listOf("Android", "iOS", "iPhone", "iPad").any { name.contains(it, ignoreCase = true) }) AuxIcons.Smartphone else AuxIcons.Computer
