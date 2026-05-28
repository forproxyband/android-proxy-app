package com.proxyagent.app.nativeagent.quic.stream

import com.proxyagent.app.nativeagent.quic.wire.Stream as StreamFrame
import java.io.InputStream
import java.io.OutputStream
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-stream send/receive state machine + buffers.
 *
 * **Stream IDs (RFC 9000 §2.1).** The low 2 bits of a stream ID
 * encode `(initiator, directionality)`:
 *  - 0x00: client-initiated, bidirectional
 *  - 0x01: server-initiated, bidirectional
 *  - 0x02: client-initiated, unidirectional
 *  - 0x03: server-initiated, unidirectional
 *
 * For our proxy use case, the agent is the QUIC **client**. The
 * proxy-server opens one bidi stream per tunnel (so server-
 * initiated bidi, low 2 bits = 0x01). We also open one client-
 * initiated bidi stream for the control channel.
 *
 * **State machines.** Send and receive sides advance
 * independently. We track them as enums and refuse transitions
 * that would violate the spec — though in practice we route
 * through STREAM/RESET_STREAM/STOP_SENDING frames in the
 * connection layer.
 *
 * **Buffers.**
 *  - Send: unbounded (`SendBuffer`) — application writes
 *    accumulate here, the sender thread drains and emits STREAM
 *    frames. Backpressure is via the peer's MAX_STREAM_DATA
 *    flow-control window, not via a fixed buffer cap.
 *    Explicitly NOT the kwik 50 KB-cap design that triggered our
 *    receive-direction regression — see `ARCHITECTURE.md` QUIC
 *    section.
 *  - Receive: `ReceiveBuffer` reassembles potentially out-of-
 *    order STREAM frames into contiguous bytes the application
 *    can read.
 */
