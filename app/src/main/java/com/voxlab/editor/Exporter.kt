package com.voxlab.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import java.io.File

data class Clip(
    val uri: Uri,
    val durationMs: Long,
    val startMs: Long = 0,
    val endMs: Long = durationMs,
    val speed: Float = 1f,
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -100..100
    val filter: String = "None",
    val aspect: String = "Original",
    val rotation: Float = 0f,
    val muted: Boolean = false,
    val text: String = "",
    val sticker: String = "",
)

val FILTERS = listOf("None", "B&W", "Invert", "Warm", "Cool")
val ASPECTS = mapOf("Original" to 0f, "1:1" to 1f, "16:9" to 16f / 9f, "9:16" to 9f / 16f, "4:5" to 0.8f)
val STICKERS = listOf("", "😀", "🔥", "❤️", "⭐", "🎉", "😎", "👍")

@UnstableApi
fun buildEdited(c: Clip): EditedMediaItem {
    val item = MediaItem.Builder().setUri(c.uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()
        ).build()

    val video = mutableListOf<Effect>()
    val audio = mutableListOf<AudioProcessor>()
    if (c.speed != 1f) {
        video += SpeedChangeEffect(c.speed)
        audio += SonicAudioProcessor().apply { setSpeed(c.speed); setPitch(1f) }
    }
    if (c.rotation != 0f) video += ScaleAndRotateTransformation.Builder().setRotationDegrees(c.rotation).build()
    ASPECTS[c.aspect]?.takeIf { it > 0f }?.let {
        video += Presentation.createForAspectRatio(it, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
    }
    if (c.brightness != 0f) video += Brightness(c.brightness)
    if (c.contrast != 0f) video += Contrast(c.contrast)
    if (c.saturation != 0f) video += HslAdjustment.Builder().adjustSaturation(c.saturation).build()
    when (c.filter) {
        "B&W" -> video += RgbFilter.createGrayscaleFilter()
        "Invert" -> video += RgbFilter.createInvertedFilter()
        "Warm" -> video += RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.85f).build()
        "Cool" -> video += RgbAdjustment.Builder().setRedScale(0.85f).setBlueScale(1.15f).build()
    }
    val overlays = ImmutableList.builder<TextureOverlay>()
    var hasOverlay = false
    if (c.text.isNotBlank()) {
        val s = SpannableString(c.text)
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        s.setSpan(AbsoluteSizeSpan(64), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        overlays.add(TextOverlay.createStaticTextOverlay(s, OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.7f).build()))
        hasOverlay = true
    }
    if (c.sticker.isNotBlank()) {
        val s = SpannableString(c.sticker)
        s.setSpan(AbsoluteSizeSpan(160), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        overlays.add(TextOverlay.createStaticTextOverlay(s, OverlaySettings.Builder().setBackgroundFrameAnchor(0.6f, 0.6f).build()))
        hasOverlay = true
    }
    if (hasOverlay) video += OverlayEffect(overlays.build())

    return EditedMediaItem.Builder(item)
        .setRemoveAudio(c.muted)
        .setEffects(Effects(audio, video))
        .build()
}

@UnstableApi
fun startExport(
    ctx: Context, clips: List<Clip>,
    onDone: (String) -> Unit, onError: (String) -> Unit
): Transformer {
    val out = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "vox_${System.currentTimeMillis()}.mp4")
    val t = Transformer.Builder(ctx).setVideoMimeType("video/avc")
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                onDone(saveToGallery(ctx, out))
            }
            override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                onError(e.message ?: "Export failed")
            }
        }).build()
    val seq = EditedMediaItemSequence(clips.map { buildEdited(it) })
    t.start(Composition.Builder(seq).build(), out.absolutePath)
    return t
}

private fun saveToGallery(ctx: Context, f: File): String {
    if (Build.VERSION.SDK_INT < 29) return f.absolutePath
    val v = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VoxEditor")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return f.absolutePath
    ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
    return "Movies/VoxEditor/${f.name}"
}
