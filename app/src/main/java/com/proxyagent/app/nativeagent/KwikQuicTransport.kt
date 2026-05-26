package com.proxyagent.app.nativeagent

import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [QuicTransport] backed by the kwik library (`tech.kwik:kwik`).
 *
 * Registered via [NativeProxyAgent.Config.quicTransportFactory] = [Factory].
 * Without this class on the classpath the agent runs TCP-only.
 *
 * Notes:
 *  - TLS validation is disabled — same posture as the Go SDK
 *    (`InsecureSkipVerify: true`). The registrator's identity is verified
 *    by the AUTH key on the control channel, not by the cert chain.
 *  - kwik's congestion control is its own (NewReno-based). Brutal CC
 *    from the Go SDK is not portable to kwik; expect reduced throughput
 *    on highly-lossy uplinks compared to the Go agent.
 *  - Server-initiated streams arrive via `setPeerInitiatedStreamCallback`;
 *    this adapter bridges them to a blocking queue so the agent's
 *    accept loop can pull them with [acceptStream].
 */
class KwikQuicTransport private constructor(
    private val connection: tech.kwik.core.QuicConnection,
    private val incoming: LinkedBlockingQueue<tech.kwik.core.QuicStream>,
) : QuicTransport {

    private val closed = AtomicBoolean(false)

    override fun openStream(): QuicTransport.Stream {
        val s = connection.createStream(true)
        return KwikStream(s)
    }

    override fun acceptStream(): QuicTransport.Stream {
        while (!closed.get()) {
            val s = incoming.poll(500, TimeUnit.MILLISECONDS) ?: continue
            return KwikStream(s)
        }
        throw java.io.IOException("quic transport closed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { connection.close() } catch (_: Throwable) {}
    }

    private class KwikStream(private val s: tech.kwik.core.QuicStream) : QuicTransport.Stream {
        override val input: InputStream get() = s.inputStream
        override val output: OutputStream get() = s.outputStream
        override fun close() {
            // kwik streams close via their I/O streams. Best-effort
            // both sides — output.close() emits FIN, abortReading
            // cancels the read half if the peer hasn't FINned yet.
            try { s.outputStream.close() } catch (_: Throwable) {}
            try { s.abortReading(0L) } catch (_: Throwable) {}
        }
    }

    /** Factory adapter consumed by [NativeProxyAgent]. */
    class Factory : QuicTransport.Factory {
        override fun connect(
            host: String,
            port: Int,
            alpn: String,
            dialTimeoutMs: Int,
            dns: QuicTransport.DnsAdapter,
        ): QuicTransport {
            // kwik's Builder accepts a URI/host+port. Resolve via the
            // agent's DNS hook so explicit DNS overrides apply.
            val resolved = try { dns.resolve(host).hostAddress ?: host } catch (_: Throwable) { host }

            val incoming = LinkedBlockingQueue<tech.kwik.core.QuicStream>()

            // Tuning notes — values mirror proxy-agent-sdk-go's
            // internal/netagent/quic_tuning.go so receive windows and
            // stream limits are at parity. Throughput on lossy mobile
            // uplinks is dominated by these, not by congestion control.
            //
            // What's NOT portable from the Go SDK:
            //   • Brutal CC — kwik uses its own NewReno-style controller
            //     and exposes no CC plugin point. Cost: lower upload
            //     throughput when the link is lossy AND the BDP is large.
            //     Server-side Brutal still operates if it's configured.
            //   • InitialPacketSize / PMTUD — kwik does its own PMTU.
            //   • InitialConnectionReceiveWindow — no public knob.
            val builder = tech.kwik.core.QuicClientConnection.newBuilder()
                .uri(URI.create("https://$resolved:$port"))
                .applicationProtocol(alpn)
                .noServerCertificateCheck()
                .connectTimeout(Duration.ofMillis(dialTimeoutMs.toLong()))
                .maxIdleTimeout(Duration.ofSeconds(60))
                // Initial per-stream receive window. Go SDK uses 8 MB;
                // matching here so 1 Gbps × 50 ms BDP (~6 MiB) fits with
                // headroom for retransmits.
                .defaultStreamReceiveBufferSize(8L * 1024 * 1024)
                // Limit peer-initiated streams. Each tunnel = one stream
                // in QUIC mode, so 1024 is plenty for typical loads and
                // bounds memory if the server misbehaves.
                .maxOpenPeerInitiatedBidirectionalStreams(1024)
                .maxOpenPeerInitiatedUnidirectionalStreams(1024)
                // Oversize the UDP socket buffer so big bursts don't drop
                // packets in the kernel queue. Android typically clamps
                // SO_RCVBUF at net.core.rmem_max (often 4–8 MiB) — we
                // request 32 MB and take whatever the OS allows.
                .socketFactory { _ ->
                    DatagramSocket().apply {
                        try { receiveBufferSize = 32 * 1024 * 1024 } catch (_: Throwable) {}
                        try { sendBufferSize = 32 * 1024 * 1024 } catch (_: Throwable) {}
                    }
                }

            val connection = builder.build()
            connection.setPeerInitiatedStreamCallback { stream ->
                incoming.offer(stream)
            }
            // keepAlive(20s) matches the Go config's KeepAlivePeriod.
            try { connection.keepAlive(20) } catch (_: Throwable) {}

            connection.connect()
            return KwikQuicTransport(connection, incoming)
        }
    }
}
