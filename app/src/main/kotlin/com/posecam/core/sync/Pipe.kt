package com.posecam.core.sync

/**
 * The folder a recording is filed under in the cloud (`<prefix>/<wire>-pipe/<sessionId>/...`). The collector picks one
 * in the end-of-take dialog; until they do, the recording is not uploaded. The three values are the categories LabelNow
 * knows; the backend refuses any other with `400 INVALID_REQUEST`.
 */
enum class Pipe(val wire: String, val label: String) {
    WHITE("white", "White pipe"),
    BLACK("black", "Black pipe"),
    BLACK_WHITE("black-white", "Black/White pipe"),
    ;

    companion object {
        fun fromWire(value: String?): Pipe? = entries.firstOrNull { it.wire == value }
    }
}
