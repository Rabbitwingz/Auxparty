package app.musicremote.ui.party

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicremote.party.QueueItem
import app.musicremote.ui.components.GroupedRow
import app.musicremote.ui.components.IconTile
import app.musicremote.ui.components.QrCode
import app.musicremote.ui.components.SectionLabel
import app.musicremote.ui.icons.AuxIcons
import app.musicremote.ui.state.GuestUi
import app.musicremote.ui.state.PartyUi
import app.musicremote.ui.state.formatRelative
import app.musicremote.ui.state.partySummary
import app.musicremote.ui.state.requesterNames

class PartyActions(
    val onBack: () -> Unit = {},
    val onShare: (String) -> Unit = {},
    val onNewLink: () -> Unit = {},
    val onEndParty: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onPlayNext: (String) -> Unit = {},
    val onClear: () -> Unit = {},
    val onRemoveGuest: (String) -> Unit = {},
    val onGuestLimit: (Int) -> Unit = {},
)

private sealed interface Confirm {
    data object End : Confirm
    data object Clear : Confirm
    data object NewLink : Confirm
    data class RemoveGuest(val guest: GuestUi) : Confirm
}

/** The host's party control room: share the link, manage the queue and the guests. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PartyScreen(
    party: PartyUi,
    actions: PartyActions = PartyActions(),
    now: Long = System.currentTimeMillis(),
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = LocalHapticFeedback.current
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Party") },
                subtitle = { Text(if (party.active) partySummary(party) else "Not running") },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(AuxIcons.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "share") {
                ShareCard(party, actions.onShare, onNewLink = { confirm = Confirm.NewLink })
            }

            party.current?.let { current ->
                item(key = "now-label") { SectionLabel("Playing now") }
                item(key = "now") {
                    GroupedRow(
                        index = 0,
                        count = 1,
                        headline = current.title,
                        supporting = songSupporting(current),
                        leading = { IconTile(AuxIcons.MusicNote, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) },
                    )
                }
            }

            item(key = "queue-label") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Up next", Modifier.weight(1f))
                    if (party.upcoming.isNotEmpty()) {
                        TextButton(onClick = { confirm = Confirm.Clear }, modifier = Modifier.padding(top = 12.dp)) {
                            Icon(AuxIcons.DeleteSweep, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Clear")
                        }
                    }
                }
            }
            if (party.upcoming.isEmpty()) {
                item(key = "queue-empty") {
                    GroupedRow(
                        index = 0,
                        count = 1,
                        headline = "Nothing queued",
                        supporting = "Requests from the party link show up here. They play in order after the current song.",
                        leading = { IconTile(AuxIcons.QueueMusic) },
                    )
                }
            }
            itemsIndexed(party.upcoming, key = { _, item -> item.itemId }) { index, item ->
                GroupedRow(
                    index = index,
                    count = party.upcoming.size,
                    headline = item.title,
                    supporting = songSupporting(item),
                    leading = { PositionTile(index + 1) },
                    trailing = {
                        if (index > 0) {
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                actions.onPlayNext(item.itemId)
                            }) {
                                Icon(AuxIcons.VerticalAlignTop, "Play ${item.title} next", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            actions.onRemove(item.itemId)
                        }) {
                            Icon(AuxIcons.Close, "Remove ${item.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "limit-label") { SectionLabel("Requests") }
            item(key = "limit") {
                GroupedRow(
                    index = 0,
                    count = 1,
                    headline = "Songs each guest can have waiting",
                    supporting = "Keeps one person from filling the queue",
                    trailing = {
                        IconButton(onClick = { actions.onGuestLimit(party.limitPerGuest - 1) }, enabled = party.limitPerGuest > 1) {
                            Icon(AuxIcons.Remove, "Fewer")
                        }
                        Text("${party.limitPerGuest}", style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { actions.onGuestLimit(party.limitPerGuest + 1) }, enabled = party.limitPerGuest < 20) {
                            Icon(AuxIcons.Add, "More")
                        }
                    },
                )
            }

            item(key = "guests-label") { SectionLabel(if (party.guests.isEmpty()) "Guests" else "Guests (${party.guests.size})") }
            if (party.guests.isEmpty()) {
                item(key = "guests-empty") {
                    GroupedRow(
                        index = 0,
                        count = 1,
                        headline = "No one has joined yet",
                        supporting = "Share the link or show the QR code.",
                        leading = { IconTile(AuxIcons.Group) },
                    )
                }
            }
            itemsIndexed(party.guests, key = { _, g -> g.id }) { index, guest ->
                GroupedRow(
                    index = index,
                    count = party.guests.size,
                    headline = guest.name,
                    supporting = "Joined ${formatRelative(guest.joinedAt, now)}",
                    leading = { IconTile(AuxIcons.Person) },
                    trailing = {
                        IconButton(onClick = { confirm = Confirm.RemoveGuest(guest) }) {
                            Icon(AuxIcons.PersonRemove, "Remove ${guest.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "end") {
                OutlinedButton(
                    onClick = { confirm = Confirm.End },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 24.dp).fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text("End party", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
        }
    }

    confirm?.let { c ->
        val (title, body, button) = when (c) {
            Confirm.End -> Triple("End the party?", "Guests are disconnected, the link stops working and the queue is cleared. The song playing now keeps playing.", "End party")
            Confirm.Clear -> Triple("Clear the queue?", "Every waiting request is removed. The song playing now keeps playing.", "Clear")
            Confirm.NewLink -> Triple("Make a new link?", "The current link stops working. Guests who already joined stay in the party.", "New link")
            is Confirm.RemoveGuest -> Triple("Remove ${c.guest.name}?", "They're disconnected straight away. Songs they requested stay in the queue.", "Remove")
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    when (c) {
                        Confirm.End -> actions.onEndParty()
                        Confirm.Clear -> actions.onClear()
                        Confirm.NewLink -> actions.onNewLink()
                        is Confirm.RemoveGuest -> actions.onRemoveGuest(c.guest.id)
                    }
                    confirm = null
                }) { Text(button) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun songSupporting(item: QueueItem): String =
    listOfNotNull(item.artist, "Requested by ${requesterNames(item)}").joinToString(" · ")

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ShareCard(party: PartyUi, onShare: (String) -> Unit, onNewLink: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val link = party.link
            // Scanners need dark-on-light with a quiet zone, whatever the theme.
            Box(
                modifier = Modifier
                    .size(212.dp)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (link != null) {
                    QrCode(link, Modifier.fillMaxSize(), description = "QR code for the party link")
                } else {
                    Icon(AuxIcons.QrCode2, null, Modifier.size(64.dp), tint = Color.Black.copy(alpha = 0.3f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Scan to join", style = MaterialTheme.typography.titleLargeEmphasized)
            Text(
                if (link != null) "Or share the link. Anyone with it can add songs." else "Waiting for the link…",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            // Wraps to two rows at large font sizes instead of squeezing the labels.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { link?.let(onShare) },
                    enabled = link != null,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(AuxIcons.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share link")
                }
                FilledTonalButton(onClick = onNewLink, enabled = link != null, shapes = ButtonDefaults.shapes()) {
                    Icon(AuxIcons.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("New link")
                }
            }
        }
    }
}

@Composable
private fun PositionTile(position: Int) {
    Box(
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$position", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
