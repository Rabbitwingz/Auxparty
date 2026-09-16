package app.auxparty.ui.search

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.auxparty.SearchResult
import app.auxparty.ui.components.GroupedRow
import app.auxparty.ui.components.IconTile
import app.auxparty.ui.components.ShapeIllustration
import app.auxparty.ui.icons.AuxIcons
import app.auxparty.ui.state.SearchUi
import app.auxparty.ui.state.resultSupporting

class SearchActions(
    val onBack: () -> Unit = {},
    val onQuery: (String, Boolean) -> Unit = { _, _ -> },
    val onPlayNow: (SearchResult) -> Unit = {},
    val onAddToQueue: (SearchResult) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * Search YouTube Music from the phone. In Remote mode a tap plays the song now; during
 * a party a tap adds it to the queue, and the play button still plays it straight away.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    search: SearchUi,
    partyActive: Boolean,
    actions: SearchActions = SearchActions(),
    autoFocus: Boolean = true,
) {
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(search.message) {
        val message = search.message ?: return@LaunchedEffect
        actions.onMessageShown()
        snackbar.showSnackbar(message)
    }
    LaunchedEffect(Unit) {
        if (autoFocus && search.query.isEmpty()) focus.requestFocus()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(AuxIcons.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = search.query,
                        onValueChange = { actions.onQuery(it, false) },
                        placeholder = { Text(if (partyActive) "Search for a song to add" else "Search YouTube Music") },
                        singleLine = true,
                        leadingIcon = { Icon(AuxIcons.Search, null) },
                        trailingIcon = {
                            if (search.query.isNotEmpty()) {
                                IconButton(onClick = { actions.onQuery("", true) }) { Icon(AuxIcons.Close, "Clear search") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            actions.onQuery(search.query, true)
                        }),
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when {
            search.searching && search.results.isEmpty() -> Centered(padding) {
                LoadingIndicator(Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Searching…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            search.error != null -> Centered(padding) { Hint(search.error) }
            search.searched && search.results.isEmpty() -> Centered(padding) { Hint("Nothing found for “${search.query.trim()}”.") }
            search.results.isEmpty() -> Centered(padding) {
                ShapeIllustration(
                    shape = MaterialShapes.Cookie9Sided,
                    icon = if (partyActive) AuxIcons.QueueMusic else AuxIcons.Search,
                    size = 144.dp,
                )
                Spacer(Modifier.height(24.dp))
                Hint(
                    if (partyActive) "Songs you add join the party queue, in line with everyone else’s."
                    else "Pick a song and it plays straight away.",
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(search.results, key = { _, r -> r.videoId }) { index, result ->
                    val busy = search.busyVideoId == result.videoId
                    GroupedRow(
                        index = index,
                        count = search.results.size,
                        headline = result.title,
                        supporting = resultSupporting(result),
                        // The tile says what a tap does: add to the queue, or play.
                        leading = { IconTile(if (partyActive) AuxIcons.PlaylistAdd else AuxIcons.MusicNote) },
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            if (partyActive) actions.onAddToQueue(result) else actions.onPlayNow(result)
                        },
                        trailing = {
                            when {
                                busy -> LoadingIndicator(Modifier.size(32.dp))
                                partyActive -> IconButton(onClick = { actions.onPlayNow(result) }) {
                                    Icon(AuxIcons.PlayArrow, "Play ${result.title} now", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                else -> Icon(AuxIcons.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
