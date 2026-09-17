package com.posecam

/** Minimal JSON writer for metadata files, so it stays unit-testable off-device. */
object Json {
    fun write(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Int, is Long -> value.toString()
        is Float, is Double -> {
            val d = (value as Number).toDouble()
            require(d.isFinite()) { "JSON cannot represent $d" }
            value.toString()
        }
        is Map<*, *> -> if (value.isEmpty()) "{}" else {
            val inner = "$indent  "
            value.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
                "$inner${quote(k.toString())}: ${write(v, inner)}"
            }
        }
        is Iterable<*> -> value.joinToString(", ", "[", "]") { write(it, indent) }
        is IntArray -> write(value.toList(), indent)
        is FloatArray -> write(value.toList(), indent)
        else -> throw IllegalArgumentException("Unsupported JSON type: ${value::class}")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
