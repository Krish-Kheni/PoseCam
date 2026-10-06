package com.posecam.core.sync

/**
 * The folder a recording is filed under in the cloud (`sessions/<wire>-pipe/<sessionId>/...`). The collector picks one
 * in the end-of-take dialog; until they do, the recording is not uploaded.
 */
enum class Pipe(val wire: String, val label: String) {
    WHITE("white", "White pipe"),
    BLACK("black", "Black pipe"),
    ;

    companion object {
        fun fromWire(value: String?): Pipe? = entries.firstOrNull { it.wire == value }
    }
}