internal class Stream(
    val streamId: Long,
    /** Initial flow-control window we grant the peer for sending
     *  on this stream (peer's MAX_STREAM_DATA from our side). */
    val ourInitialMaxStreamData: Long,
    /** Initial flow-control window the peer granted us for
     *  sending on this stream (their MAX_STREAM_DATA from us). */
    var peerMaxStreamData: Long,
    /** Per-stream cap on the userspace SendBuffer (see
     *  [SendBuffer] kdoc). Bounds how far ahead the bridge thread
     *  may write before it blocks on [SendBuffer.write], which is
     *  what gives us kwik-style backpressure to the upstream TCP
     *  socket when peer's flow control pins. Sourced from the
     *  active NetworkProfile. */
    sendBufferMaxBytes: Int = SendBuffer.DEFAULT_MAX_BUFFER_BYTES,
) {
    /** Send-side state per RFC 9000 §3.1. */
    enum class SendState { READY, SEND, DATA_SENT, DATA_RECVD, RESET_SENT, RESET_RECVD }
    /** Receive-side state per RFC 9000 §3.2. */
    enum class RecvState { RECV, SIZE_KNOWN, DATA_RECVD, DATA_READ, RESET_RECVD, RESET_READ }

    @Volatile var sendState: SendState = SendState.READY
        private set
    @Volatile var recvState: RecvState = RecvState.RECV
        private set

    val sendBuffer = SendBuffer(sendBufferMaxBytes)
    val recvBuffer = ReceiveBuffer(ourInitialMaxStreamData)

    /** Highest stream offset we've sent. */
    @Volatile var sendOffset: Long = 0L
        private set
    /** Whether the FIN bit has been set on a sent STREAM frame. */
    @Volatile var sentFin: Boolean = false
        private set

    private val lock = ReentrantLock()

    /** Public input/output streams for the application layer. */
    val input: InputStream = StreamInputStream(this)
    val output: OutputStream = StreamOutputStream(this)

    /** Optional hook the connection installs so a write to this
     *  stream's [output] promptly wakes the sender loop instead of
     *  waiting out its idle poll (≤20 ms). Invoked on the writing
     *  (bridge) thread, so keep it cheap — just an offer to the send
     *  queue. Without it, the first byte of every server response
     *  would sit buffered up to one idle tick before going on the
     *  wire, adding latency to every tunnel. */
    @Volatile var sendWakeup: (() -> Unit)? = null

    /**
     * Called by the sender thread to drain up to [maxBytes]
     * of pending data plus a FIN flag if the send side is
     * closing. Returns null when nothing to send. Advances
     * [sendOffset] by the returned frame's data length.
     *
     * Honors the peer's flow-control window — never returns
     * more bytes than `peerMaxStreamData - sendOffset`.
     */
    fun pollSendFrame(maxBytes: Int): StreamFrame? = lock.withLock {
        if (sendState == SendState.DATA_RECVD || sendState == SendState.RESET_SENT) return@withLock null
        val flowCredit = (peerMaxStreamData - sendOffset).coerceAtLeast(0L)
        val budget = minOf(maxBytes.toLong(), flowCredit).toInt()
        val data: ByteArray
        val finBit: Boolean
        if (budget <= 0) {
            // Even with no payload bytes, we may still want to
            // emit a FIN-only frame if the app has closed.
            if (sendBuffer.closed && !sentFin && sendBuffer.queuedBytes == 0L) {
                data = ByteArray(0)
                finBit = true
            } else {
                return@withLock null
            }
        } else {
            data = sendBuffer.drain(budget)
            if (data.isEmpty() && !(sendBuffer.closed && !sentFin)) return@withLock null
            finBit = sendBuffer.closed && sendBuffer.queuedBytes == 0L
        }
        val frame = StreamFrame(
            streamId = streamId,
            offset = sendOffset,
            data = data,
            fin = finBit,
            explicitLength = true,
        )
        sendOffset += data.size
        if (finBit) {
            sentFin = true
            sendState = SendState.DATA_SENT
        } else if (sendState == SendState.READY) {
            sendState = SendState.SEND
        }
        frame
    }

    /**
     * Called by the receiver thread when a STREAM frame for this
     * stream arrives. Inserts into the receive buffer (which
     * tolerates out-of-order delivery). Returns true if any new
     * contiguous bytes became readable.
     */
    fun acceptStreamFrame(frame: StreamFrame): Boolean = lock.withLock {
        require(frame.streamId == streamId) { "stream id mismatch" }
        val advanced = recvBuffer.write(frame.offset, frame.data, fin = frame.fin)
        if (frame.fin) {
            recvState = when (recvState) {
                RecvState.RECV -> RecvState.SIZE_KNOWN
                else -> recvState
            }
        }
        if (recvBuffer.eofReached) {
            recvState = when (recvState) {
                RecvState.SIZE_KNOWN, RecvState.RECV -> RecvState.DATA_RECVD
                else -> recvState
            }
        }
        advanced
    }

    /**
     * Called when peer sends MAX_STREAM_DATA for this stream;
     * widens our send window. No-op if [newMax] is not higher
     * than what we already had.
     */
    fun applyMaxStreamData(newMax: Long) = lock.withLock {
        if (newMax > peerMaxStreamData) peerMaxStreamData = newMax
    }

    /**
     * Returns a new MAX_STREAM_DATA value to advertise to the peer
     * if the application has drained enough of this stream's
     * receive window to warrant extending credit, else null.
     * Called by the connection sender loop on each tick; the
     * caller emits a MAX_STREAM_DATA frame for any non-null value.
     * Delegates to [ReceiveBuffer.maybeExtendWindow], which takes
     * its own lock — no need to hold the Stream lock here.
     */
    fun maybeExtendRecvWindow(): Long? = recvBuffer.maybeExtendWindow()

    /** Mark send side closed (FIN to be set on next/last frame). */
    fun closeSend() = lock.withLock { sendBuffer.close() }

    /** Called by [Connection.close] to wake any bridge thread parked in
     *  this stream's [input.read]. Without this, force-closing the QUIC
     *  connection leaves bridge threads dangling and the agent never
     *  notices the disconnect. */
    fun closeOnConnectionTermination() {
        recvBuffer.closeOnConnectionTermination()
        sendBuffer.close()
    }
}

// ────────────────────────────────────────────────────────────────────
// Send buffer — bounded, blocks on write() when full so the bridge
// thread feeding it can't accumulate gigabytes locally when the QUIC
// peer stops accepting bytes.
// ────────────────────────────────────────────────────────────────────

