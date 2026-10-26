@file:OptIn(ExperimentalMaterial3Api::class)

package com.voxlab.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { EditorScreen() }
            }
        }
    }
}

private val TOOLS = listOf(
    "Trim", "Split", "Duplicate", "Replace", "Delete", "Speed", "Adjust",
    "Filters", "Crop", "Rotate", "Text", "Stickers", "Audio"
)

private fun durationOf(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        r.release()
    }
}

@Composable
fun EditorScreen() {
    val ctx = LocalContext.current
    val clips = remember { mutableStateListOf<Clip>() }
    var sel by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf("Trim") }
    var status by remember { mutableStateOf("") }
    var exporter by remember { mutableStateOf<Transformer?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val exo = remember { ExoPlayer.Builder(ctx).build() }

    DisposableEffect(Unit) {
        onDispose {
            exo.release()
            exporter?.cancel()
        }
    }

    fun upd(f: (Clip) -> Clip) {
        if (sel in clips.indices) clips[sel] = f(clips[sel])
    }

    val addPicker = rememberLauncherForActivityResult(PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { u -> clips.add(Clip(u, durationOf(ctx, u))) }
        if (clips.isNotEmpty() && sel !in clips.indices) sel = 0
    }
    val replacePicker = rememberLauncherForActivityResult(PickVisualMedia()) { u ->
        if (u != null) upd { Clip(u, durationOf(ctx, u)) }
    }
    val videoOnly = PickVisualMediaRequest(PickVisualMedia.VideoOnly)

    val cur = clips.getOrNull(sel)

    LaunchedEffect(cur?.uri, cur?.startMs, cur?.endMs, cur?.speed) {
        if (cur != null) {
            val cfg = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(cur.startMs)
                .setEndPositionMs(cur.endMs)
                .build()
            exo.setMediaItem(MediaItem.Builder().setUri(cur.uri).setClippingConfiguration(cfg).build())
            exo.setPlaybackSpeed(cur.speed)
            exo.prepare()
        } else {
            exo.clearMediaItems()
        }
    }

    LaunchedEffect(exporter) {
        val ex = exporter ?: return@LaunchedEffect
        val h = ProgressHolder()
        while (exporter === ex) {
            ex.getProgress(h)
            progress = h.progress / 100f
            delay(300)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
        AndroidView(
            factory = { context -> PlayerView(context).also { it.player = exo } },
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)
        )
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { addPicker.launch(videoOnly) }) { Text("+ Add") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = clips.isNotEmpty() && exporter == null,
                onClick = {
                    status = "Exporting…"
                    progress = 0f
                    exporter = startExport(
                        ctx, clips.toList(),
                        { path -> status = "Saved: $path"; exporter = null },
                        { err -> status = "Error: $err"; exporter = null }
                    )
                }
            ) { Text("Export") }
            Spacer(Modifier.width(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (exporter != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }

        // Timeline
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(clips) { i, c ->
                Box(
                    Modifier
                        .size(width = 90.dp, height = 50.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2A3A))
                        .border(2.dp, if (i == sel) Color(0xFF00E5FF) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { sel = i },
                    contentAlignment = Alignment.Center
                ) {
                    Text("${i + 1} · ${(c.endMs - c.startMs) / 1000f}s", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Tool bar
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TOOLS) { t ->
                FilterChip(
                    selected = tool == t,
                    onClick = {
                        tool = t
                        val c = cur
                        if (c != null) {
                            when (t) {
                                "Split" -> {
                                    val at = c.startMs + exo.currentPosition
                                    if (at > c.startMs + 200 && at < c.endMs - 200) {
                                        clips[sel] = c.copy(endMs = at)
                                        clips.add(sel + 1, c.copy(startMs = at))
                                    } else {
                                        status = "Move playhead inside the clip to split"
                                    }
                                }
                                "Duplicate" -> clips.add(sel + 1, c)
                                "Replace" -> replacePicker.launch(videoOnly)
                                "Delete" -> {
                                    clips.removeAt(sel)
                                    sel = (sel - 1).coerceAtLeast(0)
                                }
                                else -> {}
                            }
                        }
                    },
                    label = { Text(t) }
                )
            }
        }

        // Tool options
        Box(Modifier.fillMaxWidth().heightIn(min = 90.dp).padding(top = 6.dp)) {
            if (cur == null) {
                Text("Add a video to start editing")
            } else {
                when (tool) {
                    "Trim" -> Column {
                        Text("Trim ${cur.startMs / 1000f}s – ${cur.endMs / 1000f}s")
                        RangeSlider(
                            value = cur.startMs.toFloat()..cur.endMs.toFloat(),
                            valueRange = 0f..cur.durationMs.toFloat().coerceAtLeast(1f),
                            onValueChange = { r ->
                                if (r.endInclusive - r.start > 300f) {
                                    upd { it.copy(startMs = r.start.toLong(), endMs = r.endInclusive.toLong()) }
                                }
                            }
                        )
                    }
                    "Speed" -> Column {
                        Text("Speed ${"%.2f".format(cur.speed)}x")
                        Slider(value = cur.speed, onValueChange = { v -> upd { it.copy(speed = v) } }, valueRange = 0.25f..4f)
                    }
                    "Adjust" -> Column {
                        Text("Brightness")
                        Slider(value = cur.brightness, onValueChange = { v -> upd { it.copy(brightness = v) } }, valueRange = -1f..1f)
                        Text("Contrast")
                        Slider(value = cur.contrast, onValueChange = { v -> upd { it.copy(contrast = v) } }, valueRange = -1f..1f)
                        Text("Saturation")
                        Slider(value = cur.saturation, onValueChange = { v -> upd { it.copy(saturation = v) } }, valueRange = -100f..100f)
                    }
                    "Filters" -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(FILTERS) { f ->
                            FilterChip(selected = cur.filter == f, onClick = { upd { it.copy(filter = f) } }, label = { Text(f) })
                        }
                    }
                    "Crop" -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ASPECTS.keys.toList()) { a ->
                            FilterChip(selected = cur.aspect == a, onClick = { upd { it.copy(aspect = a) } }, label = { Text(a) })
                        }
                    }
                    "Rotate" -> Button(onClick = { upd { it.copy(rotation = (it.rotation + 90f) % 360f) } }) {
                        Text("Rotate 90° (now ${cur.rotation.toInt()}°)")
                    }
                    "Text" -> OutlinedTextField(
                        value = cur.text,
                        onValueChange = { v -> upd { it.copy(text = v) } },
                        label = { Text("Caption / title text") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    "Stickers" -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(STICKERS) { s ->
                            FilterChip(
                                selected = cur.sticker == s,
                                onClick = { upd { it.copy(sticker = s) } },
                                label = { Text(if (s.isEmpty()) "None" else s) }
                            )
                        }
                    }
                    "Audio" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = cur.muted, onCheckedChange = { v -> upd { it.copy(muted = v) } })
                        Spacer(Modifier.width(8.dp))
                        Text("Mute original audio")
                    }
                    else -> Text("Applied to selected clip")
                }
            }
        }
    }
}
