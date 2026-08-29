package com.ikegami.svcam.semantic

import kotlin.math.round

data class SemanticVector(
    val global: FloatArray,
    val objects: List<FloatArray>,
    val relations: FloatArray,
    val objectCount: Int,
) {
    fun flatten(): FloatArray {
        val output = FloatArray(SemanticSchema.TOTAL_DIMENSIONS)
        var offset = 0
        global.copyInto(output, offset)
        offset += global.size
        objects.forEach { slot ->
            slot.copyInto(output, offset)
            offset += slot.size
        }
        relations.copyInto(output, offset)
        return output
    }
}

object SemanticEncoder {
    private val globalIndex = SemanticSchema.GLOBAL_LABELS.withIndex().associate { it.value to it.index }
    private val objectIndex = SemanticSchema.OBJECT_LABELS.withIndex().associate { it.value to it.index }
    private val relationIndex = SemanticSchema.RELATION_LABELS.withIndex().associate { it.value to it.index }

    fun encode(scene: StructuredScene): SemanticVector {
        val global = FloatArray(SemanticSchema.GLOBAL_DIMENSIONS)
        scene.global.forEach { (key, value) -> globalIndex[key]?.let { global[it] = normalized(value) } }

        val objectSlots = MutableList(SemanticSchema.OBJECT_SLOTS) {
            FloatArray(SemanticSchema.OBJECT_DIMENSIONS)
        }
        scene.objects.take(SemanticSchema.OBJECT_SLOTS).forEachIndexed { slot, obj ->
            val vector = objectSlots[slot]
            obj.scores.forEach { (key, value) -> objectIndex[key]?.let { vector[it] = normalized(value) } }
            listOf("center_x", "center_y", "width", "height").forEachIndexed { index, key ->
                objectIndex[key]?.let { vector[it] = normalized(obj.bbox.getOrElse(index) { 0f }) }
            }
        }

        val relations = FloatArray(SemanticSchema.RELATION_DIMENSIONS)
        scene.relations.forEach { (key, value) -> relationIndex[key]?.let { relations[it] = normalized(value) } }

        val result = SemanticVector(global, objectSlots, relations, scene.objects.size.coerceAtMost(16))
        check(result.flatten().size == SemanticSchema.TOTAL_DIMENSIONS)
        return result
    }

    private fun normalized(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return round(clamped * 10_000f) / 10_000f
    }
}
