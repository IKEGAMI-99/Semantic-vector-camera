package com.ikegami.svcam.semantic

import org.json.JSONArray
import org.json.JSONObject

/** Sparse semantic observation produced by the vision model. Values are normalized to 0..1. */
data class StructuredScene(
    val global: Map<String, Float>,
    val objects: List<SceneObject>,
    val relations: Map<String, Float>,
) {
    companion object {
        fun parse(raw: String): StructuredScene {
            val jsonText = extractJson(raw)
            val root = JSONObject(jsonText)
            // Never save a thought/example or an incomplete response as an empty vector.
            return StructuredScene(
                global = root.getJSONObject("global").toFloatMap(),
                objects = root.getJSONArray("objects").toObjects(),
                relations = root.getJSONObject("relations").toFloatMap(),
            )
        }

        private fun extractJson(raw: String): String {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start >= 0 && end > start) { "Vision model did not return a JSON object" }
            return raw.substring(start, end + 1)
        }

        private fun JSONObject?.toFloatMap(): Map<String, Float> {
            if (this == null) return emptyMap()
            return buildMap {
                keys().forEach { key ->
                    val value = optDouble(key, Double.NaN)
                    if (!value.isNaN()) put(key, value.toFloat().coerceIn(0f, 1f))
                }
            }
        }

        private fun JSONArray?.toObjects(): List<SceneObject> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until minOf(length(), SemanticSchema.OBJECT_SLOTS)) {
                    val item = optJSONObject(i) ?: continue
                    val bboxArray = item.optJSONArray("bbox")
                    val bbox = List(4) { index ->
                        bboxArray?.optDouble(index, 0.0)?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                    }
                    add(
                        SceneObject(
                            label = item.optString("label", "object"),
                            bbox = bbox,
                            scores = item.optJSONObject("scores").toFloatMap(),
                        )
                    )
                }
            }
        }
    }
}

data class SceneObject(
    val label: String,
    val bbox: List<Float>,
    val scores: Map<String, Float>,
)
