package com.proxyagent.app.nativeagent.quic.flow

import java.util.concurrent.atomic.AtomicLong

/**
 * Connection-level flow control (RFC 9000 §4).
 *
 * Two directions:
 *  - **Outgoing (send-side):** peer advertised MAX_DATA, we
 *    must not send more bytes than that across all streams.
 *  - **Incoming (receive-side):** we advertised an initial
 *    limit in our transport parameters; we extend the peer's
 *    quota via MAX_DATA frames as the application drains
 *    received data.
 *
 * The receive-side window-update emission cadence is the key
 * fix for the kwik regression we documented in
 * `ARCHITECTURE.md`. We emit MAX_DATA / MAX_STREAM_DATA on a
 * **separate scheduler tick** from STREAM-frame draining, so a
 * busy data-emitting sender thread can never starve the peer
 * by failing to extend credit.
 *
 * Trigger: emit a window-update when the consumed prefix
 * exceeds half of the currently-advertised window
 * (RFC 9000 §4.1's "more than half consumed" heuristic).
 */
internal class ConnectionFlowControl(
    /** Bytes the peer has authorized us to send across all streams. */
    initialPeerMaxData: Long,
    /** Bytes we initially authorize the peer to send to us. */
    private val initialOurMaxData: Long,
) {
    private val peerMaxData = AtomicLong(initialPeerMaxData)
    private val bytesSent = AtomicLong(0)

    /** Bytes the peer has actually delivered to us (read or unread). */
    private val bytesReceived = AtomicLong(0)
    /** The highest MAX_DATA we've sent to the peer. */
    private val ourAdvertisedMaxData = AtomicLong(initialOurMaxData)
    /** Bytes already consumed by the application (`readOffset` summed
     *  across all streams plus retired reset/finished credit). */
    private val bytesConsumed = AtomicLong(0)

    // ── Send side ────────────────────────────────────────────

    fun sendCredit(): Long = peerMaxData.get() - bytesSent.get()
    fun canSend(bytes: Int): Boolean = sendCredit() >= bytes
    fun onBytesSent(bytes: Int) { bytesSent.addAndGet(bytes.toLong()) }
    fun applyPeerMaxData(newMax: Long) {
        peerMaxData.updateAndGet { if (newMax > it) newMax else it }
    }

    // ── Receive side ─────────────────────────────────────────

    fun onBytesReceived(bytes: Int) { bytesReceived.addAndGet(bytes.toLong()) }
    fun onBytesConsumed(bytes: Int) { bytesConsumed.addAndGet(bytes.toLong()) }

    /**
     * Returns a new MAX_DATA value to advertise if the consumed
     * prefix has crossed half of the currently-advertised window,
     * else null. Caller emits a MAX_DATA frame and updates the
     * peer's perception.
     */
    fun shouldAdvertiseMaxData(): Long? {
        val cur = ourAdvertisedMaxData.get()
        val consumed = bytesConsumed.get()
        return if (consumed > cur - initialOurMaxData / 2) {
            val next = consumed + initialOurMaxData
            ourAdvertisedMaxData.set(next)
            next
        } else null
    }
}

/**
 * Per-stream flow control. Parallel of [ConnectionFlowControl]
 * but scoped to one stream ID. Created alongside each Stream;
 * the Stream class owns the bytes-sent / bytes-received
 * counters in its buffers, so this class is mostly a thin
 * wrapper for the credit math.
 */
internal class StreamFlowControl(
    initialPeerMaxStreamData: Long,
    private val initialOurMaxStreamData: Long,
) {
    private val peerMaxStreamData = AtomicLong(initialPeerMaxStreamData)
    private val ourAdvertisedMaxStreamData = AtomicLong(initialOurMaxStreamData)

    fun peerMax(): Long = peerMaxStreamData.get()
    fun applyPeerMaxStreamData(newMax: Long) {
        peerMaxStreamData.updateAndGet { if (newMax > it) newMax else it }
    }

    /**
     * Given the stream's current consumed offset, returns the
     * new MAX_STREAM_DATA value we should advertise — or null
     * if no update is yet warranted.
     */
    fun shouldAdvertiseMaxStreamData(consumedOffset: Long): Long? {
        val cur = ourAdvertisedMaxStreamData.get()
        return if (consumedOffset > cur - initialOurMaxStreamData / 2) {
            val next = consumedOffset + initialOurMaxStreamData
            ourAdvertisedMaxStreamData.set(next)
            next
        } else null
    }
}
