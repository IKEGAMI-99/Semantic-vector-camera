package com.ikegami.svcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ikegami.svcam.BuildConfig
import com.ikegami.svcam.logging.LiveLogEvent
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val terminalBackground = Color(0xFF050807)
private val terminalPrimary = Color(0xFFB7F7CE)
private val terminalSecondary = Color(0xFF83B69A)
private val terminalAccent = Color(0xFF77E6A2)
private val terminalWarn = Color(0xFFFFD166)
private val terminalError = Color(0xFFFF7A7A)
private val terminalDim = Color(0xFF355944)
private val terminalClock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    .withZone(ZoneId.systemDefault())

@Composable
fun ProcessingConsole(
    events: List<LiveLogEvent>,
    startedAt: Instant,
    finished: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var now by remember(startedAt) { mutableStateOf(Instant.now()) }

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    LaunchedEffect(finished, startedAt) {
        while (!finished) {
            now = Instant.now()
            delay(250)
        }
        now = Instant.now()
    }

    val progress = progressFor(events, finished, failed)
    val stage = currentStage(events, finished, failed)
    val elapsed = Duration.between(startedAt, now).toMillis().coerceAtLeast(0L) / 1000.0

    Column(
        modifier
            .fillMaxSize()
            .background(terminalBackground)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "SEMANTIC VECTOR CAMERA",
            color = terminalAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "v${BuildConfig.VERSION_NAME} · SVCAM-896-V1",
                color = terminalSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "%6.2fs".format(Locale.US, elapsed),
                color = terminalSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Text(
            if (finished && failed) "> ERROR / $stage" else if (finished) "> COMPLETE / $stage" else "> $stage",
            color = if (failed) terminalError else terminalPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(terminalDim),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(if (failed) terminalError else terminalAccent),
            )
        }
        Text(
            "${(progress * 100f).toInt().coerceIn(0, 100)}%  ${progressLabel(events, finished, failed)}",
            color = terminalSecondary,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (events.isEmpty()) {
                item {
                    Text(
                        "[--:--:--.---] SESSION   waiting for first event...",
                        color = terminalSecondary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(events.size) { index ->
                val event = events[index]
                Text(
                    terminalLine(event),
                    color = when (event.level) {
                        "ERROR" -> terminalError
                        "WARN" -> terminalWarn
                        else -> terminalPrimary
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (finished) {
            Text(
                if (failed) {
                    "PROCESS ABORTED\nSOURCE FRAME RELEASED WHEN AVAILABLE"
                } else {
                    "896 / 896 VALID\nORIGINAL IMAGE DESTROYED\nSEMANTIC MEMORY SAVED"
                },
                color = if (failed) terminalError else terminalAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClose,
            ) {
                Text(if (failed) "BACK TO CAMERA" else "MEMORY SAVED · RETURN")
            }
        } else {
            Text(
                "AI IS CONVERTING THE FRAME INTO SEMANTIC MEMORY",
                color = terminalSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

private fun terminalLine(event: LiveLogEvent): String {
    val module = event.module.take(10).padEnd(10)
    val message = when (event.event) {
        "capture_requested" -> "Shutter accepted / semantic capture started"
        "gallery_import_requested" -> "Gallery image accepted / semantic capture started"
        "gallery_image_loaded" -> "Gallery bitmap loaded ${event.field("width")}x${event.field("height")}"
        "frame_captured" -> "Frame acquired ${event.field("width")}x${event.field("height")} rot=${event.field("rotation")}"
        "vision_frame_prepare" -> "Preparing source frame ${event.field("width")}x${event.field("height")}"
        "vision_frame_prepared" -> "Vision input ${event.field("width")}x${event.field("height")} scaled=${event.field("scaled")}"
        "rgb_buffer_ready" -> "RGB buffer ready bytes=${event.field("bytes")}"
        "model_load_start" -> "Loading ${event.field("model")} / projector ${event.field("mmproj")}"
        "model_load_complete" -> "Model ready in ${event.field("duration_ms")} ms"
        "inference_start" -> "Gemma vision inference started / max ${event.field("n_predict")} tokens"
        "kv_cache_cleared" -> "KV cache cleared"
        "native_bitmap_ready" -> "Native bitmap ready / ${event.field("elapsed_ms")} ms"
        "chat_template_ready" -> "Chat template ready / chars=${event.field("current")}"
        "native_tokenize_start" -> "Tokenizing multimodal prompt"
        "native_tokenize_complete" -> "Multimodal prompt tokenized / tokens=${event.field("current")} positions=${event.field("total")} / ${event.field("elapsed_ms")} ms"
        "vision_plan_ready" -> "Vision plan ready / chunks=${event.field("current")} tokens=${event.field("total")}"
        "vision_eval_start" -> "Multimodal evaluation started"
        "vision_chunk_start" -> "Chunk ${event.field("current")}/${event.field("total")} started"
        "vision_chunk_shape" -> "Chunk shape tokens=${event.field("current")} positions=${event.field("total")}"
        "vision_text_prefill_start" -> "Text prefill started / tokens=${event.field("current")}"
        "vision_text_prefill_complete" -> "Text prefill complete / positions=${event.field("current")} / ${event.field("elapsed_ms")} ms"
        "vision_image_encode_start" -> "Vision encoder started / image tokens=${event.field("current")} positions=${event.field("total")}"
        "vision_image_encode_complete" -> "Vision encoder complete / ${event.field("elapsed_ms")} ms"
        "vision_image_llm_prefill_start" -> "Image embeddings -> Gemma prefill started"
        "vision_image_llm_prefill_complete" -> "Image prefill complete / positions=${event.field("current")} / ${event.field("elapsed_ms")} ms"
        "vision_chunk_complete" -> "Chunk ${event.field("current")}/${event.field("total")} complete"
        "native_heartbeat" -> "Still working / ${nativeStageLabel(event.field("stage"))} / wall=${event.field("wall_elapsed_ms")} ms"
        "vision_eval_complete" -> "Vision/prefill complete / n_past=${event.field("current")} / ${event.field("elapsed_ms")} ms"
        "generation_start" -> "Semantic JSON generation started / max=${event.field("total")}"
        "generation_progress" -> "Generating JSON token ${event.field("current")}/${event.field("total")} / ${event.field("elapsed_ms")} ms"
        "generation_json_complete" -> "Complete JSON detected at token ${event.field("current")} / stopping early"
        "generation_complete" -> "JSON generation complete tokens=${event.field("current")} / ${event.field("elapsed_ms")} ms"
        "inference_complete" -> "Inference complete ${event.field("duration_ms")} ms / ${event.field("output_chars")} chars"
        "semantic_parse_start" -> "Parsing structured scene JSON"
        "semantic_parse_complete" -> "Scene parsed global=${event.field("global_scores")} objects=${event.field("objects")} relations=${event.field("relation_scores")}"
        "encoding_start" -> "Encoding fixed SVCAM-896-V1 layout"
        "vector_valid" -> "Vector valid ${event.field("dimensions")}/896 / objects=${event.field("objects")}"
        "capture_saved" -> "Semantic memory written ${event.field("file")} (${event.field("bytes")} bytes)"
        "original_frame_destroyed" -> "Source Bitmap destroyed"
        "semantic_memory_complete" -> "MEMORY COMPLETE / ${event.field("objects")} objects"
        "capture_failed", "processing_failed", "gallery_import_failed" -> event.field("message").ifBlank { "Processing failed" }
        else -> buildString {
            append(event.event.replace('_', ' '))
            val extras = event.fields.entries
                .filterNot { it.key in setOf("exception", "message") }
                .take(3)
                .joinToString(" ") { "${it.key}=${it.value}" }
            if (extras.isNotBlank()) append(" / ").append(extras)
        }
    }
    return "[${terminalClock.format(event.time)}] $module $message"
}

private fun LiveLogEvent.field(name: String): String = fields[name]?.toString().orEmpty()

private fun nativeStageLabel(raw: String): String = when {
    raw.startsWith("vision_image_encode") -> "VISION ENCODER"
    raw.startsWith("vision_image_llm_prefill") -> "IMAGE PREFILL"
    raw.startsWith("vision_text_prefill") -> "TEXT PREFILL"
    raw.startsWith("vision_chunk") || raw.startsWith("vision_eval") -> "VISION + PREFILL"
    raw.startsWith("native_tokenize") -> "BUILD VISION TOKENS"
    raw.startsWith("generation_") -> "GENERATE SEMANTICS"
    raw.isBlank() || raw == "idle" -> "NATIVE"
    else -> raw.replace('_', ' ').uppercase(Locale.US)
}

private fun currentStage(events: List<LiveLogEvent>, finished: Boolean, failed: Boolean): String {
    if (finished) return if (failed) "FAILED" else "MEMORY SAVED"
    val last = events.lastOrNull() ?: return "CAPTURE"
    if (last.event == "native_heartbeat") return nativeStageLabel(last.field("stage"))
    return when {
        last.module == "CAMERA" -> "CAPTURE"
        last.event.startsWith("gallery_") -> "VISION INPUT"
        last.event.startsWith("model_load") -> "LOAD MODEL"
        last.module == "IMAGE" && last.event != "original_frame_destroyed" -> "VISION INPUT"
        last.event.startsWith("vision_image_encode") -> "VISION ENCODER"
        last.event.startsWith("vision_image_llm_prefill") -> "IMAGE PREFILL"
        last.event.startsWith("vision_text_prefill") -> "TEXT PREFILL"
        last.event.startsWith("vision_chunk") || last.event.startsWith("vision_eval") -> "VISION + PREFILL"
        last.event == "inference_start" || last.event.startsWith("native_") || last.event == "chat_template_ready" || last.event == "kv_cache_cleared" -> "BUILD VISION TOKENS"
        last.event.startsWith("generation_") -> "GENERATE SEMANTICS"
        last.event == "inference_complete" || last.event.startsWith("semantic_parse") -> "SEMANTIC PARSE"
        last.event == "encoding_start" -> "ENCODE 896D"
        last.event == "vector_valid" -> "VALIDATE 896D"
        last.event == "capture_saved" -> "SAVE MEMORY"
        last.event == "original_frame_destroyed" -> "DESTROY ORIGINAL"
        else -> last.module
    }
}

private fun progressForNativeStage(stage: String): Float = when {
    stage.startsWith("vision_image_encode_complete") -> 0.56f
    stage.startsWith("vision_image_encode") -> 0.52f
    stage.startsWith("vision_image_llm_prefill_complete") -> 0.61f
    stage.startsWith("vision_image_llm_prefill") -> 0.57f
    stage.startsWith("vision_text_prefill_complete") -> 0.515f
    stage.startsWith("vision_text_prefill") -> 0.51f
    stage.startsWith("vision_chunk_complete") -> 0.615f
    stage.startsWith("vision_chunk") || stage.startsWith("vision_eval") -> 0.505f
    stage.startsWith("generation_") -> 0.63f
    else -> 0.49f
}

private fun progressFor(events: List<LiveLogEvent>, finished: Boolean, failed: Boolean): Float {
    if (finished) return if (failed) progressFor(events, false, false).coerceAtLeast(0.08f) else 1f
    var progress = 0.03f
    events.forEach { event ->
        val eventProgress = when (event.event) {
            "capture_requested", "gallery_import_requested" -> 0.05f
            "frame_captured", "gallery_image_loaded" -> 0.12f
            "vision_frame_prepare" -> 0.16f
            "model_load_start" -> 0.20f
            "model_load_complete" -> 0.30f
            "vision_frame_prepared" -> 0.34f
            "rgb_buffer_ready" -> 0.38f
            "inference_start" -> 0.42f
            "kv_cache_cleared" -> 0.43f
            "native_bitmap_ready" -> 0.44f
            "chat_template_ready" -> 0.45f
            "native_tokenize_start" -> 0.46f
            "native_tokenize_complete" -> 0.49f
            "vision_plan_ready" -> 0.495f
            "vision_eval_start" -> 0.50f
            "vision_chunk_start", "vision_chunk_shape" -> 0.505f
            "vision_text_prefill_start" -> 0.51f
            "vision_text_prefill_complete" -> 0.515f
            "vision_image_encode_start" -> 0.52f
            "vision_image_encode_complete" -> 0.56f
            "vision_image_llm_prefill_start" -> 0.57f
            "vision_image_llm_prefill_complete" -> 0.61f
            "vision_chunk_complete" -> 0.615f
            "native_heartbeat" -> progressForNativeStage(event.field("stage"))
            "vision_eval_complete" -> 0.62f
            "generation_start" -> 0.63f
            "generation_progress" -> {
                val current = event.field("current").toFloatOrNull() ?: 0f
                val total = event.field("total").toFloatOrNull()?.coerceAtLeast(1f) ?: 1f
                0.63f + 0.06f * (current / total).coerceIn(0f, 1f)
            }
            "generation_json_complete" -> 0.695f
            "generation_complete" -> 0.70f
            "inference_complete" -> 0.72f
            "semantic_parse_start" -> 0.75f
            "semantic_parse_complete" -> 0.80f
            "encoding_start" -> 0.84f
            "vector_valid" -> 0.92f
            "capture_saved" -> 0.97f
            "original_frame_destroyed" -> 0.99f
            "semantic_memory_complete" -> 1f
            else -> progress
        }
        progress = maxOf(progress, eventProgress)
    }
    return progress
}

private fun progressLabel(events: List<LiveLogEvent>, finished: Boolean, failed: Boolean): String {
    if (finished) return if (failed) "PROCESSING FAILED" else "SEMANTIC MEMORY COMPLETE"
    return when (currentStage(events, false, false)) {
        "CAPTURE" -> "CAPTURING REALITY"
        "LOAD MODEL" -> "LOADING GEMMA + VISION PROJECTOR"
        "VISION INPUT" -> "PREPARING SOURCE IMAGE"
        "BUILD VISION TOKENS" -> "TOKENIZING IMAGE + SEMANTIC PROMPT"
        "VISION ENCODER" -> "RUNNING GEMMA VISION ENCODER ON CPU"
        "TEXT PREFILL" -> "PREFILLING TEXT TOKENS"
        "IMAGE PREFILL" -> "PREFILLING GEMMA WITH IMAGE EMBEDDINGS"
        "VISION + PREFILL" -> "RUNNING MULTIMODAL PREFILL"
        "GENERATE SEMANTICS" -> "GEMMA IS EMITTING COMPACT JSON"
        "SEMANTIC PARSE" -> "STRUCTURING OBJECTS AND RELATIONS"
        "ENCODE 896D" -> "ENCODING SEMANTIC VECTOR"
        "VALIDATE 896D" -> "VALIDATING DIMENSIONS"
        "SAVE MEMORY" -> "WRITING .SVCAM.JSON"
        "DESTROY ORIGINAL" -> "RELEASING SOURCE IMAGE"
        else -> "PROCESSING"
    }
}
