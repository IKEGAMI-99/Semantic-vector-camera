package com.ikegami.svcam.semantic

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticSchemaTest {
    @Test
    fun schemaIsExactly896Dimensions() {
        assertEquals(256, SemanticSchema.GLOBAL_LABELS.size)
        assertEquals(32, SemanticSchema.OBJECT_LABELS.size)
        assertEquals(128, SemanticSchema.RELATION_LABELS.size)
        assertEquals(
            896,
            SemanticSchema.GLOBAL_LABELS.size +
                SemanticSchema.OBJECT_SLOTS * SemanticSchema.OBJECT_LABELS.size +
                SemanticSchema.RELATION_LABELS.size,
        )
    }

    @Test
    fun encoderPadsUnusedObjectSlots() {
        val scene = StructuredScene(
            global = mapOf("outdoor" to 0.8f),
            objects = listOf(SceneObject("person", listOf(0.5f, 0.5f, 0.2f, 0.4f), mapOf("human" to 0.9f))),
            relations = mapOf("near" to 0.5f),
        )
        val vector = SemanticEncoder.encode(scene)
        assertEquals(896, vector.flatten().size)
        assertEquals(1, vector.objectCount)
    }
}
