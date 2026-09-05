package com.ikegami.svcam.semantic

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredSceneTest {
    @Test fun completeScenePreservesUnicodeAndScores() {
        val scene = StructuredScene.parse("""{"global":{"indoor":0.9},"objects":[{"label":"カメラ📷","bbox":[0.5,0.5,0.2,0.2],"scores":{"device":1}}],"relations":{}}""")
        assertEquals(0.9f, scene.global.getValue("indoor"))
        assertEquals("カメラ📷", scene.objects.single().label)
        assertEquals(896, SemanticEncoder.encode(scene).flatten().size)
    }

    @Test(expected = JSONException::class)
    fun exampleJsonMustNotBecomeAnEmptyVector() {
        StructuredScene.parse("""{"example":0.9}""")
    }

    @Test(expected = JSONException::class)
    fun missingRelationsMustNotBeSaved() {
        StructuredScene.parse("""{"global":{},"objects":[]}""")
    }

    @Test(expected = JSONException::class)
    fun truncatedNestedObjectMustNotBeSaved() {
        StructuredScene.parse("""{"global":{"indoor":0.9},"objects":[{"label":"camera","scores":{}}""")
    }
}
