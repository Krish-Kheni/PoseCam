package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun writesNestedValuesAndEscapes() {
        val json = Json.write(linkedMapOf("a" to "q\"\n", "b" to null, "c" to intArrayOf(1, 2), "d" to linkedMapOf("e" to true)))
        assertEquals("{\n  \"a\": \"q\\\"\\n\",\n  \"b\": null,\n  \"c\": [1, 2],\n  \"d\": {\n    \"e\": true\n  }\n}", json)
    }
}
