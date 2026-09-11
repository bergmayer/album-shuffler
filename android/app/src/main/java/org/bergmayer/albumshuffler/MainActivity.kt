package org.bergmayer.albumshuffler

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val model by viewModels<ShufflerViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFAEC6FF))
                else lightColorScheme(primary = Color(0xFF2862D4))) {
                val state by model.state.collectAsStateWithLifecycle()
                val player by model.playerState.collectAsStateWithLifecycle()
                val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    model.authorizationResult(it.resultCode, it.data)
                }
                ShufflerScreen(state, player, model) { model.authorize(authorization::launch) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        if (intent?.data?.scheme == "albumshuffler" && intent.data?.host == "shuffle") model.deepLink()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShufflerScreen(state: ShufflerState, player: PlayerState, model: ShufflerViewModel,
    insets: WindowInsets = WindowInsets.safeDrawing, connect: () -> Unit) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    var signInHelp by rememberSaveable { mutableStateOf(false) }
    var appleMusicMissing by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(contentWindowInsets = insets, topBar = {
        TopAppBar(windowInsets = insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal), title = { Text("Album Shuffler", fontWeight = FontWeight.SemiBold) },
            actions = { TextButton(onClick = { settings = true }) { Text("Settings") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!state.connected) {
                Column(Modifier.weight(1f).widthIn(max = 540.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    MusicGlyph(Modifier.size(64.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("Apple Music Access", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Connect your Apple Music library to pick an album and play it from beginning to end.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = connect, enabled = !state.loading) { Text(if (state.loading) "Connecting…" else "Connect to Apple Music") }
                    Spacer(Modifier.height(12.dp))
                    Text("Requires Apple Music and an active subscription.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { signInHelp = true }) { Text("Sign-in stuck?") }
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val landscape = maxWidth >= 600.dp && maxWidth > maxHeight
                    val landscapeArtwork = (maxHeight - (200 + 100 * (LocalDensity.current.fontScale - 1)).dp).coerceIn(48.dp, 320.dp)
                    val compact = maxHeight < 480.dp
                    if (landscape) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            ShuffleControls(state, player, model, { sources = true }, Modifier.weight(1f).fillMaxHeight(), compact)
                            NowPlaying(player, model, modifier = Modifier.weight(1f).fillMaxHeight(), expanded = true, artworkSize = landscapeArtwork)
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            ShuffleControls(state, player, model, { sources = true }, Modifier.weight(1f).widthIn(max = 640.dp))
                            NowPlaying(player, model)
                        }
                    }
                }
            }
        }
    }
    val error = state.error ?: player.error
    if (error != null) AlertDialog(onDismissRequest = model::dismissError, title = { Text("Something went wrong") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } })
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text("Choose a source") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
            SourceRow("Entire Library", state.sourceId == null) { model.selectSource(null); sources = false }
            HorizontalDivider()
            state.playlists.forEach { playlist ->
                SourceRow(playlist.name, state.sourceId == playlist.id) { model.selectSource(playlist); sources = false }
            }
            if (state.playlists.isEmpty()) Text("Your library has no playlists.", Modifier.padding(vertical = 16.dp))
        }
    }, confirmButton = { TextButton(onClick = { sources = false }) { Text("Done") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.connected) "Connected to Apple Music" else "Apple Music is not connected")
            if (state.connected) {
                TextButton(onClick = { model.refreshLibrary(); settings = false }, enabled = !state.loading) { Text("Refresh library and playlists") }
                TextButton(onClick = { model.disconnect(); settings = false }) { Text("Disconnect Apple Music") }
            } else TextButton(onClick = { settings = false; connect() }) { Text("Connect to Apple Music") }
            TextButton(onClick = { settings = false; signInHelp = true }) { Text("Sign-in stuck?") }
            Text("Album Shuffler 1.0", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (signInHelp) AlertDialog(onDismissRequest = { signInHelp = false }, title = { Text("If Apple Music keeps loading") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Some versions of Apple Music can get stuck connecting apps when you are already signed in. These steps may help:")
            Text("1. Press Back to leave the loading screen.")
            Text("2. Open Apple Music and sign out of your account.")
            Text("3. Return to Album Shuffler and tap Connect to Apple Music. Sign in and approve access when prompted.")
            Text("Signing out may remove downloaded music from this device.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                val launch = context.packageManager.getLaunchIntentForPackage("com.apple.android.music")
                    ?: throw ActivityNotFoundException()
                context.startActivity(launch)
                signInHelp = false
            } catch (_: ActivityNotFoundException) { appleMusicMissing = true }
        }) { Text("Open Apple Music") }
    }, dismissButton = { TextButton(onClick = { signInHelp = false }) { Text("Close") } })
    if (appleMusicMissing) AlertDialog(onDismissRequest = { appleMusicMissing = false }, text = {
        Text("Install Apple Music from Google Play, then return here to connect.")
    }, confirmButton = { TextButton(onClick = { appleMusicMissing = false }) { Text("OK") } })
}

