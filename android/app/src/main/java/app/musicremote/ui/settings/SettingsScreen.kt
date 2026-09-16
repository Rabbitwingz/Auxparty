package app.musicremote.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.musicremote.BuildConfig
import app.musicremote.ThemeMode
import app.musicremote.ui.SystemIntents
import app.musicremote.ui.components.GroupedColumn
import app.musicremote.ui.components.GroupedRow
import app.musicremote.ui.components.IconTile
import app.musicremote.ui.components.SectionLabel
import app.musicremote.ui.icons.AuxIcons
import app.musicremote.ui.state.Connection
import app.musicremote.ui.state.HostUiState

const val SOURCE_URL = "https://github.com/Rabbitwingz/YTM-Bridge"

class SettingsActions(
    val onBack: () -> Unit = {},
    val onDevices: () -> Unit = {},
    val onThemeMode: (ThemeMode) -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onRequestNotifications: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(state: HostUiState, actions: SettingsActions = SettingsActions()) {
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val setup = state.setup

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(AuxIcons.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ------------------------------------------------------ remote
            SectionLabel("Remote access")
            GroupedColumn {
                val (statusText, statusIcon) = when (state.connection) {
                    Connection.Online -> "Connected. Friends can control your music." to AuxIcons.CloudDone
                    Connection.Connecting -> "Connecting…" to AuxIcons.Sync
                    Connection.Offline -> (state.connectionError?.let { "Offline: $it" } ?: "Offline, retrying") to AuxIcons.CloudOff
                }
                GroupedRow(
                    index = 0, count = 2,
                    headline = "Connection",
                    supporting = statusText,
                    leading = {
                        if (state.connection == Connection.Offline) {
                            IconTile(statusIcon, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        } else {
                            IconTile(statusIcon)
                        }
                    },
                )
                GroupedRow(
                    index = 1, count = 2,
                    headline = "Linked browsers",
                    supporting = when (state.linked.size) {
                        0 -> "None yet"
                        1 -> "1 browser"
                        else -> "${state.linked.size} browsers"
                    },
                    leading = { IconTile(AuxIcons.Devices) },
                    trailing = { Icon(AuxIcons.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = actions.onDevices,
                )
            }

            // ------------------------------------------------------- setup
            SectionLabel("Permissions")
            GroupedColumn {
                val rows = buildList {
                    add(PermissionRow("Notification access", "Required to see and control music", setup.notificationAccess) {
                        SystemIntents.open(context, SystemIntents.notificationAccess(context), SystemIntents.appInfo(context))
                    })
                    add(PermissionRow("Display over other apps", "Starts songs while in the background", setup.overlay) {
                        SystemIntents.open(context, SystemIntents.overlay(context), SystemIntents.appInfo(context))
                    })
                    add(PermissionRow("Unrestricted battery", "Stays reachable when the phone sleeps", setup.battery) {
                        SystemIntents.open(context, SystemIntents.battery(context), SystemIntents.appInfo(context))
                    })
                    setup.notifications?.let { granted ->
                        add(PermissionRow("Notifications", "Shows when remote control is on", granted, actions.onRequestNotifications))
                    }
                }
                rows.forEachIndexed { i, row ->
                    GroupedRow(
                        index = i, count = rows.size,
                        headline = row.title,
                        supporting = if (row.granted) row.detail else "Not allowed. Tap to fix.",
                        leading = {
                            if (row.granted) {
                                IconTile(AuxIcons.CheckCircle)
                            } else {
                                IconTile(AuxIcons.LockOpen, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            }
                        },
                        onClick = if (row.granted) null else row.fix,
                    )
                }
            }

            // -------------------------------------------------- appearance
            SectionLabel("Appearance")
            GroupedRow(
                index = 0, count = 1,
                headline = "Theme",
                supporting = "Colours always follow the current album art",
                leading = { IconTile(AuxIcons.DarkMode) },
            )
            Spacer(Modifier.height(8.dp))
            ThemeToggle(state.themeMode, actions.onThemeMode)

            // ------------------------------------------------------- about
            SectionLabel("About")
            GroupedColumn {
                val host = Uri.parse(BuildConfig.WEB_URL).host ?: BuildConfig.WEB_URL
                GroupedRow(0, 4, "Auxparty ${BuildConfig.VERSION_NAME}", supporting = "Remote control for your music", leading = { IconTile(AuxIcons.Info) })
                GroupedRow(
                    1, 4, "Website", supporting = host,
                    leading = { IconTile(AuxIcons.OpenInNew) },
                    onClick = { SystemIntents.open(context, SystemIntents.web(BuildConfig.WEB_URL)) },
                )
                GroupedRow(
                    2, 4, "Source code", supporting = "github.com/Rabbitwingz/YTM-Bridge",
                    leading = { IconTile(AuxIcons.OpenInNew) },
                    onClick = { SystemIntents.open(context, SystemIntents.web(SOURCE_URL)) },
                )
                GroupedRow(
                    3, 4, "Diagnostics", supporting = "Test search and playback on this phone",
                    leading = { IconTile(AuxIcons.BugReport) },
                    trailing = { Icon(AuxIcons.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = actions.onDiagnostics,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private class PermissionRow(val title: String, val detail: String, val granted: Boolean, val fix: () -> Unit)

/** Connected toggle buttons, M3 Expressive's replacement for segmented buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeToggle(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.System to "System", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (mode, label) ->
            ToggleButton(
                checked = selected == mode,
                onCheckedChange = { onSelect(mode) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                if (selected == mode) {
                    Icon(AuxIcons.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                }
                Text(label)
            }
        }
    }
}
