package com.posecam

import java.util.concurrent.ArrayBlockingQueue

/**
 * Fixed set of [YuvBuffer]s. This is what bounds the image queue: when every buffer is
 * waiting to be encoded, [tryAcquire] returns null and the frame is dropped instead of
 * blocking the tracker.
 */
class BufferPool(val capacity: Int) {
    private val free = ArrayBlockingQueue<YuvBuffer>(capacity).apply {
        repeat(capacity) { add(YuvBuffer()) }
    }

    fun tryAcquire(): YuvBuffer? = free.poll()

    fun release(buffer: YuvBuffer) {
        check(free.offer(buffer)) { "Buffer released twice" }
    }

    val available: Int get() = free.size
}