@Composable
private fun ShuffleControls(state: ShufflerState, player: PlayerState, model: ShufflerViewModel, onSources: () -> Unit, modifier: Modifier, compact: Boolean = false) {
    Column(modifier.testTag("shuffle-controls").fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("SOURCE")
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.loading, role = Role.Button) { onSources() }) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                MusicGlyph(Modifier.size(22.dp))
                                Text(state.sourceName, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("⌄", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (state.loading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Loading playlists…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                        SectionLabel("MODE")
                        PlaybackMode.entries.forEach { mode ->
                            Row(Modifier.fillMaxWidth().selectable(state.mode == mode, role = Role.RadioButton, onClick = { model.selectMode(mode) })
                                .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RadioButton(selected = state.mode == mode, onClick = null)
                                Column {
                                    Text(mode.title, style = MaterialTheme.typography.titleMedium)
                                    if (!compact) Text(mode.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (state.mode == PlaybackMode.SUBSET && state.sourceId == null) {
                            Text("Subset mode requires a playlist source.", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
        }
                    if (compact) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = model::shuffle, enabled = state.canShuffle, shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp), modifier = Modifier.weight(1f).heightIn(min = 54.dp)) {
                                Text(if (state.shuffling) "Working…" else "Play random album", style = MaterialTheme.typography.labelLarge)
                            }
                            FilledTonalButton(onClick = model::shuffle, enabled = state.canShuffle && player.track != null,
                                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("Choose another", style = MaterialTheme.typography.labelLarge) }
                        }
                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = model::shuffle, enabled = state.canShuffle, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                            if (state.shuffling) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else TransportGlyph("play", Modifier.size(18.dp), LocalContentColor.current)
                            Spacer(Modifier.width(10.dp))
                            Text(if (state.shuffling) "Working…" else "Play random album", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalButton(onClick = model::shuffle, enabled = state.canShuffle && player.track != null,
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                            Text("Choose another", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
}

@Composable
private fun SourceRow(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(name, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NowPlaying(player: PlayerState, model: ShufflerViewModel, modifier: Modifier = Modifier, expanded: Boolean = false, artworkSize: Dp = 260.dp) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf(false) }
    Surface(modifier = modifier.testTag("now-playing"), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, shape = if (expanded) RoundedCornerShape(24.dp) else RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight().verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (expanded) 20.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (expanded) Arrangement.Center else Arrangement.Top) {
            if (expanded) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val artSize = minOf(maxWidth * .75f, artworkSize)
                    Box(Modifier.align(Alignment.Center).size(artSize).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        MusicGlyph(Modifier.size(artSize * .4f))
                        if (player.track?.artwork != null) AsyncImage(model = player.track.artwork, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Row(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!expanded) Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    MusicGlyph(Modifier.size(28.dp))
                    if (player.track?.artwork != null) AsyncImage(model = player.track.artwork, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f)) {
                    Text(player.track?.title ?: "Nothing playing", style = if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(player.track?.let { listOf(it.artist, it.album).filter(String::isNotBlank).joinToString(" — ") } ?: "Pick a mode and play",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (player.buffering) Text("Loading audio…", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = {
                    try {
                        val launch = context.packageManager.getLaunchIntentForPackage("com.apple.android.music")
                        val link = player.track?.url?.toUri()?.takeIf { it.scheme == "https" && it.host == "music.apple.com" }
                        context.startActivity(launch ?: Intent(Intent.ACTION_VIEW, link ?: "https://music.apple.com/".toUri()))
                    } catch (_: ActivityNotFoundException) { openError = true }
                }, modifier = Modifier.semantics { contentDescription = "Open in Apple Music" }) { Text("Open") }
            }
            Spacer(Modifier.height(if (expanded) 20.dp else 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = { model.transport(PlaybackService.PREVIOUS) }, enabled = player.track != null && player.canPrevious,
                    modifier = Modifier.semantics { contentDescription = "Previous track" }) {
                    TransportGlyph("previous", Modifier.size(26.dp), LocalContentColor.current)
                }
                FilledIconButton(onClick = { model.transport(if (player.playing) PlaybackService.PAUSE else PlaybackService.PLAY) },
                    enabled = player.track != null && !player.buffering,
                    modifier = Modifier.size(54.dp).semantics { contentDescription = if (player.playing) "Pause" else "Play" }) {
                    TransportGlyph(if (player.playing) "pause" else "play", Modifier.size(28.dp), LocalContentColor.current)
                }
                IconButton(onClick = { model.transport(PlaybackService.NEXT) }, enabled = player.track != null && player.canNext,
                    modifier = Modifier.semantics { contentDescription = "Next track" }) {
                    TransportGlyph("next", Modifier.size(26.dp), LocalContentColor.current)
                }
            }
        }
    }
    if (openError) AlertDialog(onDismissRequest = { openError = false }, text = { Text("Install Apple Music or a browser to open this link.") },
        confirmButton = { TextButton(onClick = { openError = false }) { Text("OK") } })
}

@Composable
private fun MusicGlyph(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        drawLine(color, Offset(size.width * .55f, size.height * .15f), Offset(size.width * .55f, size.height * .73f), size.width * .10f)
        drawLine(color, Offset(size.width * .55f, size.height * .20f), Offset(size.width * .85f, size.height * .20f), size.width * .12f)
        drawCircle(color, size.width * .18f, Offset(size.width * .39f, size.height * .76f))
    }
}

@Composable
private fun TransportGlyph(kind: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        if (kind == "pause") {
            drawLine(color, Offset(w * .3f, h * .2f), Offset(w * .3f, h * .8f), w * .2f)
            drawLine(color, Offset(w * .7f, h * .2f), Offset(w * .7f, h * .8f), w * .2f)
        } else {
            val backward = kind == "previous"
            val path = Path().apply {
                moveTo(w * if (backward) .8f else .2f, h * .15f)
                lineTo(w * if (backward) .2f else .8f, h * .5f)
                lineTo(w * if (backward) .8f else .2f, h * .85f); close()
            }
            drawPath(path, color)
            if (kind != "play") {
                val x = w * if (backward) .1f else .9f
                drawLine(color, Offset(x, h * .15f), Offset(x, h * .85f), w * .1f)
            }
        }
    }
}