/**
 * Append-only FIFO of bytes waiting to be sent, capped at
 * [maxBufferBytes]. Writes that would push past the cap block on
 * [notFull] until the sender drains room (kwik's SendBuffer design,
 * see `tech.kwik.core.stream.SendBuffer` for the reference).
 *
 * **Why bounded:** earlier (build ≤99) this buffer was unbounded.
 * The bridge thread that copies TCP-target bytes into the stream
 * could pump unlimited bytes locally even when peer's MAX_DATA was
 * pinned (e.g. proxy-server-go after a download speedtest). The
 * stuck bytes blocked the build-97 stall self-heal from clearing
 * and prevented the upload phase from using the same QUIC
 * connection. With a hard cap, the bridge stalls on write() →
 * TCP target socket's window fills → target backs off → no local
 * accumulation, no need to close the QUIC connection to recover.
 *
 * **Cap size:** comes from the active
 * [com.proxyagent.app.nativeagent.quic.NetworkProfile]'s
 * `sendBufferMaxBytes`. Big enough to hold ≈BDP × small fanout
 * (so the sender always has data to pull when credit allows),
 * small enough that an idle stream after a finished tunnel
 * doesn't pin MiB of dead bytes.
 *
 * **Sender fairness:** in our QUIC stack the sender prioritizes
 * ACK / MAX_*_DATA control frames over STREAM data, so a fat
 * SendBuffer here does NOT starve receive-direction window
 * updates (the regression kwik saw at 4 MiB doesn't apply —
 * kwik's sender was strictly FIFO).
 */
internal class SendBuffer(private val maxBufferBytes: Int = DEFAULT_MAX_BUFFER_BYTES) {
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0  // bytes consumed from chunks[0]
    @Volatile var queuedBytes: Long = 0L
        private set
    @Volatile var closed: Boolean = false
        private set
    private val lock = ReentrantLock()
    /** Signalled by [drain] whenever bytes leave the buffer AND by
     *  [close] / [unblockAll] when the buffer is being torn down.
     *  Writers parked in [write] await on this. */
    private val notFull = lock.newCondition()

    fun write(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len <= 0) return
        lock.withLock {
            // Park until cap allows the full chunk (or the buffer
            // closes / is force-unblocked). Partial-fit writes would
            // require splitting the source which complicates the
            // public contract; we keep the contract "append once
            // succeeded, contiguous" and split on the caller side if
            // needed. In practice the bridge thread writes ≤bridge-
            // buffer-bytes at a time, well under maxBufferBytes.
            while (!closed && queuedBytes + len > maxBufferBytes) {
                notFull.await()
            }
            if (closed) {
                // Closed while we were parked (e.g. peer dropped the
                // stream or connection terminated). Surface as
                // IOException so the bridge thread's copy loop exits
                // via its catch instead of silently retrying.
                throw java.io.IOException("send buffer closed")
            }
            val copy = ByteArray(len)
            System.arraycopy(data, off, copy, 0, len)
            chunks.addLast(copy)
            queuedBytes += len
        }
    }

    /** Drain up to [maxBytes] from the head into a fresh array.
     *  Signals [notFull] so any [write] parked at the cap wakes
     *  and refills the headroom we just opened. */
    fun drain(maxBytes: Int): ByteArray = lock.withLock {
        if (maxBytes <= 0 || chunks.isEmpty()) return@withLock ByteArray(0)
        val out = ByteArray(minOf(maxBytes.toLong(), queuedBytes).toInt())
        var written = 0
        while (written < out.size && chunks.isNotEmpty()) {
            val head = chunks.first()
            val available = head.size - headOffset
            val take = minOf(available, out.size - written)
            System.arraycopy(head, headOffset, out, written, take)
            written += take
            headOffset += take
            if (headOffset >= head.size) {
                chunks.removeFirst()
                headOffset = 0
            }
        }
        queuedBytes -= written
        if (written > 0) notFull.signalAll()
        if (written == out.size) out else out.copyOf(written)
    }

    fun close() = lock.withLock {
        closed = true
        notFull.signalAll()  // unblock any parked writers
    }

    /** Force-unblock parked writers without marking the buffer
     *  closed — used on connection termination to free bridge
     *  threads so they can exit and let stream teardown proceed. */
    fun unblockAll() = lock.withLock { notFull.signalAll() }

    companion object {
        /** Fallback when no profile-sourced cap is supplied. 1 MiB
         *  matches the LOW_100 BDP at ~80 ms RTT and is the
         *  smallest size that doesn't throttle a single stream
         *  below the profile's nominal rate. */
        const val DEFAULT_MAX_BUFFER_BYTES: Int = 1 * 1024 * 1024
    }
}

// ────────────────────────────────────────────────────────────────────
// Receive buffer — reassembles potentially out-of-order STREAM frames
// into contiguous in-order bytes for the application.
// ────────────────────────────────────────────────────────────────────

