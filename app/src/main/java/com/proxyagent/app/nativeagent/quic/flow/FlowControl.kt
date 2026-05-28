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

    /** Peer-advertised MAX_DATA value at which we last emitted a
     *  DATA_BLOCKED frame, paired with the wall time of that emission
     *  so we can periodically RE-emit while still blocked at the same
     *  limit — quic-go ignores a one-shot DATA_BLOCKED (build-96 logs
     *  confirmed: db_sent went 0→1 at credit=0, md_recv then sat
     *  unchanged at 56 for 115 seconds). RFC 9000 §19.12 permits
     *  repeated DATA_BLOCKED while blocked. */
    private val lastDataBlockedAt = AtomicLong(-1L)
    @Volatile private var lastDataBlockedNanos: Long = 0L
    /** Re-emit interval. 1 s is aggressive but bounded — at most
     *  1 DATA_BLOCKED/sec while stuck, which is well under any flood
     *  threshold but frequent enough to keep nudging the peer. */
    private val dataBlockedReemitNanos: Long = 1_000_000_000L

    /**
     * Returns the current peer-advertised limit if we are connection-
     * flow-blocked at it AND it's time to (re-)signal the peer via a
     * DATA_BLOCKED frame (RFC 9000 §19.12). Without this signal,
     * proxy-server-go can leave us with `peerMaxData==bytesSent` for
     * minutes after a heavy download (the proxy stops proactively
     * extending MAX_DATA once its consume rate falls to zero). One-
     * shot wasn't enough (build 96), so we re-emit every
     * [dataBlockedReemitNanos] while still blocked at the same limit;
     * when the peer extends the limit we reset and the new value
     * becomes eligible immediately if we hit it again.
     */
    fun shouldEmitDataBlocked(): Long? {
        val cur = peerMaxData.get()
        val sent = bytesSent.get()
        if (sent < cur) return null
        val prev = lastDataBlockedAt.get()
        val now = System.nanoTime()
        val limitChanged = prev != cur
        val intervalElapsed = now - lastDataBlockedNanos >= dataBlockedReemitNanos
        if (!limitChanged && !intervalElapsed) return null
        lastDataBlockedAt.set(cur)
        lastDataBlockedNanos = now
        return cur
    }

    // ── Receive side ─────────────────────────────────────────

    fun onBytesReceived(bytes: Int) { bytesReceived.addAndGet(bytes.toLong()) }
    fun onBytesConsumed(bytes: Int) { bytesConsumed.addAndGet(bytes.toLong()) }

    /**
     * Returns a new connection-level MAX_DATA value to advertise if
     * the peer has filled more than half the current window, else
     * null. Caller emits a MAX_DATA frame.
     *
     * Keyed off [bytesReceived] (total the peer has sent us), NOT
     * consumed: our proxy bridge drains received bytes straight into
     * the target TCP socket, so "received" tracks usage closely
     * enough and we never need a separate consume hook. The old
     * implementation used `bytesConsumed`, which nothing ever
     * incremented, so MAX_DATA was never sent and the peer stalled
     * at the initial 12 MB window — fine for a page load, fatal for
     * a download speed test.
     */
    fun shouldAdvertiseMaxData(): Long? {
        val cur = ourAdvertisedMaxData.get()
        val received = bytesReceived.get()
        return if (received > cur - initialOurMaxData / 2) {
            val next = received + initialOurMaxData
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
