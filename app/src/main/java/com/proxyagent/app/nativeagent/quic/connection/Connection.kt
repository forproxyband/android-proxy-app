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
 *  - Key updates: not implemented; long-lived connections that
 *    exceed the AEAD usage limit will fail.
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

    private val tls = TlsClient(serverHost, alpn, ourTransportParameters)
    private val cc = BrutalCongestionControl(ccTargetMbps)
    private val flow = ConnectionFlowControl(
        initialPeerMaxData = 0L,  // will be set from peer's TP
        initialOurMaxData = ourTransportParameters.initialMaxData,
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

    private val socket = DatagramSocket()
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

    /** Establish the connection. Blocks until handshake completes
     *  or a fatal error occurs. */
    fun connect(timeoutMs: Long) {
        log("connect: dcid=${originalDcid.toHex()} scid=${sourceCid.toHex()} timeout=${timeoutMs}ms")
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
        )
        streams[id] = s
        return s
    }

    /** Block waiting for a server-initiated stream (each tunnel
     *  arrives as one). Returns null if connection closes. */
    fun acceptStream(timeoutMs: Long = Long.MAX_VALUE): Stream? {
        val s = if (timeoutMs == Long.MAX_VALUE) incomingServerStreams.take()
                else incomingServerStreams.poll(timeoutMs, TimeUnit.MILLISECONDS)
        return s
    }

    /** Tear down the connection. Best-effort — sends a
     *  CONNECTION_CLOSE then closes the socket. */
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
                log("recv: pkt#$packetCount size=${pkt.length} firstByte=0x${(buf[0].toInt() and 0xFF).toString(16)}")
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

    private fun processLongPacket(bytes: ByteArray, datagramStart: Int, datagramEnd: Int, buf: ByteBuffer): Int {
        return try {
            val info = parseLongHeader(buf, datagramEnd)
            val space = info.type.toPacketNumberSpace()
            val cs = spaceFor(space)
            val hp = cs.receiveHp ?: return -1  // we don't have keys yet (shouldn't happen for Initial)
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
            cs.recovery.onPacketReceived(pn, ackElic)
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
            val plaintext = try {
                cs.receive!!.decrypt(pn, ciphertext, aad)
            } catch (_: Throwable) { return -1 }
            if (pn > cs.largestReceivedPn) cs.largestReceivedPn = pn
            val frames = Frame.parseAll(ByteBuffer.wrap(plaintext))
            val ackElic = frames.any { it.ackEliciting }
            cs.recovery.onPacketReceived(pn, ackElic)
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
                    val acked = spaceFor(space).recovery.processAckFrame(frame, peerTransportParameters?.ackDelayExponent ?: 3)
                    val now = System.nanoTime()
                    for (p in acked) {
                        cc.onPacketAcked(p.sizeBytes, now - p.sentTimeNanos)
                    }
                    val loss = spaceFor(space).recovery.detectLost()
                    for (f in loss.framesToRetransmit) requeueLostFrame(space, f)
                    if (loss.bytesLost > 0) cc.onPacketLost(loss.bytesLost.toInt())
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
                    val s = streams.getOrPut(frame.streamId) {
                        // Server-initiated stream.
                        val ns = Stream(
                            streamId = frame.streamId,
                            ourInitialMaxStreamData = ourTransportParameters.initialMaxStreamDataBidiRemote,
                            peerMaxStreamData = peerTransportParameters?.initialMaxStreamDataBidiLocal ?: 0L,
                        )
                        incomingServerStreams.offer(ns)
                        ns
                    }
                    s.acceptStreamFrame(frame)
                    flow.onBytesReceived(frame.data.size)
                }
                is MaxData -> flow.applyPeerMaxData(frame.maxData)
                is MaxStreamData -> streams[frame.streamId]?.applyMaxStreamData(frame.maxStreamData)
                is MaxStreams -> { /* TODO: track peer's stream limit */ }
                is HandshakeDone -> {
                    handshakeDone = true
                    // Discard Initial + Handshake keys per RFC 9001 §4.9.
                    initialSpace.send = null; initialSpace.receive = null
                    handshakeSpace.send = null; handshakeSpace.receive = null
                }
                is ConnectionClose -> {
                    closed.set(true)
                }
                is NewToken -> { /* discard */ }
                else -> { /* ignore informational frames */ }
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
            log("tls: peer TPs received, peer_max_data=${tp.initialMaxData}")
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

    private fun queueCryptoData(space: PacketNumberSpace, data: ByteArray) {
        pendingCrypto.merge(space, data) { a, b -> a + b }
    }

    private fun requeueLostFrame(space: PacketNumberSpace, f: Frame) {
        // Simplification: only re-queue CRYPTO and STREAM frames.
        // Other frame types are state-driven and get re-emitted
        // naturally by their owning subsystem.
        when (f) {
            is Crypto -> queueCryptoData(space, f.data)
            is StreamFrame -> streams[f.streamId]?.sendBuffer?.write(f.data)
            else -> {}
        }
    }

    private fun senderLoop() {
        while (!closed.get()) {
            try {
                sendQueue.poll(20, TimeUnit.MILLISECONDS)  // wait up to 20ms

                // Priority 1: Crypto data still owed at any level.
                for (space in listOf(PacketNumberSpace.INITIAL, PacketNumberSpace.HANDSHAKE, PacketNumberSpace.ONE_RTT)) {
                    val pending = pendingCrypto[space]
                    if (pending != null && pending.isNotEmpty()) {
                        emitCryptoPacket(space, pending)
                        pendingCrypto.remove(space)
                    }
                }

                // Priority 2: ACK frames where ack-eliciting packets are outstanding.
                for (space in listOf(PacketNumberSpace.INITIAL, PacketNumberSpace.HANDSHAKE, PacketNumberSpace.ONE_RTT)) {
                    val cs = spaceFor(space)
                    if (!cs.ready()) continue
                    val ack = cs.recovery.buildAckFrame(0L, peerTransportParameters?.ackDelayExponent ?: 3)
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

                // Priority 4: STREAM frames, paced.
                if (oneRttSpace.ready()) drainStreams()
            } catch (t: Throwable) {
                if (!closed.get()) {
                    // TODO: log via callback
                }
            }
        }
    }

    private fun drainStreams() {
        // Round-robin streams; each gets one packet's worth per pass.
        for ((_, stream) in streams) {
            val budget = 1100  // approx room for one STREAM frame's payload in a 1200-byte packet
            if (!cc.canSendNow(budget)) {
                val wait = cc.waitNanos(budget)
                if (wait > 0 && wait != Long.MAX_VALUE) {
                    try { Thread.sleep(wait / 1_000_000L) } catch (_: InterruptedException) {}
                }
                if (!cc.canSendNow(budget)) break
            }
            val frame = stream.pollSendFrame(budget) ?: continue
            if (!flow.canSend(frame.data.size)) break
            flow.onBytesSent(frame.data.size)
            emitOneRttPacket(listOf(frame))
        }
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
        // Initial packets MUST be padded to min 1200 bytes total.
        val initialPadding = if (space == PacketNumberSpace.INITIAL) {
            val totalSoFar = payloadSize + 16 /* AEAD tag */ + pnLen + roughHeaderSize(space)
            maxOf(1200 - totalSoFar, 0)
        } else 0
        val paddedFrames = if (initialPadding > 0) frames + Padding(initialPadding) else frames

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
            val shdr = encodeShortHeader(destinationCid, pnLen)
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

        // Send on wire.
        try {
            socket.send(DatagramPacket(fullPacket, fullPacket.size, resolvedAddress))
            log("send: space=$space pn=$pn size=${fullPacket.size}B frames=${frames.joinToString { it.javaClass.simpleName }}")
        } catch (t: Throwable) {
            log("send: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun roughHeaderSize(space: PacketNumberSpace): Int = when (space) {
        PacketNumberSpace.INITIAL -> 1 + 4 + 1 + destinationCid.size + 1 + sourceCid.size + 1 + 4  // ~rough
        PacketNumberSpace.HANDSHAKE -> 1 + 4 + 1 + destinationCid.size + 1 + sourceCid.size + 4
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