/**
 * Holds out-of-order frame payloads keyed by their start offset,
 * and exposes a contiguous read-side as a blocking InputStream.
 *
 * [maxOffsetAllowed] is the flow-control window we advertised
 * to the peer (initial value = `ourInitialMaxStreamData`). The
 * Stream is responsible for emitting MAX_STREAM_DATA frames as
 * the application drains data so the peer can keep sending.
 */
internal class ReceiveBuffer(initialMaxOffset: Long) {
    private val pending = TreeMap<Long, ByteArray>()  // sorted by offset
    /** Next offset the application can read from. Anything in
     *  `pending` at or below this offset has already been
     *  promoted into [readable]. */
    @Volatile var readOffset: Long = 0L
        private set
    /** Highest offset we've received so far (incl. discontiguous
     *  bytes). Used to advertise flow control. */
    @Volatile var highWaterOffset: Long = 0L
        private set
    /** True once the peer FIN'd and we've delivered all bytes up
     *  through the FIN offset. */
    @Volatile var eofReached: Boolean = false
        private set
    /** Final size if the FIN'd offset is known (peer set FIN);
     *  -1 if not yet. */
    private var finalSize: Long = -1L
    @Volatile var maxOffsetAllowed: Long = initialMaxOffset
        private set
    /** The initial window size, retained so [maybeExtendWindow]
     *  can re-grant a full window each time the consumed prefix
     *  crosses the half-window mark. */
    private val initialWindow: Long = initialMaxOffset
    private val readable = ArrayDeque<ByteArray>()
    private var readableHeadOffset = 0
    /** Total bytes the application has actually drained via
     *  [readBlocking]. Distinct from [readOffset], which advances
     *  as soon as contiguous bytes are promoted into [readable]
     *  (i.e. *received*, not yet *read*). Flow-control window
     *  extension keys off THIS so a slow downstream applies real
     *  backpressure instead of letting [readable] grow unbounded. */
    @Volatile var consumedOffset: Long = 0L
        private set
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    /**
     * Insert frame data at [offset]. Returns true if any new
     * contiguous bytes became readable. Out-of-window or
     * duplicate data is silently dropped (RFC 9000 §3.2 allows
     * discarding data already delivered).
     */
    fun write(offset: Long, data: ByteArray, fin: Boolean): Boolean = lock.withLock {
        if (offset + data.size > maxOffsetAllowed) {
            // FLOW_CONTROL_ERROR — but we just clamp and emit a
            // closer to the connection layer. For now, accept up
            // to the limit and drop the rest.
        }
        if (fin) finalSize = offset + data.size
        val end = offset + data.size
        if (end <= readOffset) return@withLock false  // wholly stale
        // Avoid storing duplicates of bytes already merged.
        val effectiveOffset: Long
        val effectiveData: ByteArray
        if (offset < readOffset) {
            val skip = (readOffset - offset).toInt()
            effectiveOffset = readOffset
            effectiveData = data.copyOfRange(skip, data.size)
        } else {
            effectiveOffset = offset
            effectiveData = data
        }
        // Insert, keeping the LONGER frame on a same-offset collision.
        // A shorter retransmit must not clobber a longer one already
        // buffered (that would silently drop the tail bytes).
        val existing = pending[effectiveOffset]
        if (existing == null || effectiveData.size > existing.size) {
            pending[effectiveOffset] = effectiveData
        }
        if (effectiveOffset + effectiveData.size > highWaterOffset) {
            highWaterOffset = effectiveOffset + effectiveData.size
        }
        // Drain everything contiguous (or overlapping) with readOffset.
        // CRITICAL: the head entry's key can be < readOffset when an
        // out-of-order frame was buffered before readOffset advanced
        // past its start (e.g. [50:150] arrives, then [0:80] drains
        // readOffset to 80, leaving [50:150] with key 50 < 80). The
        // old `key != readOffset` break stranded those bytes forever
        // and stalled the stream. We now trim the already-read prefix
        // and only stop at a genuine gap (key > readOffset).
        var advanced = false
        while (true) {
            val head = pending.firstEntry() ?: break
            if (head.key > readOffset) break  // genuine hole — wait for it to fill
            pending.remove(head.key)
            val entryEnd = head.key + head.value.size
            if (entryEnd <= readOffset) continue  // wholly stale duplicate — drop
            val skip = (readOffset - head.key).toInt()  // ≥ 0
            val fresh = if (skip == 0) head.value
                        else head.value.copyOfRange(skip, head.value.size)
            readable.addLast(fresh)
            readOffset += fresh.size
            advanced = true
        }
        if (advanced) notEmpty.signalAll()
        if (finalSize >= 0 && readOffset >= finalSize) {
            eofReached = true
            notEmpty.signalAll()
        }
        advanced
    }

