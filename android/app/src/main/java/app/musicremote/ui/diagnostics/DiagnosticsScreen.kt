package app.musicremote.ui.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.musicremote.MediaBridge
import app.musicremote.SearchResult
import app.musicremote.BackgroundPlayer
import app.musicremote.YtMusicSearch
import app.musicremote.ui.components.SectionLabel
import app.musicremote.ui.icons.AuxIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The original Phase 1 test tools: on-phone search, starting a track (including
 * from the background) and the playFromSearch fallback. Kept for troubleshooting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    val log = remember { mutableStateListOf<String>() }

    fun note(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add(0, "$stamp  $line")
        if (log.size > 50) log.removeAt(log.lastIndex)
    }

    fun play(result: SearchResult, fromBackground: Boolean) {
        scope.launch {
            if (fromBackground) {
                note("Playing \"${result.title}\" in 8s. Press Home now.")
                delay(8_000)
            }
            // The same path a browser's pick takes: background first, launch last.
            val outcome = BackgroundPlayer.get(context).play(result.videoId, result.title, result.artist, log = ::note)
            when (outcome) {
                is BackgroundPlayer.Outcome.Played -> note("Result: played via ${outcome.method}")
                is BackgroundPlayer.Outcome.Failed -> note("Result: failed, ${outcome.reason}")
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { YtMusicSearch.search(q) } }
            searching = false
            outcome
                .onSuccess { results = it; note("Search \"$q\": ${it.size} results") }
                .onFailure { note("Search failed: $it") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AuxIcons.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Tap a result to play it the way a friend's pick does: in the background, without opening YouTube Music. Long-press to start it 8 seconds later, then press Home.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search YouTube Music") },
                    singleLine = true,
                    leadingIcon = { Icon(AuxIcons.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { search() }, shapes = ButtonDefaults.shapes()) { Text("Search") }
                    FilledTonalButton(
                        onClick = {
                            val q = query.trim()
                            if (q.isNotEmpty()) {
                                MediaBridge.get(context).playFromSearch(q)
                                note("Asked the current app to play \"$q\"")
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("Ask app to search") }
                    if (searching) LoadingIndicator(Modifier.size(36.dp))
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        BackgroundPlayer.get(context).forget()
                        note("Forgot the remembered playback method; the next pick re-tests all of them")
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Re-test playback methods") }
            }
            items(results, key = { it.videoId }) { result ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { play(result, false) }, onLongClick = { play(result, true) }),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(result.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(result.artist, result.duration).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (log.isNotEmpty()) {
                item { SectionLabel("Log", Modifier.padding(start = 0.dp)) }
                items(log) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
