package com.proxyagent.app.nativeagent

import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Field
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
                // Per-stream receive window. Builder side-effect (see
                // kwik QuicClientConnectionImpl): also sets the
                // connection-level cap to 10× this value, so 16 MiB
                // here gives 160 MiB connection-level — enough headroom
                // for high-throughput uploads through several parallel
                // tunnels (each tunnel = one server-initiated stream).
                // Smaller values (1 MiB / 8 MiB) showed receive-side
                // starvation on Wi-Fi at ~70 Mbps speedtest upload —
                // kwik runs flow-control updates per-stream while the
                // Go SDK uses larger windows + Brutal CC, so we
                // compensate with raw window size.
                .defaultStreamReceiveBufferSize(16L * 1024 * 1024)
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

            // Swap NewReno → FixedWindow congestion control. See
            // companion object's swapToFixedWindowCC doc for the
            // full rationale; tl;dr is kwik exposes no CC plugin
            // point and NewReno's slow-start + multiplicative
            // decrease caps our QUIC send-side throughput around
            // 40 Mbps on healthy paths, while Brutal-equivalent
            // pacing would do 90+. Best-effort — updates
            // ccSwapState for the agent to log; never fails the
            // connect path.
            KwikQuicTransport.installFixedWindowCC(connection)

            return KwikQuicTransport(connection, incoming)
        }
    }

    companion object {
        /** Fixed congestion window installed via reflection over
         *  kwik's default NewReno. 16 MiB ÷ 86 ms RTT ≈ 1.5 Gbps
         *  ceiling — well above any realistic mobile/Wi-Fi rate,
         *  so this window never becomes the bottleneck even at very
         *  high RTT. FixedWindowCongestionController never grows or
         *  shrinks its window, so under-sizing this hard-caps real-
         *  world throughput. Must fit in `int` because kwik's
         *  constructor signature is `(int, Logger)`. */
        private const val FIXED_CC_WINDOW_BYTES = 16 * 1024 * 1024

        /** Outcome of the most recent `connect()`'s CC swap attempt.
         *  `NativeProxyAgent.startQuic` reads and logs this so
         *  `agent.log` shows whether kwik is running with the
         *  FixedWindow pacing override or fell back to NewReno.
         *  Values:
         *  - `swapped` — FixedWindow CC installed, expect ~90 Mbps
         *    download QUIC throughput on healthy paths.
         *  - `skipped:<reason>` — kwik's NewReno still in place
         *    (expect ~40 Mbps download). `<reason>` identifies the
         *    reflection step that failed, useful for picking up
         *    breakage when kwik renames its internals.
         *  - `unattempted` — `connect()` hasn't completed yet. */
        @Volatile var ccSwapState: String = "unattempted"
            private set

        /** Entry point invoked by [Factory.connect] after the QUIC
         *  handshake completes. Wraps [swapToFixedWindowCC] with the
         *  catch-all guard and pushes the outcome into [ccSwapState]
         *  for the agent log. `internal` so Factory can call it but
         *  the implementation stays scoped to this file. */
        internal fun installFixedWindowCC(conn: tech.kwik.core.QuicConnection) {
            ccSwapState = try {
                swapToFixedWindowCC(conn)
            } catch (t: Throwable) {
                "skipped:wrap_${t.javaClass.simpleName}"
            }
        }

        /**
         * Replaces kwik's default NewReno congestion controller with
         * a FixedWindowCongestionController via reflection on private
         * fields. Best-effort — returns a status string the caller
         * can log; never throws.
         *
         * **Why a reflection swap and not a builder method:** kwik
         * 0.10.x's public `QuicClientConnection.Builder` has no CC
         * plugin point (verified against the public method list).
         * The internal field structure used here:
         *
         *   `QuicClientConnectionImpl.sender` (`SenderImpl`)
         *     → `.congestionController` (`CongestionController`,
         *        defaults to `NewRenoCongestionController`)
         *        → `.log` (`tech.kwik.core.log.Logger`,
         *           inherited from `AbstractCongestionController`)
         *
         * **Why FixedWindow and not a custom Brutal-equivalent CC:**
         * AbstractCongestionController already implements all the
         * accounting (`canSend`, `registerInFlight`/Acked/Lost,
         * `bytesInFlight`); only `NewRenoCongestionController`
         * actively shrinks the window on loss and grows it on ack.
         * FixedWindowCongestionController is shipped with kwik,
         * inherits the same accounting, and never modifies the
         * window — exactly the "trust the link" behavior we want.
         * Writing our own CC would duplicate the base class for no
         * additional gain.
         *
         * **bytesInFlight preservation:** the handshake leaves a
         * handful of packets in flight on the old CC's counter. We
         * copy that into the new CC so its counter doesn't go
         * negative when those ACKs arrive (negative
         * `bytesInFlight` only matters for telemetry, never for
         * correctness, but the copy is one extra reflection call
         * for cleanliness).
         *
         * **Risk surface:** breakage if kwik renames any of
         * `sender`, `congestionController`, `bytesInFlight`, `log`
         * in a future version. Each failure returns
         * `skipped:<reason>` and falls back to NewReno — the agent
         * still works, just at NewReno throughput. Pin kwik or
         * re-verify field names on bumps.
         */
        private fun swapToFixedWindowCC(conn: tech.kwik.core.QuicConnection): String {
            val sender = getField(conn, "sender")
                ?: return "skipped:sender_field_missing"
            val oldCC = getField(sender, "congestionController")
                ?: return "skipped:cc_field_missing"
            val log = getField(oldCC, "log")
                ?: return "skipped:logger_field_missing"

            val ccClass = try {
                Class.forName("tech.kwik.core.cc.FixedWindowCongestionController")
            } catch (_: ClassNotFoundException) {
                return "skipped:no_fixedwindow_class"
            }
            val loggerCls = try {
                Class.forName("tech.kwik.core.log.Logger")
            } catch (_: ClassNotFoundException) {
                return "skipped:no_logger_class"
            }

            val newCC = try {
                ccClass.getDeclaredConstructor(
                    Int::class.javaPrimitiveType, loggerCls
                ).newInstance(FIXED_CC_WINDOW_BYTES, log)
            } catch (t: Throwable) {
                return "skipped:ctor_${t.javaClass.simpleName}"
            }

            // Best-effort bytesInFlight migration so ACKs for the
            // handshake's in-flight packets don't underflow the new
            // CC's counter. Silently skip on miss — kwik may rename
            // this field; correctness doesn't depend on it.
            val inFlight = getField(oldCC, "bytesInFlight")
            if (inFlight != null) {
                try { setField(newCC, "bytesInFlight", inFlight) } catch (_: Throwable) {}
            }

            return try {
                setField(sender, "congestionController", newCC)
                "swapped"
            } catch (t: Throwable) {
                "skipped:set_${t.javaClass.simpleName}"
            }
        }

        /** Walk class hierarchy looking for a declared field by name.
         *  Returns null on miss (not exception) so the swap can fall
         *  back gracefully on kwik internals refactors. */
        private fun getField(obj: Any, name: String): Any? {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f: Field = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            return null
        }

        /** Write `value` into `obj`'s field `name`, climbing the
         *  class hierarchy. Bypasses `private final` via
         *  `setAccessible` — works on Android ART without the
         *  modifiers-field hack that newer OpenJDK requires. Throws
         *  `NoSuchFieldException` if the field doesn't exist. */
        private fun setField(obj: Any, name: String, value: Any) {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                try {
                    val f: Field = cls.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(obj, value)
                    return
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            throw NoSuchFieldException(name)
        }
    }
}