    /** Update the flow-control window we advertise to the peer. */
    fun updateMaxOffset(newMax: Long) = lock.withLock {
        if (newMax > maxOffsetAllowed) maxOffsetAllowed = newMax
    }

    /**
     * Force EOF and wake every reader. Called by [Connection.close] when
     * the underlying QUIC connection is being torn down (e.g. by the
     * stall self-heal). Without this, bridge threads parked in
     * [readBlocking] would stay parked forever, leaking tunnels and
     * preventing the supervisor from seeing the disconnect.
     */
    fun closeOnConnectionTermination() = lock.withLock {
        eofReached = true
        notEmpty.signalAll()
    }

    /**
     * Auto-tuning receive-window update. Returns a new
     * MAX_STREAM_DATA value to advertise when the application has
     * consumed (read) more than half the current window, else
     * null. The connection sender loop calls this each tick and
     * emits a MAX_STREAM_DATA frame for any non-null result.
     *
     * Keyed off [consumedOffset] (bytes the application has
     * actually drained via readBlocking), NOT [readOffset] (bytes
     * promoted to readable) and NOT [highWaterOffset] (bytes
     * received off the wire). Consuming-side tuning bounds our
     * buffering to ~1.5x the initial window and applies correct
     * backpressure: if the downstream TCP socket stalls, the proxy
     * bridge stops reading, consumedOffset stops advancing, and we
     * stop extending credit — which is exactly what flow control is
     * for. (Connection-level MAX_DATA keys off received-bytes as a
     * pragmatic shortcut because nothing tracked consumed bytes
     * there; per-stream we have the true consume cursor, so we do
     * it the RFC-9000-§4.1-correct way.)
     *
     * Without this, every download stream stalls once the peer
     * fills the initial per-stream window — the connection-level
     * MAX_DATA bump alone is necessary but NOT sufficient (the
     * peer is bounded by the MIN of the two windows).
     */
    fun maybeExtendWindow(): Long? = lock.withLock {
        val cur = maxOffsetAllowed
        if (consumedOffset > cur - initialWindow / 2) {
            val next = consumedOffset + initialWindow
            maxOffsetAllowed = next
            next
        } else null
    }

    /** Block until at least one byte is readable or EOF. */
    fun readBlocking(): Int = lock.withLock {
        while (readable.isEmpty() && !eofReached) notEmpty.await()
        if (readable.isEmpty()) return@withLock -1
        val head = readable.first()
        val b = head[readableHeadOffset].toInt() and 0xFF
        readableHeadOffset++
        consumedOffset++
        if (readableHeadOffset >= head.size) {
            readable.removeFirst()
            readableHeadOffset = 0
        }
        b
    }

    fun readBlocking(dst: ByteArray, off: Int, len: Int): Int = lock.withLock {
        while (readable.isEmpty() && !eofReached) notEmpty.await()
        if (readable.isEmpty()) return@withLock -1
        var written = 0
        while (written < len && readable.isNotEmpty()) {
            val head = readable.first()
            val avail = head.size - readableHeadOffset
            val take = minOf(avail, len - written)
            System.arraycopy(head, readableHeadOffset, dst, off + written, take)
            written += take
            readableHeadOffset += take
            if (readableHeadOffset >= head.size) {
                readable.removeFirst()
                readableHeadOffset = 0
            }
        }
        consumedOffset += written
        written
    }
}

// ────────────────────────────────────────────────────────────────────
// java.io adapters
// ────────────────────────────────────────────────────────────────────

private class StreamInputStream(private val stream: Stream) : InputStream() {
    override fun read(): Int = stream.recvBuffer.readBlocking()
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        stream.recvBuffer.readBlocking(b, off, len)
    override fun close() {
        // No-op for now — the connection close path tears down
        // streams en masse. Could send STOP_SENDING if we wanted
        // graceful half-close on the receive side.
    }
}

private class StreamOutputStream(private val stream: Stream) : OutputStream() {
    override fun write(b: Int) {
        stream.sendBuffer.write(byteArrayOf(b.toByte()))
        stream.sendWakeup?.invoke()
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        stream.sendBuffer.write(b, off, len)
        stream.sendWakeup?.invoke()
    }
    override fun close() {
        stream.closeSend()
        stream.sendWakeup?.invoke()  // flush the FIN promptly
    }
}
