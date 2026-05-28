package com.proxyagent.app.nativeagent.quic.connection

import com.proxyagent.app.nativeagent.quic.cc.BrutalCongestionControl
import com.proxyagent.app.nativeagent.quic.crypto.InitialKeys
import com.proxyagent.app.nativeagent.quic.flow.ConnectionFlowControl
import com.proxyagent.app.nativeagent.quic.recovery.SpaceRecovery
import com.proxyagent.app.nativeagent.quic.stream.Stream
import com.proxyagent.app.nativeagent.quic.tls.TlsClient
import com.proxyagent.app.nativeagent.quic.tls.TransportParameters
import com.proxyagent.app.nativeagent.quic.tls.encodeTransportParameters
import com.proxyagent.app.nativeagent.quic.wire.Ack
import com.proxyagent.app.nativeagent.quic.wire.ConnectionClose
import com.proxyagent.app.nativeagent.quic.wire.Crypto
import com.proxyagent.app.nativeagent.quic.wire.DataBlocked
import com.proxyagent.app.nativeagent.quic.wire.Frame
import com.proxyagent.app.nativeagent.quic.wire.HandshakeDone
import com.proxyagent.app.nativeagent.quic.wire.LongPacketType
import com.proxyagent.app.nativeagent.quic.wire.MaxData
import com.proxyagent.app.nativeagent.quic.wire.MaxStreamData
import com.proxyagent.app.nativeagent.quic.wire.MaxStreams
import com.proxyagent.app.nativeagent.quic.wire.NewToken
import com.proxyagent.app.nativeagent.quic.wire.PacketNumber
import com.proxyagent.app.nativeagent.quic.wire.PacketNumberSpace
import com.proxyagent.app.nativeagent.quic.wire.PacketWire
import com.proxyagent.app.nativeagent.quic.wire.Padding
import com.proxyagent.app.nativeagent.quic.wire.Ping
import com.proxyagent.app.nativeagent.quic.wire.ResetStream
import com.proxyagent.app.nativeagent.quic.wire.StopSending
import com.proxyagent.app.nativeagent.quic.wire.Stream as StreamFrame
import com.proxyagent.app.nativeagent.quic.wire.encodeLongHeader
import com.proxyagent.app.nativeagent.quic.wire.encodeShortHeader
import com.proxyagent.app.nativeagent.quic.wire.parseLongHeader
import com.proxyagent.app.nativeagent.quic.wire.parseShortHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The top-level QUIC connection. Orchestrates TLS handshake,
 * packet protection, stream management, and the send/receive
 * threads.
 *
 * **Connection lifecycle:**
 *  1. [connect] dials UDP, builds Initial keys from a random
 *     DCID, drives TLS handshake via Initial/Handshake CRYPTO
 *     frames, ending in installed 1-RTT keys and a usable
 *     application-level connection.
 *  2. After [connect] returns, [openStream] / [acceptStream]
 *     create client- or server-initiated streams that the
 *     application uses for byte transfer.
 *  3. [close] tears the connection down cleanly with a
 *     CONNECTION_CLOSE frame.
 *
 * **Threads:**
 *  - `receiveLoop` — single-threaded UDP receive + decrypt +
 *    frame dispatch.
 *  - `senderLoop` — single-threaded packet assembly. Iterates
 *    in priority order: pending TLS bytes → ACKs → window
 *    updates → STREAM data. This priority is the deliberate
 *    fix for the kwik regression where STREAM-frame draining
 *    starved ACK/window-update emission.
 *
 * Threads communicate via concurrent queues; no shared mutable
 * state outside what's documented as such.
 *
 * **Caveats / known incomplete bits** (test-iterate to fix):
 *  - Retry handling on Initial: server-side retry support exists
 *    in the wire layer but the state machine doesn't handle a
 *    retry response; first-shot Initial only.
 *  - Version negotiation: refuses (closes connection) rather
 *    than retrying.
 *  - Key updates (RFC 9001 §6): supported on the receive path —
 *    a peer-initiated Key Phase flip is followed by deriving the
 *    next-generation keys ("quic ku") and adopting them for both
 *    directions. We never *initiate* an update ourselves (fine for
 *    our connection lifetimes, well under the AEAD usage limit).
 *  - PMTU discovery: assumes 1200-byte MTU.
 *  - Datagram extension: disabled.
 */
