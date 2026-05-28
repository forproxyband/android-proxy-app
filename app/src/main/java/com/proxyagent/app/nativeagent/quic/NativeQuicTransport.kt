package com.proxyagent.app.nativeagent.quic

import com.proxyagent.app.nativeagent.QuicTransport
import com.proxyagent.app.nativeagent.quic.connection.Connection
import com.proxyagent.app.nativeagent.quic.tls.TransportParameters
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-house QUIC client adapter — implements the `QuicTransport`
 * SPI on top of our hand-rolled QUIC v1 stack
 * (`com.proxyagent.app.nativeagent.quic.*`). Drop-in replacement
 * for [com.proxyagent.app.nativeagent.KwikQuicTransport.Factory]
 * once field-validated.
 *
 * Why we're switching off kwik:
 *  - kwik's single-sender-thread architecture starves
 *    ACK / MAX_*_DATA emission behind STREAM-frame draining
 *    (documented as the "do-not-revisit" knob in
 *    ARCHITECTURE.md). With our own stack, the sender loop
 *    explicitly prioritizes control frames before STREAM data,
 *    so window updates land promptly and the receive direction
 *    doesn't collapse under load.
 *  - kwik has no CC plugin point, forcing a reflection patch
 *    to swap in `FixedWindowCongestionController` — which still
 *    doesn't give us Brutal CC's pacing model. The native stack
 *    ships [com.proxyagent.app.nativeagent.quic.cc.BrutalCongestionControl]
 *    natively.
 */
class NativeQuicTransport private constructor(
    private val connection: Connection,
) : QuicTransport {

    private val closed = AtomicBoolean(false)

    override fun openStream(): QuicTransport.Stream {
        check(!closed.get()) { "transport closed" }
        val s = connection.openBidiStream()
        return StreamAdapter(s)
    }

    override fun acceptStream(): QuicTransport.Stream {
        check(!closed.get()) { "transport closed" }
        val s = connection.acceptStream(timeoutMs = Long.MAX_VALUE)
            ?: throw java.io.IOException("transport closed during accept")
        return StreamAdapter(s)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { connection.close() } catch (_: Throwable) {}
    }

    private class StreamAdapter(
        private val stream: com.proxyagent.app.nativeagent.quic.stream.Stream,
    ) : QuicTransport.Stream {
        override val input: InputStream get() = stream.input
        override val output: OutputStream get() = stream.output
        override fun close() {
            try { stream.input.close() } catch (_: Throwable) {}
            try { stream.output.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Factory matching kwik's. Plugs into
     * [com.proxyagent.app.nativeagent.NativeProxyAgent.Config.quicTransportFactory]
     * the same way. NativeProxyAgent doesn't need to know
     * which factory it's using — both produce a `QuicTransport`.
     */
    class Factory(
        /** Optional Wi-Fi-return hook applied to the UDP uplink socket
         *  before connect. ProxyService passes a lambda that resolves to
         *  the current Wi-Fi `Network` and calls `bindSocket(...)`; null
         *  means "use the process default route" (no Wi-Fi return). The
         *  binder is re-invoked on every (re)dial including stall
         *  self-heal, so each fresh QUIC socket lands on the right
         *  network even after a Wi-Fi handover. */
        private val uplinkSocketBinder: ((java.net.DatagramSocket) -> Unit)? = null,
        /** User-selected network profile. Renders to a [QuicTuning]
         *  that gates Brutal CC target rate, the UDP socket buffer
         *  size, and the flow-control window-refresh cadence. Default
         *  reproduces the pre-profile hardcoded parameters (Brutal
         *  CC 100 Mbps as the old hardcoded value, but with the
         *  larger UDP buffer / 50% FC refresh that match the kwik
         *  defaults at the high end). */
        private val networkProfile: NetworkProfile = NetworkProfile.HIGH_1000,
    ) : QuicTransport.Factory {
        override fun connect(
            host: String,
            port: Int,
            alpn: String,
            dialTimeoutMs: Int,
            dns: QuicTransport.DnsAdapter,
        ): QuicTransport {
            val resolved = try {
                InetSocketAddress(dns.resolve(host), port)
            } catch (t: Throwable) {
                throw java.io.IOException("DNS lookup failed for $host: ${t.message}", t)
            }
            val ourTp = TransportParameters(
                // Mirror what kwik effectively advertises: 16 MiB per-stream
                // and 160 MiB (= 10× per-stream) at the connection level.
                // Build-93's 9-minute upload trace showed the proxy stops
                // forwarding client→agent data when our advertised connection
                // MAX_DATA is tight (12 MiB): once we use it during download
                // it never gets reopened, and the proxy throttles subsequent
                // uploads. Advertising 160 MiB up front gives the proxy
                // permanent headroom and tracks kwik's behavior 1:1 — which
                // does push upload (7.94 Mbps in the user's run) where 12 MiB
                // gave 0.
                maxIdleTimeoutMs = 60_000,
                initialMaxData = 160L * 1024 * 1024,
                initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                initialMaxStreamDataUni = 16L * 1024 * 1024,
                initialMaxStreamsBidi = 1024,
                initialMaxStreamsUni = 1024,
                initialSourceConnectionId = null,  // filled in by Connection
            )
            val quicTuning = networkProfile.tuning().quic
            val conn = Connection(
                serverHost = host,
                serverPort = port,
                resolvedAddress = resolved,
                alpn = alpn,
                ourTransportParameters = ourTp,
                ccTargetMbps = quicTuning.brutalTargetMbps,
                uplinkSocketBinder = uplinkSocketBinder,
                udpSocketBufBytes = quicTuning.udpSocketBufBytes,
                windowUpdateHeadroomRatio = quicTuning.windowUpdateHeadroomRatio,
            )
            conn.connect(timeoutMs = dialTimeoutMs.toLong())
            return NativeQuicTransport(conn)
        }
    }
}
