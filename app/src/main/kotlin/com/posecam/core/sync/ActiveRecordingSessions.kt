package com.posecam.core.sync

import java.util.concurrent.ConcurrentHashMap

/**
 * Sessions whose [com.posecam.core.storage.SessionWriter] is open in this process.
 * Their files are still being written, so queue recovery and cleanup must leave them alone.
 * (After a process death nothing is being written, so an in-memory set is exactly right.)
 */
object ActiveRecordingSessions {
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun add(sessionId: String) {
        active.add(sessionId)
    }

    fun remove(sessionId: String) {
        active.remove(sessionId)
    }

    fun contains(sessionId: String): Boolean = sessionId in active

    fun snapshot(): Set<String> = active.toSet()
}
