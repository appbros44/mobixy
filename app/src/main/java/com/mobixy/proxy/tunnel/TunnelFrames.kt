package com.mobixy.proxy.tunnel

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object TunnelFrames {
    const val HEADER_SIZE = 5

    fun encode(sid: Int, payload: ByteArray, flags: Byte = 0): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(sid)
        buffer.put(flags)
        buffer.put(payload)
        return buffer.array()
    }

    data class Decoded(val sid: Int, val flags: Byte, val payload: ByteArray)

    fun decode(frame: ByteArray): Decoded {
        require(frame.size >= HEADER_SIZE) { "Frame too small" }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val sid = buffer.int
        val flags = buffer.get()
        val payload = ByteArray(frame.size - HEADER_SIZE)
        buffer.get(payload)
        return Decoded(sid = sid, flags = flags, payload = payload)
    }
}
