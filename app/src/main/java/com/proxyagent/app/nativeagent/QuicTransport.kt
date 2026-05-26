package com.proxyagent.app.nativeagent

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress

/**
 * SPI for plugging a QUIC client implementation into [NativeProxyAgent].
 *
 * The agent is TCP-only out of the box. To enable QUIC, register a
 * [Factory] on [NativeProxyAgent.Config.quicTransportFactory] that
 * produces a connected [QuicTransport] when called. A default
 * implementation backed by the `kwik` library is provided in
 * [KwikQuicTransport]; replace it with any other library by writing a
 * thin adapter against this interface.
 *
 * Wire requirements:
 *  - TLS 1.3 with the registrator's certificate (server-side controls
 *    cert validation; agents typically `insecureSkipVerify`).
 *  - ALPN string passed in [Factory.connect] must match the server's
 *    NextProtos exactly. The agent uses `"proxy-tunnel/1"`.
 *  - Bidirectional streams. The first stream the agent opens carries
 *    the control channel; every server-initiated stream carries one
 *    tunnel (JSON header line then raw bytes).
 */
interface QuicTransport {

    /** Bidirectional QUIC stream wrapped so the agent can read/write
     *  with standard java.io. Closing must shut down both directions. */
    interface Stream : AutoCloseable {
        val input: InputStream
        val output: OutputStream
        override fun close()
    }

    /** Optional DNS hook so the QUIC implementation can resolve via the
     *  same servers as the rest of the agent (instead of the JVM
     *  default resolver). Pass-through implementation is fine. */
    fun interface DnsAdapter {
        fun resolve(host: String): InetAddress
    }

    /** Open a fresh bidirectional stream the agent initiates. */
    fun openStream(): Stream

    /** Open the first bidirectional stream; treated as the control
     *  channel by the agent. Implementations may simply delegate to
     *  [openStream] — distinguished only for log clarity. */
    fun openControlStream(): Stream = openStream()

    /** Block until the peer opens a new stream and return it. The
     *  agent calls this repeatedly in its accept loop. Throw on
     *  shutdown so the loop terminates. */
    fun acceptStream(): Stream

    /** Tear down the connection. Idempotent. */
    fun close()

    /** Builds a connected [QuicTransport]. The implementation owns the
     *  full handshake and TLS configuration. */
    interface Factory {
        /**
         * @param host registrator hostname.
         * @param port registrator UDP port.
         * @param alpn ALPN identifier (see class doc).
         * @param dialTimeoutMs handshake budget; throw on overrun.
         * @param dns optional resolver hook; pass-through is fine.
         */
        fun connect(
            host: String,
            port: Int,
            alpn: String,
            dialTimeoutMs: Int,
            dns: DnsAdapter,
        ): QuicTransport
    }
}