internal class Connection(
    private val serverHost: String,
    private val serverPort: Int,
    private val resolvedAddress: InetSocketAddress,
    private val alpn: String,
    private val ourTransportParameters: TransportParameters,
    private val ccTargetMbps: Int = 100,
    /** Wi-Fi-return hook: invoked on the raw UDP uplink socket BEFORE
     *  [connect] binds the remote address. The Android pattern is
     *  `Network.bindSocket(socket)`, which must precede `connect()` — once
     *  the socket has a peer it can no longer be moved to a different
     *  network. Re-evaluated on every (re)dial because [Connection] is
     *  recreated by the supervisor on stall self-heal, so each fresh
     *  attempt picks up the current Wi-Fi network. Null = no binding,
     *  socket uses the process default route. */
    private val uplinkSocketBinder: ((java.net.DatagramSocket) -> Unit)? = null,
    /** UDP socket buffer hint (receive + send). Sized by the active
     *  [com.proxyagent.app.nativeagent.quic.NetworkProfile]; smaller
     *  values cap kernel-queue depth so the pacer rate is what bounds
     *  latency, not the socket queue. OS may clamp at
     *  net.core.{r,s}mem_max. */
    private val udpSocketBufBytes: Int = 32 * 1024 * 1024,
    /** Forwarded to [ConnectionFlowControl] — fraction of initial
     *  window kept as headroom before a MAX_DATA refresh fires. */
    private val windowUpdateHeadroomRatio: Double = 0.5,
    /** Per-stream SendBuffer cap. The bridge thread feeding a stream
     *  blocks on [com.proxyagent.app.nativeagent.quic.stream.SendBuffer.write]
     *  when this fills — backpressure to the upstream TCP socket.
     *  Replaces the old build-97 close-and-reconnect self-heal:
     *  with bounded buffers there's no local accumulation when peer
     *  pins MAX_DATA, so the connection never needs to be torn down
     *  to recover. */
    private val streamSendBufferMaxBytes: Int =
        com.proxyagent.app.nativeagent.quic.stream.SendBuffer.DEFAULT_MAX_BUFFER_BYTES,
) {

    // ── Connection IDs (RFC 9000 §5) ──────────────────────────

    /** Our own connection ID — peer's "destination" for packets
     *  flowing to us. We pick this at handshake time. */
    val sourceCid: ByteArray = randomCid(8)

    /** Peer's connection ID — appears as destination in packets
     *  we send. Initially the client-chosen random DCID; the
     *  server may replace this in its first Initial. */
    var destinationCid: ByteArray = randomCid(8)
        private set

    /** The DCID used in the client's very first Initial. We
     *  keep it around to verify against the server's
     *  `original_destination_connection_id` transport param. */
    val originalDcid: ByteArray = destinationCid.copyOf()

    // ── Crypto spaces ─────────────────────────────────────────

    private val initialSpace = CryptoSpace(PacketNumberSpace.INITIAL)
    private val handshakeSpace = CryptoSpace(PacketNumberSpace.HANDSHAKE)
    private val oneRttSpace = CryptoSpace(PacketNumberSpace.ONE_RTT)
    private fun spaceFor(p: PacketNumberSpace): CryptoSpace = when (p) {
        PacketNumberSpace.INITIAL -> initialSpace
        PacketNumberSpace.HANDSHAKE -> handshakeSpace
        PacketNumberSpace.ONE_RTT -> oneRttSpace
    }

    // ── Subsystems ────────────────────────────────────────────

    // Inject our sourceCid into the TPs we advertise — RFC 9000 §18.2
    // makes initial_source_connection_id (TP 0x0f) MANDATORY for QUIC
    // v1 clients. Omitting it causes a TRANSPORT_PARAMETER_ERROR close
    // from quic-go before the handshake completes.
    private val tls = TlsClient(
        serverHost,
        alpn,
        ourTransportParameters.copy(initialSourceConnectionId = sourceCid),
    )
    private val cc = BrutalCongestionControl(ccTargetMbps)
    private val flow = ConnectionFlowControl(
        initialPeerMaxData = 0L,  // will be set from peer's TP
        initialOurMaxData = ourTransportParameters.initialMaxData,
        windowUpdateHeadroomRatio = windowUpdateHeadroomRatio,
    )

    private val streams = ConcurrentHashMap<Long, Stream>()
    private val incomingServerStreams = LinkedBlockingQueue<Stream>()

    /** Next client-initiated bidi stream ID we'll allocate. Each
     *  open advances by 4 (per RFC 9000 §2.1 the low bits encode
     *  initiator + directionality). */
    private val nextClientBidiStreamId = AtomicLong(0L)

    @Volatile var peerTransportParameters: TransportParameters? = null
        private set
    @Volatile var handshakeDone: Boolean = false
        private set

    // ── I/O ────────────────────────────────────────────────────

    private val socket = DatagramSocket().also {
        // Pre-bind buffer hint so the kernel sizes its UDP queue for the
        // expected BDP of this profile. Failures are swallowed: Android
        // commonly clamps below the request (net.core.rmem_max), and the
        // socket still works with the OS default — just at degraded
        // throughput on high-BDP paths.
        try { it.receiveBufferSize = udpSocketBufBytes } catch (_: Throwable) {}
        try { it.sendBufferSize = udpSocketBufBytes } catch (_: Throwable) {}
    }
    private val closed = AtomicBoolean(false)
    private val sendQueue = LinkedBlockingQueue<QueuedSendWork>()
    private lateinit var recvThread: Thread
    private lateinit var sendThread: Thread

    /** Lazily-deferred work the sender thread should pick up. */
    private sealed class QueuedSendWork {
        object Tick : QueuedSendWork()  // generic wakeup
    }

    // ── Public API ────────────────────────────────────────────

    /** Diagnostic log tag — visible via `adb logcat -s NativeQUIC`.
     *  All log lines are at INFO level so the user gets them with
     *  one filter regardless of release/debug build. */
    private fun log(msg: String) {
        android.util.Log.i("NativeQUIC", "[$serverHost] $msg")
    }

    /** Like [log] but ALSO mirrors to [externalStatLog] when installed,
     *  so the line lands in the agent's *exportable* log — not just
     *  logcat, which the field can't easily capture. Reserved for the
     *  low-frequency 5-second stats line so the export stays compact. */
    private fun logStat(msg: String) {
        android.util.Log.i("NativeQUIC", "[$serverHost] $msg")
        externalStatLog?.invoke("[$serverHost] $msg")
    }

    internal companion object {
        /** Diagnostic sink installed by NativeProxyAgent so the in-house
         *  QUIC stats line shows up in the exportable agent log without
         *  `adb logcat`. Process-global (the proxy runs one uplink QUIC
         *  connection); cleared on teardown to avoid leaking the agent. */
        @Volatile var externalStatLog: ((String) -> Unit)? = null
    }

    /** Establish the connection. Blocks until handshake completes
     *  or a fatal error occurs. */
    fun connect(timeoutMs: Long) {
        log("connect: dcid=${originalDcid.toHex()} scid=${sourceCid.toHex()} timeout=${timeoutMs}ms")
        // Wi-Fi-return: bind the uplink socket to the chosen network BEFORE
        // we connect to the peer. After `socket.connect(...)`, Android no
        // longer allows moving the socket to a different network — the bind
        // becomes a no-op silently. The binder closure is supplied per-dial
        // by ProxyService so reconnects (e.g. stall self-heal) pick up the
        // current Wi-Fi `Network` reference even after a Wi-Fi handover.
        try {
            uplinkSocketBinder?.invoke(socket)
        } catch (t: Throwable) {
            log("connect: uplink bind failed (${t.javaClass.simpleName}: ${t.message}) — falling back to default route")
        }
        socket.connect(resolvedAddress)
        socket.soTimeout = 250  // ms — short so receive thread can poll closed flag
        log("connect: udp connected to $resolvedAddress, local=${socket.localPort}")

        // Install Initial keys from our chosen random DCID.
        val initialKeys = InitialKeys.derive(originalDcid)
        initialSpace.installInitialKeys(initialKeys.client, initialKeys.server)
        log("connect: initial keys derived")

        recvThread = Thread(::receiveLoop, "quic-recv-${serverHost}").apply {
            isDaemon = true
            start()
        }
        sendThread = Thread(::senderLoop, "quic-send-${serverHost}").apply {
            isDaemon = true
            start()
        }

        // Start TLS — produces ClientHello, queued for emission.
        // Route via handleTlsStep so the Level→PacketNumberSpace
        // conversion (and any new secrets, though there are none
        // at this point) lives in one place.
        val firstStep = tls.start()
        log("connect: TLS started, ClientHello size=${firstStep.outgoing.values.firstOrNull()?.size ?: 0}")
        handleTlsStep(firstStep)
        sendQueue.offer(QueuedSendWork.Tick)

        // Wait for handshake.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!handshakeDone && System.currentTimeMillis() < deadline && !closed.get()) {
            try { Thread.sleep(20) } catch (_: InterruptedException) {}
        }
        if (!handshakeDone) {
            close()
            throw java.io.IOException("QUIC handshake timeout after ${timeoutMs}ms")
        }
    }

    /** Open a client-initiated bidi stream (used for the control
     *  channel by NativeProxyAgent's startQuic path). */
    fun openBidiStream(): Stream {
        val id = nextClientBidiStreamId.getAndAdd(4L)  // 0, 4, 8, ... (client bidi)
        val s = Stream(
            streamId = id,
            ourInitialMaxStreamData = ourTransportParameters.initialMaxStreamDataBidiLocal,
            peerMaxStreamData = peerTransportParameters?.initialMaxStreamDataBidiRemote ?: 0L,
            sendBufferMaxBytes = streamSendBufferMaxBytes,
        )
        s.sendWakeup = { sendQueue.offer(QueuedSendWork.Tick) }
        streams[id] = s
        return s
    }

    /** Block waiting for a server-initiated stream (each tunnel
     *  arrives as one). Returns null when the connection closes — the
     *  caller (NativeQuicTransport.acceptStream) turns that into an
     *  IOException so the supervisor's accept loop can exit and trigger
     *  a reconnect. We poll in 500 ms slices instead of `take()`-ing
     *  forever so a [close] call propagates promptly to a parked
     *  accept caller. */
    fun acceptStream(timeoutMs: Long = Long.MAX_VALUE): Stream? {
        if (timeoutMs != Long.MAX_VALUE) {
            if (closed.get()) return null
            return incomingServerStreams.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }
        while (!closed.get()) {
            val s = incomingServerStreams.poll(500, TimeUnit.MILLISECONDS) ?: continue
            return s
        }
        return null
    }

    /** Tear down the connection. Best-effort — sends a
     *  CONNECTION_CLOSE then closes the socket. Also forces EOF on
     *  every stream's receive buffer so bridge threads parked in
     *  [Stream.input.read] unblock immediately — otherwise they leak,
     *  the agent's quicAcceptLoop never notices the disconnect (it was
     *  parked too), and the supervisor never gets a chance to reopen
     *  the QUIC connection (build-98 logged STALL TIMEOUT but the
     *  supervisor never reconnected because every caller was wedged). */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val cc = ConnectionClose(
                isApplicationError = false,
                errorCode = 0,
                frameType = 0,
                reasonPhrase = "client closing",
            )
            emitOneRttPacket(listOf(cc))
        } catch (_: Throwable) {}
        try { socket.close() } catch (_: Throwable) {}
        // Unblock everything parked on this connection's streams.
        for (s in streams.values) {
            try { s.closeOnConnectionTermination() } catch (_: Throwable) {}
        }
    }

    // ── Internals: receive loop ───────────────────────────────

    private fun receiveLoop() {
        log("recvLoop: started")
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var packetCount = 0
        while (!closed.get()) {
            try {
                socket.receive(pkt)
                packetCount++
                if (verboseWire) log("recv: pkt#$packetCount size=${pkt.length} firstByte=0x${(buf[0].toInt() and 0xFF).toString(16)}")
                processDatagram(buf, pkt.length)
            } catch (_: java.net.SocketTimeoutException) {
                // Normal — loop polls closed flag.
            } catch (t: Throwable) {
                if (!closed.get()) {
                    log("recvLoop: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        log("recvLoop: exit (packets=$packetCount)")
    }

    private fun processDatagram(bytes: ByteArray, length: Int) {
        statsDatagrams.incrementAndGet()
        var offset = 0
        while (offset < length) {
            val buf = ByteBuffer.wrap(bytes, offset, length - offset)
            val markerStart = buf.position()
            val firstByte = bytes[offset].toInt() and 0xFF
            val isLong = (firstByte and PacketWire.HEADER_FORM_LONG) != 0
            val consumed = if (isLong) processLongPacket(bytes, offset, length, buf)
                           else processShortPacket(bytes, offset, length, buf)
            if (consumed <= 0) break  // protocol error, drop the rest
            offset += consumed
        }
    }

    /** Total UDP datagrams received and frame-type tallies for
     *  post-mortem diagnostics. Logged periodically by the sender
     *  thread so we can see if STREAM frames arrive at all even
     *  when individual frame logs get drowned out. */
    private val statsDatagrams = java.util.concurrent.atomic.AtomicLong(0L)
    private val statsStreamFrames = java.util.concurrent.atomic.AtomicLong(0L)
    private val statsNewServerStreams = java.util.concurrent.atomic.AtomicLong(0L)
    /** 1-RTT AEAD decrypt failures — a non-zero value that keeps
     *  climbing means we're dropping the peer's packets (e.g. an
     *  unhandled key update, which is exactly what froze build 92). */
    private val statsDecryptFailures = java.util.concurrent.atomic.AtomicLong(0L)
    /** DATA_BLOCKED frames we've sent (RFC 9000 §19.12). Non-zero
     *  means we hit send-side flow control and signalled the peer to
     *  extend MAX_DATA. If this climbs but `flow.send_credit` never
     *  recovers, the peer is ignoring our signal (proxy-server-go
     *  quirk) and we need a different unsticking mechanism. */
    private val statsDataBlockedSent = java.util.concurrent.atomic.AtomicLong(0L)
    /** MAX_DATA frames the peer has sent us — i.e. how many times the
     *  peer extended our send-side connection window. Pair with
     *  [statsDataBlockedSent] to tell if DATA_BLOCKED is doing its job. */
    private val statsMaxDataRecv = java.util.concurrent.atomic.AtomicLong(0L)

    // Send-side stall self-heal (build-97) was a force-close when
    // (workRemains && sendCredit==0) for 5 s. It triggered on every
    // download→upload speedtest transition because proxy-server-go
    // pins MAX_DATA after the test client stops reading the download
    // payload. The close-and-reconnect was the visible "обрыв" the
    // user observed. Build-100 replaces that mechanism with kwik-style
    // bounded SendBuffers: the bridge thread blocks at the cap, the
    // upstream TCP socket backs off, no GB-scale local accumulation,
    // workRemains naturally drains as streams complete. Genuinely-dead
    // connections close via QUIC idle_timeout (60 s) — RFC-standard
    // and applies symmetrically to both sides.

    private fun processLongPacket(bytes: ByteArray, datagramStart: Int, datagramEnd: Int, buf: ByteBuffer): Int {
        return try {
            val info = parseLongHeader(buf, datagramEnd)
            val space = info.type.toPacketNumberSpace()
            val cs = spaceFor(space)
            val hp = cs.receiveHp ?: return -1  // we don't have keys yet (shouldn't happen for Initial)

            // Update destinationCid from server's SCID on the very first
            // Initial response (RFC 9000 §7.2 — once the server picks its
            // own connection ID, the client uses that for the rest of the
            // connection). We can detect "first time" by checking if our
            // current dcid still matches the random one we originally chose.
            if (info.type == LongPacketType.INITIAL && destinationCid.contentEquals(originalDcid) && !info.scid.contentEquals(originalDcid)) {
                destinationCid = info.scid
                log("recv: adopt server SCID as dcid = ${info.scid.toHex()}")
            }
            // Header protection: sample at info.packetNumberOffset + 4.
            val sampleOffset = info.packetNumberOffset + 4
            if (sampleOffset + 16 > datagramEnd) return -1
            val sample = bytes.copyOfRange(sampleOffset, sampleOffset + 16)
            val pnRegion = bytes.copyOfRange(info.packetNumberOffset, info.packetNumberOffset + 4)
            val (unprotectedFirst, unprotectedPn) = hp.remove(info.firstByte, pnRegion, sample, isLongHeader = true)
            val pnLength = unprotectedPn.size
            // Reconstruct full PN.
            val truncatedPn = ByteBuffer.wrap(unprotectedPn).let { pb ->
                when (pnLength) {
                    1 -> pb.get().toLong() and 0xFFL
                    2 -> pb.short.toLong() and 0xFFFFL
                    3 -> ((pb.get().toLong() and 0xFFL) shl 16) or
                         ((pb.get().toLong() and 0xFFL) shl 8) or
                         (pb.get().toLong() and 0xFFL)
                    4 -> pb.int.toLong() and 0xFFFFFFFFL
                    else -> error("unreachable")
                }
            }
            val pn = expandPn(truncatedPn, pnLength, cs.largestReceivedPn)
            // Decrypt payload via AEAD.
            val payloadStart = info.packetNumberOffset + pnLength
            val payloadEnd = info.packetNumberOffset + info.payloadLength.toInt()
            if (payloadEnd > datagramEnd) return -1
            val ciphertext = bytes.copyOfRange(payloadStart, payloadEnd)
            // AAD = header bytes from datagramStart through end of PN, with first byte and PN unprotected.
            val aad = bytes.copyOfRange(info.headerStartOffset, info.headerStartOffset + (payloadStart - info.headerStartOffset))
            aad[0] = unprotectedFirst
            // PN bytes in AAD should be unprotected too.
            System.arraycopy(unprotectedPn, 0, aad, info.packetNumberOffset - info.headerStartOffset, pnLength)
            val plaintext = try {
                cs.receive!!.decrypt(pn, ciphertext, aad)
            } catch (t: Throwable) {
                log("recv long: AEAD decrypt failed space=$space pn=$pn ct=${ciphertext.size}B: ${t.javaClass.simpleName}")
                return -1  // RFC 9001 §5.5: silently drop AEAD failures
            }
            log("recv long: decrypted space=$space pn=$pn payload=${plaintext.size}B")
            // Update largest received PN.
            if (pn > cs.largestReceivedPn) cs.largestReceivedPn = pn
            // Parse frames and dispatch.
            val frames = Frame.parseAll(ByteBuffer.wrap(plaintext))
            val ackElic = frames.any { it.ackEliciting }
            // Wake the sender promptly when the receiver crosses the
            // immediate-ACK threshold; without this the sender's idle
            // 20 ms poll bounds ACK latency to the same 20 ms, starving
            // peer's CC ack-clock under heavy RX (the QUIC-upload-in-
            // duplex regression — see SpaceRecovery.IMMEDIATE_ACK_THRESHOLD).
            if (cs.recovery.onPacketReceived(pn, ackElic)) {
                sendQueue.offer(QueuedSendWork.Tick)
            }
            dispatchFrames(space, frames)
            payloadEnd - datagramStart
        } catch (t: Throwable) {
            log("processLongPacket: ${t.javaClass.simpleName}: ${t.message}")
            -1
        }
    }

    private fun processShortPacket(bytes: ByteArray, datagramStart: Int, datagramEnd: Int, buf: ByteBuffer): Int {
        return try {
            val info = parseShortHeader(buf, dcidLength = sourceCid.size)
            val cs = oneRttSpace
            val hp = cs.receiveHp ?: return -1
            val sampleOffset = info.packetNumberOffset + 4
            if (sampleOffset + 16 > datagramEnd) return -1
            val sample = bytes.copyOfRange(sampleOffset, sampleOffset + 16)
            val pnRegion = bytes.copyOfRange(info.packetNumberOffset, info.packetNumberOffset + 4)
            val (unprotectedFirst, unprotectedPn) = hp.remove(info.firstByte, pnRegion, sample, isLongHeader = false)
            val pnLength = unprotectedPn.size
            val truncatedPn = ByteBuffer.wrap(unprotectedPn).let { pb ->
                when (pnLength) {
                    1 -> pb.get().toLong() and 0xFFL
                    2 -> pb.short.toLong() and 0xFFFFL
                    3 -> ((pb.get().toLong() and 0xFFL) shl 16) or
                         ((pb.get().toLong() and 0xFFL) shl 8) or
                         (pb.get().toLong() and 0xFFL)
                    4 -> pb.int.toLong() and 0xFFFFFFFFL
                    else -> error("unreachable")
                }
            }
            val pn = expandPn(truncatedPn, pnLength, cs.largestReceivedPn)
            // 1-RTT: payload extends from PN+pnLength to end of datagram.
            val payloadStart = info.packetNumberOffset + pnLength
            val ciphertext = bytes.copyOfRange(payloadStart, datagramEnd)
            val aad = bytes.copyOfRange(info.headerStartOffset, payloadStart)
            aad[0] = unprotectedFirst
            System.arraycopy(unprotectedPn, 0, aad, info.packetNumberOffset - info.headerStartOffset, pnLength)
            // Key update (RFC 9001 §6): if the Key Phase bit differs from
            // our current phase, the peer rotated keys — decrypt with the
            // next generation and, on success, adopt it for both
            // directions. quic-go initiates this mid-connection; not
            // following it dropped every subsequent packet (build 92:
            // recv_pn frozen while datagrams flooded).
            val pktKeyPhase = (unprotectedFirst.toInt() ushr 2) and 1
            val keyUpdate = pktKeyPhase != cs.keyPhase
            val protection = if (keyUpdate) (cs.nextReceiveProtection() ?: cs.receive!!) else cs.receive!!
            val plaintext = try {
                protection.decrypt(pn, ciphertext, aad)
            } catch (_: Throwable) {
                val n = statsDecryptFailures.incrementAndGet()
                if (n <= 12L || n % 200L == 0L) {
                    log("recv short: AEAD FAIL #$n pn=$pn keyphase=$pktKeyPhase(cur=${cs.keyPhase}) ct=${ciphertext.size}B recv_pn=${cs.largestReceivedPn}")
                }
                return -1
            }
            if (keyUpdate) {
                cs.commitKeyUpdate()
                log("recv: KEY UPDATE adopted at pn=$pn, new phase=${cs.keyPhase}")
            }
            if (pn > cs.largestReceivedPn) cs.largestReceivedPn = pn
            val frames = Frame.parseAll(ByteBuffer.wrap(plaintext))
            val ackElic = frames.any { it.ackEliciting }
            // See processLongPacket for why we wake the sender here: the
            // 1-RTT space carries application data, so this is where the
            // duplex-upload starvation actually manifested.
            if (cs.recovery.onPacketReceived(pn, ackElic)) {
                sendQueue.offer(QueuedSendWork.Tick)
            }
            dispatchFrames(PacketNumberSpace.ONE_RTT, frames)
            datagramEnd - datagramStart  // short header packets consume the rest of the datagram
        } catch (_: Throwable) {
            -1
        }
    }

    private fun dispatchFrames(space: PacketNumberSpace, frames: List<Frame>) {
        for (frame in frames) {
            when (frame) {
                is Padding, is Ping -> { /* nothing to do */ }
                is Ack -> {
                    val cs = spaceFor(space)
                    // Maintain the PN-encoding anchor (largest PN the peer has
                    // acknowledged). This was NEVER updated before — stuck at
                    // -1 — so emitPacketInSpace sized every sent PN off the
                    // absolute value rather than the in-flight range (RFC 9000
                    // §A.2) and could desync once many packets were outstanding.
                    if (frame.largestAcked > cs.largestAckedSentPn) cs.largestAckedSentPn = frame.largestAcked
                    val acked = cs.recovery.processAckFrame(frame, peerTransportParameters?.ackDelayExponent ?: 3)
                    val now = System.nanoTime()
                    // RFC 9002 §5: take the RTT sample ONLY from the largest
                    // newly-acked packet. Sampling every packet in the batch
                    // feeds the EWMA with old sentTimes when a big group is
                    // acked at once (build-93: cc.cwnd ballooned to 161 MiB
                    // because srtt drifted to ~6 s from such stale samples).
                    val largestAckedPacket = acked.maxByOrNull { it.packetNumber }
                    for (p in acked) {
                        val rtt = if (p === largestAckedPacket) now - p.sentTimeNanos else 0L
                        cc.onPacketAcked(p.sizeBytes, rtt)
                    }
                    // ACK progress resets the PTO timer + backoff so the probe
                    // only fires when the peer truly goes quiet on us.
                    if (acked.isNotEmpty()) {
                        lastAckProgressNanos = now
                        ptoBackoff = 0
                    }
                    val loss = cs.recovery.detectLost()
                    for (f in loss.framesToRetransmit) requeueLostFrame(space, f)
                    if (loss.bytesLost > 0) cc.onPacketLost(loss.bytesLost.toInt())
                    // Log incoming ACKs for the 1-RTT space only (Initial/
                    // Handshake are noisy during the brief handshake). Shows
                    // whether the server is acking our packets — particularly
                    // our PING keepalives. `acked_now` is how many of OUR sent
                    // packets this ACK newly covered.
                    if (verboseWire && space == PacketNumberSpace.ONE_RTT) {
                        log("recv ACK: largest=${frame.largestAcked} ranges=${frame.ranges.size} acked_now=${acked.size} cc.in_flight=${cc.bytesInFlight}")
                    }
                }
                is Crypto -> {
                    val tlsLevel = when (space) {
                        PacketNumberSpace.INITIAL -> TlsClient.Level.INITIAL
                        PacketNumberSpace.HANDSHAKE -> TlsClient.Level.HANDSHAKE
                        PacketNumberSpace.ONE_RTT -> TlsClient.Level.APPLICATION
                    }
                    val step = tls.processCryptoData(tlsLevel, frame.data)
                    handleTlsStep(step)
                }
                is StreamFrame -> {
                    statsStreamFrames.incrementAndGet()
                    val isNew = !streams.containsKey(frame.streamId)
                    val s = streams.getOrPut(frame.streamId) {
                        // Server-initiated stream.
                        val ns = Stream(
                            streamId = frame.streamId,
                            ourInitialMaxStreamData = ourTransportParameters.initialMaxStreamDataBidiRemote,
                            peerMaxStreamData = peerTransportParameters?.initialMaxStreamDataBidiLocal ?: 0L,
                            sendBufferMaxBytes = streamSendBufferMaxBytes,
                        )
                        ns.sendWakeup = { sendQueue.offer(QueuedSendWork.Tick) }
                        incomingServerStreams.offer(ns)
                        statsNewServerStreams.incrementAndGet()
                        ns
                    }
                    if (isNew) log("recv STREAM: NEW server stream id=${frame.streamId} peer_max=${s.peerMaxStreamData} queue_size=${incomingServerStreams.size}")
                    s.acceptStreamFrame(frame)
                    flow.onBytesReceived(frame.data.size)
                    if (verboseWire) log("recv STREAM: id=${frame.streamId} offset=${frame.offset} len=${frame.data.size} fin=${frame.fin} readOffset=${s.recvBuffer.readOffset}")
                }
                is MaxData -> {
                    flow.applyPeerMaxData(frame.maxData)
                    statsMaxDataRecv.incrementAndGet()
                    // The peer just widened our connection send window — a
                    // sender parked on flow control can resume now, so wake it.
                    sendQueue.offer(QueuedSendWork.Tick)
                    log("recv MAX_DATA: ${frame.maxData}")
                }
                is MaxStreamData -> {
                    streams[frame.streamId]?.applyMaxStreamData(frame.maxStreamData)
                    sendQueue.offer(QueuedSendWork.Tick)  // may unblock a stream's send
                    log("recv MAX_STREAM_DATA: id=${frame.streamId} max=${frame.maxStreamData}")
                }
                is MaxStreams -> { log("recv MAX_STREAMS: bidi=${frame.bidi} max=${frame.maxStreams}") }
                is HandshakeDone -> {
                    handshakeDone = true
                    // Discard Initial + Handshake keys per RFC 9001 §4.9.
                    initialSpace.send = null; initialSpace.receive = null
                    handshakeSpace.send = null; handshakeSpace.receive = null
                    log("recv HANDSHAKE_DONE")
                }
                is ConnectionClose -> {
                    log("recv CONNECTION_CLOSE: app=${frame.isApplicationError} errCode=${frame.errorCode} frameType=${frame.frameType} reason='${frame.reasonPhrase}'")
                    closed.set(true)
                }
                is NewToken -> { log("recv NEW_TOKEN: ${frame.token.size}B") }
                is StopSending -> {
                    // Peer asks us to stop sending on this stream (RFC 9000
                    // §3.5). Common cause: peer's downstream consumer
                    // disconnected and the bytes we still owe are wasted —
                    // typically what proxy-server-go does at the end of a
                    // download speedtest. We answer with a RESET_STREAM
                    // carrying the same error code and our current
                    // sendOffset as Final Size; queued SendBuffer bytes
                    // get discarded so workRemains drops and the connection
                    // can move on without waiting out a credit-pin.
                    //
                    // Critical for the duplex-upload regression: kwik
                    // handles this and quic-go appears to extend the
                    // connection-level MAX_DATA when it sees the
                    // RESET_STREAM (freeing the credit our finished
                    // download stream had reserved), so the subsequent
                    // upload phase's small TX bytes can go through.
                    val s = streams[frame.streamId]
                    if (s != null) {
                        val finalSize = s.resetSendBecausePeerStopSending()
                        pendingResetStreams.offer(
                            ResetStream(
                                streamId = frame.streamId,
                                applicationProtocolErrorCode = frame.applicationProtocolErrorCode,
                                finalSize = finalSize,
                            )
                        )
                        sendQueue.offer(QueuedSendWork.Tick)
                        log("recv STOP_SENDING: id=${frame.streamId} code=${frame.applicationProtocolErrorCode} → reset, finalSize=$finalSize")
                    } else {
                        log("recv STOP_SENDING: id=${frame.streamId} (no stream — dropped)")
                    }
                }
                is ResetStream -> {
                    // Peer aborted their send side on this stream (RFC 9000
                    // §3.5). Force-EOF our recv buffer so the bridge thread
                    // parked in input.read unblocks and exits; the stream
                    // teardown cascades from there.
                    streams[frame.streamId]?.acceptPeerResetStream(
                        frame.applicationProtocolErrorCode, frame.finalSize
                    )
                    log("recv RESET_STREAM: id=${frame.streamId} code=${frame.applicationProtocolErrorCode} finalSize=${frame.finalSize}")
                }
                else -> { log("recv frame (unhandled): ${frame.javaClass.simpleName}") }
            }
        }
    }

    private fun handleTlsStep(step: TlsClient.HandshakeStep) {
        // Install any newly available traffic secrets.
        for (ns in step.newSecrets) {
            val cs = when (ns.level) {
                TlsClient.Level.INITIAL -> initialSpace
                TlsClient.Level.HANDSHAKE -> handshakeSpace
                TlsClient.Level.APPLICATION -> oneRttSpace
            }
            when (ns.direction) {
                TlsClient.Direction.TX -> cs.installSendKeys(ns.trafficSecret)
                TlsClient.Direction.RX -> cs.installReceiveKeys(ns.trafficSecret)
            }
            log("tls: new secret level=${ns.level} dir=${ns.direction}")
        }
        step.peerTransportParameters?.let { tp ->
            peerTransportParameters = tp
            flow.applyPeerMaxData(tp.initialMaxData)
            log("tls: peer TPs received," +
                " max_data=${tp.initialMaxData}" +
                " max_streams_bidi=${tp.initialMaxStreamsBidi}" +
                " max_streams_uni=${tp.initialMaxStreamsUni}" +
                " sd_bidi_local=${tp.initialMaxStreamDataBidiLocal}" +
                " sd_bidi_remote=${tp.initialMaxStreamDataBidiRemote}" +
                " sd_uni=${tp.initialMaxStreamDataUni}" +
                " max_idle_ms=${tp.maxIdleTimeoutMs}")
        }
        if (step.handshakeComplete) log("tls: handshake complete")
        for ((level, bytes) in step.outgoing) {
            val space = when (level) {
                TlsClient.Level.INITIAL -> PacketNumberSpace.INITIAL
                TlsClient.Level.HANDSHAKE -> PacketNumberSpace.HANDSHAKE
                TlsClient.Level.APPLICATION -> PacketNumberSpace.ONE_RTT
            }
            queueCryptoData(space, bytes)
        }
        sendQueue.offer(QueuedSendWork.Tick)
    }

    // ── Internals: send loop ──────────────────────────────────

    /** CRYPTO frame data we owe to the wire, per space. */
    private val pendingCrypto = ConcurrentHashMap<PacketNumberSpace, ByteArray>()

    /** Timestamp (ns) of last ack-eliciting packet we sent in 1-RTT.
     *  Used by the PING keepalive heuristic — if we go too long without
     *  sending anything ack-eliciting, the server's application-layer
     *  routing may classify us as idle and stop routing tunnels through
     *  us (this matched kwik's `keepAlive(20)` behavior — without it,
     *  proxy-server-go appears to drop us from the active agent pool
     *  even though the QUIC connection itself is healthy). */
    @Volatile private var lastAckElicitingSendNanos: Long = System.nanoTime()
    private val keepAliveIntervalNanos: Long = 15_000_000_000L  // 15 s

    // ── Loss recovery: retransmit queue + PTO ─────────────────
    //
    // Frames to retransmit AS-IS, preserving their original stream
    // offsets. CRITICAL: a lost STREAM frame must be re-emitted with
    // its ORIGINAL offset — the old requeueLostFrame wrote the bytes
    // back through SendBuffer, where pollSendFrame then stamped a
    // fresh (higher) offset, punching a hole in the byte stream and
    // stalling the receiver. Drained ahead of new STREAM data.
    private val pendingRetransmit =
        java.util.concurrent.ConcurrentLinkedQueue<Pair<PacketNumberSpace, Frame>>()
    /** RESET_STREAM frames the receive thread has queued for emission
     *  in response to a peer STOP_SENDING. Drained on the sender's
     *  control-priority tick so they go out promptly and free the
     *  flow-control credit the dead stream had pinned. */
    private val pendingResetStreams =
        java.util.concurrent.ConcurrentLinkedQueue<ResetStream>()
    /** Wall-clock of the last ACK that made progress (advanced the
     *  largest-acked / acked new packets). The PTO timer measures
     *  from here: if ack-eliciting data is outstanding and no ACK
     *  progresses for the PTO interval, we probe-retransmit. */
    @Volatile private var lastAckProgressNanos: Long = System.nanoTime()
    /** Exponential PTO backoff exponent; reset to 0 on ACK progress. */
    @Volatile private var ptoBackoff: Int = 0

    // ── Send pacing (token bucket) ────────────────────────────
    //
    // Why a token bucket instead of Brutal CC's per-packet
    // `nextSendTimeNanos` gate: that gate requires the sender to
    // wait precisely ~88 µs between 1200-byte packets to hit
    // 100 Mbps. Android's sleep/park granularity is coarse (often
    // 1-15 ms), so per-packet waiting either over-sleeps (→ a tiny
    // fraction of target throughput) or, as the OLD drainStreams
    // did, computed `Thread.sleep(88000 / 1_000_000)` == sleep(0)
    // and so sent exactly ONE frame per 20 ms sender tick — about
    // 440 Kbps for the WHOLE connection, which is why the in-house
    // QUIC "loaded nothing" while kwik worked. A wall-clock-refilled
    // byte budget is immune to sleep granularity (it measures
    // elapsed time, not sleep duration) and mirrors quic-go's
    // pacer.Budget() far more closely than the timestamp gate. Only
    // the sender thread touches these, so no synchronization.
    private var paceBudgetBytes: Long = 0L
    private var lastPaceRefillNanos: Long = System.nanoTime()
    /** Burst cap (bytes). Bounds how much a long-idle connection may
     *  dump at once and bounds the latency control frames wait behind
     *  one drain pass. ~5 ms of data at 100 Mbps. */
    private val paceMaxBurstBytes: Long = 64 * 1024L
    /** Per-packet wire overhead (short header + PN + AEAD tag + STREAM
     *  frame header) charged against the budget so we pace wire bytes,
     *  not just payload. */
    private val streamFrameWireOverhead = 40
    /** cwnd-gate probe size — one full ~1200-byte packet. */
    private val probePacketSize = 1200
    /** Max STREAM-frame payload per packet (room for header + tag in a
     *  1200-byte datagram). */
    private val maxStreamFramePayload = 1100L

    /** Gate for high-frequency wire logs (per-packet send/recv,
     *  per-ACK, per-STREAM-frame). OFF by default: at 100 Mbps these
     *  fire ~10k×/s and both flood logcat and throttle the sender. Flip
     *  to true only for deep wire debugging — the 5-second `stats:`
     *  line stays on and is the normal diagnostic. */
    private val verboseWire = false

    /** Refill [paceBudgetBytes] from wall-clock elapsed time at the
     *  CC's target rate, capped at [paceMaxBurstBytes]. */
    private fun refillPaceBudget() {
        val now = System.nanoTime()
        val elapsed = now - lastPaceRefillNanos
        if (elapsed <= 0L) return
        lastPaceRefillNanos = now
        val add = cc.targetBytesPerSecond * elapsed / 1_000_000_000L
        if (add > 0L) paceBudgetBytes = (paceBudgetBytes + add).coerceAtMost(paceMaxBurstBytes)
    }

    /** True if any stream still has queued payload or an unsent FIN. */
    private fun anyStreamHasData(): Boolean = streams.values.any {
        it.sendBuffer.queuedBytes > 0 || (it.sendBuffer.closed && !it.sentFin)
    }

    /** True if any packet-number space has ack-eliciting packets in
     *  flight — gates the PTO timer so it idles when nothing's pending. */
    private fun anyAckElicitingOutstanding(): Boolean =
        initialSpace.recovery.hasAckEliciting() ||
        handshakeSpace.recovery.hasAckEliciting() ||
        oneRttSpace.recovery.hasAckEliciting()

    private fun queueCryptoData(space: PacketNumberSpace, data: ByteArray) {
        pendingCrypto.merge(space, data) { a, b -> a + b }
    }

    private fun requeueLostFrame(space: PacketNumberSpace, f: Frame) {
        // Only CRYPTO, STREAM, and RESET_STREAM carry data worth
        // retransmitting; other frame types are state-driven and
        // re-emitted by their owning subsystem. Re-emit the ORIGINAL
        // frame verbatim (same offset and bytes) — do NOT round-trip
        // through SendBuffer, which would reassign a fresh offset and
        // corrupt the stream. RESET_STREAM is added so the credit-pin
        // recovery survives a lost packet; without it, a dropped
        // RESET_STREAM would leave peer waiting on bytes we'll never
        // resend and the connection would stay wedged.
        when (f) {
            is Crypto -> pendingRetransmit.offer(space to f)
            is StreamFrame -> pendingRetransmit.offer(space to f)
            is ResetStream -> pendingRetransmit.offer(space to f)
            else -> {}
        }
    }

    private fun senderLoop() {
        log("senderLoop: started")
        var lastStatsLogNanos = System.nanoTime()
        // Adaptive wait: short (2 ms) while streams still have buffered
        // data so the pace bucket keeps flowing; long (20 ms) when idle.
        // A write to any stream offers a Tick that cuts the idle wait
        // short, so the first byte of a new response goes out promptly
        // instead of waiting up to 20 ms.
        var nextPollMs = 20L
        while (!closed.get()) {
            try {
                sendQueue.poll(nextPollMs, TimeUnit.MILLISECONDS)

                // Periodic stats — every 5 seconds. Helps see at a
                // glance whether we're getting STREAM frames at all,
                // or if new server-initiated streams arrive.
                val now = System.nanoTime()
                if (now - lastStatsLogNanos > 5_000_000_000L) {
                    var totalQueued = 0L
                    for (s in streams.values) totalQueued += s.sendBuffer.queuedBytes
                    logStat("stats: datagrams=${statsDatagrams.get()}" +
                        " stream_frames=${statsStreamFrames.get()}" +
                        " new_server_streams=${statsNewServerStreams.get()}" +
                        " decrypt_fails=${statsDecryptFailures.get()}" +
                        " db_sent=${statsDataBlockedSent.get()}" +
                        " md_recv=${statsMaxDataRecv.get()}" +
                        " accept_queue=${incomingServerStreams.size}" +
                        " streams_map=${streams.size}" +
                        " send_buf_queued=$totalQueued" +
                        " cc.in_flight=${cc.bytesInFlight}" +
                        " cc.cwnd=${cc.congestionWindow}" +
                        " flow.send_credit=${flow.sendCredit()}" +
                        " 1rtt[sent_pn=${oneRttSpace.nextSendPn - 1}" +
                        " acked_pn=${oneRttSpace.recovery.largestAckedSent()}" +
                        " recv_pn=${oneRttSpace.largestReceivedPn}" +
                        " outstanding=${oneRttSpace.recovery.outstandingCount()}" +
                        " pto_backoff=$ptoBackoff]")
                    lastStatsLogNanos = now
                }

                // Priority 1: Crypto data still owed at any level.
                for (space in listOf(PacketNumberSpace.INITIAL, PacketNumberSpace.HANDSHAKE, PacketNumberSpace.ONE_RTT)) {
                    val pending = pendingCrypto[space]
                    if (pending != null && pending.isNotEmpty()) {
                        emitCryptoPacket(space, pending)
                        pendingCrypto.remove(space)
                    }
                }

                // Priority 2: ACK frames — but ONLY when we actually
                // owe one (received an ack-eliciting packet since the
                // last ACK). Previously we emitted an ACK every tick
                // unconditionally → ~50 ACK-only packets/sec flood.
                //
                // Report the ACTUAL ack-delay (build 99 fix): we used
                // to pass 0L unconditionally, which made peer's quic-go
                // subtract 0 from its RTT samples — so when our sender
                // sat idle for 20 ms before emitting an ACK, peer's
                // RTT_min inflated by 20 ms. Peer's BBR/CUBIC then
                // throttled cwnd growth in the upload direction (the
                // duplex regression: QUIC upload tanked below TCP). A
                // single atomic critical section yields both the
                // emit-decision and the delay snapshot so no packet
                // arrival can race between consume and read.
                for (space in listOf(PacketNumberSpace.INITIAL, PacketNumberSpace.HANDSHAKE, PacketNumberSpace.ONE_RTT)) {
                    val cs = spaceFor(space)
                    if (!cs.ready()) continue
                    val emission = cs.recovery.consumeAndTakeAckDelay(System.nanoTime())
                    if (!emission.shouldEmit) continue
                    val ack = cs.recovery.buildAckFrame(emission.ackDelayNanos,
                        peerTransportParameters?.ackDelayExponent ?: 3)
                    if (ack != null) {
                        emitPacketInSpace(space, listOf(ack), ackEliciting = false, inFlight = false)
                    }
                }

                // Priority 3: connection-level MAX_DATA window-update.
                flow.shouldAdvertiseMaxData()?.let { newMax ->
                    if (oneRttSpace.ready()) {
                        emitOneRttPacket(listOf(MaxData(newMax)))
                    }
                }

                // Priority 3b: per-stream MAX_STREAM_DATA window-updates.
                // The connection-level MAX_DATA above is necessary but NOT
                // sufficient — the peer is bounded by BOTH the connection
                // window and each individual stream's window. Without these
                // per-stream bumps a download stalls once the peer fills the
                // initial per-stream credit (fine for a page load, fatal for
                // a download speed test). Emitted on this same control-
                // priority tick — ahead of STREAM draining — so a busy
                // data-sending pass can never starve credit extension.
                if (oneRttSpace.ready()) {
                    for ((id, stream) in streams) {
                        stream.maybeExtendRecvWindow()?.let { newMax ->
                            log("send MAX_STREAM_DATA: id=$id max=$newMax")
                            emitOneRttPacket(listOf(MaxStreamData(id, newMax)))
                        }
                    }
                }

                // Priority 3b': RESET_STREAM in response to peer's
                // STOP_SENDING (queued from receive thread). Goes out ahead
                // of STREAM data so peer extends MAX_DATA promptly — this is
                // the kwik-equivalent recovery path that unwedges the
                // connection after a download speedtest. Each ResetStream
                // is ack-eliciting, so the retransmit/PTO machinery covers
                // packet loss.
                if (oneRttSpace.ready()) {
                    while (true) {
                        val rs = pendingResetStreams.poll() ?: break
                        emitOneRttPacket(listOf(rs))
                    }
                }

                // Priority 3c: PTO — recover from TAIL loss. Threshold loss
                // detection needs 3 LATER packets acked; a packet lost at the
                // end of a burst (nothing sent after it) is never detected, so
                // without this the stream stalls forever and cc.in_flight never
                // drains (exactly the build-91 freeze, and the repeated
                // handshake timeouts when a ClientHello/Finished is lost). If
                // ack-eliciting data is outstanding and no ACK has progressed
                // for the PTO interval, retransmit the oldest unacked packet's
                // frames (with their ORIGINAL offsets), backing off each time.
                if (anyAckElicitingOutstanding()) {
                    val ptoNanos = maxOf(cc.smoothedRttNanos * 3, 250_000_000L) shl ptoBackoff.coerceAtMost(6)
                    if (System.nanoTime() - lastAckProgressNanos > ptoNanos) {
                        var probed = false
                        for (sp in listOf(PacketNumberSpace.INITIAL, PacketNumberSpace.HANDSHAKE, PacketNumberSpace.ONE_RTT)) {
                            val cs = spaceFor(sp)
                            if (!cs.ready()) continue
                            val r = cs.recovery.takeOldestAckElicitingForRetransmit() ?: continue
                            if (r.inFlight) cc.onPacketLost(r.sizeBytes)
                            var enq = false
                            for (f in r.frames) if (
                                f is Crypto || f is StreamFrame || f is ResetStream
                            ) {
                                pendingRetransmit.offer(sp to f); enq = true
                            }
                            // Nothing concrete to resend (e.g. a PING-only
                            // packet) — send a fresh PING to elicit an ACK.
                            if (!enq) emitPacketInSpace(sp, listOf(Ping), ackEliciting = true, inFlight = true)
                            probed = true
                        }
                        if (probed) {
                            ptoBackoff++
                            lastAckProgressNanos = System.nanoTime()
                            log("PTO probe (backoff=$ptoBackoff)")
                        }
                    }
                }

                // Priority 3d: drain the retransmit queue — re-emit lost/probe
                // frames verbatim (original offsets) in fresh packets.
                while (true) {
                    val rt = pendingRetransmit.poll() ?: break
                    val cs = spaceFor(rt.first)
                    if (!cs.ready()) continue  // space discarded (e.g. post-handshake Initial)
                    emitPacketInSpace(rt.first, listOf(rt.second), ackEliciting = true, inFlight = true)
                }

                // Priority 4: STREAM frames, paced via the token bucket.
                val workRemains = if (oneRttSpace.ready()) drainStreams() else false

                // Priority 5: PING keepalive. ACK frames are not
                // ack-eliciting; if we send only ACKs for too long, the
                // peer's application layer may consider us idle. Mirrors
                // kwik's `keepAlive(20)` — without this, proxy-server-go
                // observed agents stuck "connected but never routed to".
                if (oneRttSpace.ready() &&
                    System.nanoTime() - lastAckElicitingSendNanos > keepAliveIntervalNanos) {
                    log("send PING (keepalive after ${(System.nanoTime() - lastAckElicitingSendNanos) / 1_000_000}ms idle)")
                    emitOneRttPacket(listOf(Ping))
                }

                // Keep topping up the pace bucket while data remains;
                // fall back to the idle cadence otherwise.
                nextPollMs = if (workRemains) 2L else 20L
            } catch (t: Throwable) {
                if (!closed.get()) {
                    // SURFACE these — silent swallow hides bugs like
                    // the HP-sample-out-of-bounds that ate our PING
                    // emissions for hours of debugging.
                    log("senderLoop: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        log("senderLoop: exit")
    }

    /**
     * Drain buffered STREAM data onto the wire, paced by the token
     * bucket and gated by congestion + flow control. Returns true if
     * any stream still has data we couldn't send this pass (budget
     * spent, cwnd full, or flow-control-blocked) so the sender loop
     * tightens its poll interval and comes back promptly.
     *
     * Round-robin across streams: one frame per stream per inner pass,
     * looping until the pace budget is spent or no stream can make
     * progress. The OLD version sent ~1 frame per 20 ms tick (see the
     * pacing note above) — this one sustains the CC's target rate.
     */
    private fun drainStreams(): Boolean {
        refillPaceBudget()
        outer@ while (paceBudgetBytes > 0L) {
            var sentThisPass = false
            for ((_, stream) in streams) {
                val hasData = stream.sendBuffer.queuedBytes > 0 ||
                              (stream.sendBuffer.closed && !stream.sentFin)
                if (!hasData) continue
                // Congestion-window gate (bytes-in-flight ceiling). cwnd
                // is connection-wide, so once it's full no stream sends.
                if (cc.bytesInFlight + probePacketSize > cc.congestionWindow) break@outer
                // Connection-level flow control: budget the poll by the
                // available credit so we NEVER drain more from the send
                // buffer than we're allowed to send. The OLD code polled
                // first and then `break`-ed on insufficient credit,
                // silently DROPPING the already-dequeued frame — a hole in
                // the stream's byte sequence that corrupts the transfer.
                // budget==0 still lets a closing stream emit a zero-length
                // FIN (carries no data, so needs no flow credit).
                val connCredit = flow.sendCredit()
                val budget = connCredit.coerceIn(0L, maxStreamFramePayload).toInt()
                val frame = stream.pollSendFrame(budget) ?: continue  // per-stream/conn flow-blocked
                flow.onBytesSent(frame.data.size)
                emitOneRttPacket(listOf(frame))
                paceBudgetBytes -= (frame.data.size + streamFrameWireOverhead)
                sentThisPass = true
                if (verboseWire) log("send STREAM: id=${frame.streamId} offset=${frame.offset} len=${frame.data.size} fin=${frame.fin}")
                if (paceBudgetBytes <= 0L) break@outer
            }
            if (!sentThisPass) break  // no stream could make progress this pass
        }
        val workRemains = anyStreamHasData()
        // If we still have data but the peer's connection-level window is
        // exhausted, tell the peer (RFC 9000 §19.12). proxy-server-go can
        // sit on stale MAX_DATA for minutes after a heavy download burst —
        // build 94 captured this: after the speedtest's download phase,
        // flow.send_credit stayed at 0 for 70+ seconds (forever, really)
        // and upload sat at 0 Mbps. DATA_BLOCKED nudges the proxy to drain
        // its forward buffer and send us a fresh MAX_DATA. Emitted at most
        // once per unique blocking event so we don't spam.
        if (workRemains && flow.sendCredit() <= 0L) {
            flow.shouldEmitDataBlocked()?.let { limit ->
                statsDataBlockedSent.incrementAndGet()
                log("send DATA_BLOCKED: $limit (sendCredit exhausted)")
                emitOneRttPacket(listOf(DataBlocked(limit)))
            }
        }
        return workRemains
    }

    // ── Packet emission ────────────────────────────────────────

    private fun emitCryptoPacket(space: PacketNumberSpace, cryptoBytes: ByteArray) {
        val cs = spaceFor(space)
        if (!cs.ready()) return
        val frame = Crypto(offset = 0, data = cryptoBytes)
        emitPacketInSpace(space, listOf(frame), ackEliciting = true, inFlight = true)
    }

    private fun emitOneRttPacket(frames: List<Frame>) {
        emitPacketInSpace(PacketNumberSpace.ONE_RTT, frames, ackEliciting = frames.any { it.ackEliciting }, inFlight = true)
    }

    private fun emitPacketInSpace(space: PacketNumberSpace, frames: List<Frame>, ackEliciting: Boolean, inFlight: Boolean) {
        val cs = spaceFor(space)
        if (!cs.ready()) return
        val pn = cs.nextPn()
        val pnLen = PacketNumber.encodingLength(pn, cs.largestAckedSentPn)

        // Build payload.
        var payloadSize = 0
        for (f in frames) payloadSize += f.encodedSize()
        // Initial packets MUST be padded to ≥1200 bytes total UDP
        // payload (RFC 9000 §14.1). Target 1240 to give 40-byte
        // headroom over the rough header-size estimate; sub-1200 →
        // server silently drops.
        val initialPadding = if (space == PacketNumberSpace.INITIAL) {
            val totalSoFar = payloadSize + 16 /* AEAD tag */ + pnLen + roughHeaderSize(space)
            maxOf(1240 - totalSoFar, 0)
        } else 0
        // Header-protection sample minimum (RFC 9001 §5.4.2): the
        // sample is taken at `pn_offset + 4` and is 16 bytes long, so
        // the packet must extend at least `pn_offset + 4 + 16` bytes
        // total. That implies `pnLen + payload_with_tag >= 4 + 16 = 20`,
        // i.e. plaintext payload must be at least `20 - 16 - pnLen` =
        // `4 - pnLen + (pnLen extras)`. For pnLen=1..4, plaintext must
        // be ≥ `4` bytes (we round up to 4 — overpad by a byte or two
        // for shorter PN never hurts). Without this, PING-only or
        // tiny ACK-only packets fail copyOfRange when sampling.
        val hpSamplePadding = if (initialPadding == 0) {
            maxOf(4 - payloadSize, 0)
        } else 0
        val totalExtraPadding = initialPadding + hpSamplePadding
        val paddedFrames = if (totalExtraPadding > 0) frames + Padding(totalExtraPadding) else frames

        // Encode payload.
        val payloadBuf = ByteBuffer.allocate(2048)
        for (f in paddedFrames) f.encode(payloadBuf)
        val payloadLen = payloadBuf.position()
        val payload = ByteArray(payloadLen)
        System.arraycopy(payloadBuf.array(), 0, payload, 0, payloadLen)

        // Encode header.
        val headerBytes: ByteArray
        val pnOffset: Int
        if (space == PacketNumberSpace.ONE_RTT) {
            val shdr = encodeShortHeader(destinationCid, pnLen, keyPhase = cs.keyPhase == 1)
            headerBytes = shdr
            pnOffset = shdr.size
        } else {
            val ptype = when (space) {
                PacketNumberSpace.INITIAL -> LongPacketType.INITIAL
                PacketNumberSpace.HANDSHAKE -> LongPacketType.HANDSHAKE
                else -> error("unreachable")
            }
            val enc = encodeLongHeader(
                type = ptype,
                version = PacketWire.QUIC_V1,
                dcid = destinationCid,
                scid = sourceCid,
                token = ByteArray(0),
                packetNumberLength = pnLen,
                payloadLengthIncludingPnAndTag = (pnLen + payloadLen + 16).toLong(),
            )
            headerBytes = enc.bytes
            pnOffset = enc.packetNumberOffset
        }

        // Compose AAD = headerBytes + raw PN bytes.
        val pnBytes = ByteBuffer.allocate(pnLen).also { PacketNumber.encode(pn, pnLen, it) }.array()
        val aad = ByteArray(headerBytes.size + pnLen)
        System.arraycopy(headerBytes, 0, aad, 0, headerBytes.size)
        System.arraycopy(pnBytes, 0, aad, headerBytes.size, pnLen)

        // AEAD encrypt.
        val ciphertext = cs.send!!.encrypt(pn, payload, aad)

        // Apply header protection (sample at pnOffset + 4 within full packet).
        val fullPacket = ByteArray(headerBytes.size + pnLen + ciphertext.size)
        System.arraycopy(headerBytes, 0, fullPacket, 0, headerBytes.size)
        System.arraycopy(pnBytes, 0, fullPacket, headerBytes.size, pnLen)
        System.arraycopy(ciphertext, 0, fullPacket, headerBytes.size + pnLen, ciphertext.size)

        val sampleOffset = pnOffset + 4
        val sample = fullPacket.copyOfRange(sampleOffset, sampleOffset + 16)
        val (protectedFirst, protectedPn) = cs.sendHp!!.apply(
            fullPacket[0], pnBytes, sample, isLongHeader = space != PacketNumberSpace.ONE_RTT
        )
        fullPacket[0] = protectedFirst
        System.arraycopy(protectedPn, 0, fullPacket, pnOffset, pnLen)

        // Track sent.
        val nowNanos = System.nanoTime()
        cs.recovery.onPacketSent(SpaceRecovery.SentPacket(
            packetNumber = pn,
            sentTimeNanos = nowNanos,
            sizeBytes = fullPacket.size,
            ackEliciting = ackEliciting,
            inFlight = inFlight,
            frames = frames,
        ))
        if (inFlight) cc.onPacketSent(fullPacket.size)
        // Update the keepalive heuristic: PINGs, STREAM, CRYPTO, etc.
        // are ack-eliciting and reset the idle counter. Pure-ACK
        // packets don't.
        if (ackEliciting && space == PacketNumberSpace.ONE_RTT) {
            lastAckElicitingSendNanos = nowNanos
        }

        // Send on wire.
        try {
            socket.send(DatagramPacket(fullPacket, fullPacket.size, resolvedAddress))
            if (verboseWire) log("send: space=$space pn=$pn size=${fullPacket.size}B frames=${frames.joinToString { it.javaClass.simpleName }}")
        } catch (t: Throwable) {
            log("send: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun roughHeaderSize(space: PacketNumberSpace): Int = when (space) {
        // Long header fixed parts: 1 (first) + 4 (version) + 1 (dcidLen)
        // + dcid + 1 (scidLen) + scid + varint(length). Initial adds 1
        // byte for the (empty) token length varint. We intentionally
        // UNDER-estimate the length varint at 2 bytes (it's typically 2
        // for our packet sizes around 1200, but can be 4 if payload >
        // 16383); under-estimation makes us over-pad rather than under-
        // pad — Initial packets MUST be ≥1200 bytes per RFC 9000 §14.1
        // or the server silently drops them.
        PacketNumberSpace.INITIAL -> 1 + 4 + 1 + destinationCid.size + 1 + sourceCid.size + 1 + 2
        PacketNumberSpace.HANDSHAKE -> 1 + 4 + 1 + destinationCid.size + 1 + sourceCid.size + 2
        PacketNumberSpace.ONE_RTT -> 1 + destinationCid.size
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun randomCid(length: Int): ByteArray = ByteArray(length).also { SecureRandom().nextBytes(it) }

    private fun expandPn(truncated: Long, length: Int, largestRecvd: Long): Long {
        if (largestRecvd < 0) return truncated
        val pnWindow = 1L shl (length * 8)
        val pnHalfWindow = pnWindow / 2
        val pnMask = pnWindow - 1
        val expected = largestRecvd + 1
        val candidate = (expected and pnMask.inv()) or truncated
        return when {
            candidate <= expected - pnHalfWindow && candidate < (1L shl 62) - pnWindow -> candidate + pnWindow
            candidate > expected + pnHalfWindow && candidate >= pnWindow -> candidate - pnWindow
            else -> candidate
        }
    }
}
