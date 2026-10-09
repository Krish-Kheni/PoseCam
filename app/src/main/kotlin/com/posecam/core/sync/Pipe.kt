package com.posecam.core.sync

/**
 * The folder a recording is filed under in the cloud (`<prefix>/<wire>-pipe/<sessionId>/...`). The collector picks one
 * in the end-of-take dialog; until they do, the recording is not uploaded.
 *
 * The backend owns the list (`GET /v1/pipes`, kept by [PipeCatalog]), so a new colour needs no app release: a pipe is just
 * its [wire] id plus what to show for it. [DEFAULTS] are the three the app has always known, used until the first fetch
 * succeeds and whenever the cached list is unusable. Pipes are equal when their [wire] ids are: the label is display only.
 */
class Pipe(val wire: String, val label: String, val color: Int? = null) {
    override fun equals(other: Any?): Boolean = other is Pipe && other.wire == wire
    override fun hashCode(): Int = wire.hashCode()
    override fun toString(): String = "Pipe($wire)"

    companion object {
        val WHITE = Pipe("white", "White pipe", 0xFFF8FAFC.toInt())
        val BLACK = Pipe("black", "Black pipe", 0xFF111827.toInt())
        val BLACK_WHITE = Pipe("black-white", "Black/White pipe", 0xFF9CA3AF.toInt())

        val DEFAULTS: List<Pipe> = listOf(WHITE, BLACK, BLACK_WHITE)

        /** The wire id is also an S3 folder name, so it is lowercase words joined by single hyphens. */
        fun isValidWire(value: String): Boolean = WIRE_RE.matches(value)

        /** "White pipes" -> "White pipe": the server names the category, the phone names one pipe. */
        fun short(label: String): String = label.replace(Regex("(?i)pipes$"), "pipe")

        fun fromWire(value: String?, among: List<Pipe> = DEFAULTS): Pipe? = among.firstOrNull { it.wire == value }

        private val WIRE_RE = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}
