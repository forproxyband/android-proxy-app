package com.proxyagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Loopback TCP relay that puts the agent↔registrator uplink on Wi-Fi while
// the agent↔target dial keeps using cellular (so the public exit IP that
// targets see stays mobile — that's the whole point of the app).
//
// Why this exists
// ───────────────
// The proxy-agent binary (and the NATIVE Kotlin port) opens a single control
// TCP socket to the registrator plus a pool of data sockets, and all
// per-client tunnel traffic flows over those — both directions. On a stock
// setup those sockets
// ride whatever route Android picked as default; on a phone with both Wi-Fi
// and cellular up, that's Wi-Fi (good for us, free of charge) UNLESS the
// device went cellular-only for some reason, or the OS forced cellular
// (e.g. captive portal on Wi-Fi). We want the SAVINGS deliberate, not by
// accident: when Wi-Fi is available we use it; when it's not, we transparently
// fall back to cellular and the agent stays connected.
//
// The Go binary doesn't have access to Android's Network API (it's a plain
// Linux ELF, no JNI). So we put a proxy in between: SDK dials
// 127.0.0.1:<localPort>, we accept, we dial the real registrator with the
// outgoing socket explicitly bound to the Wi-Fi Network, then io.Copy in
// both directions. Two pipe threads per session.
//
// Fallback behavior
// ─────────────────
// If we don't currently hold a usable Wi-Fi Network (haven't acquired one yet,
// onLost just fired, captive-portal validation hasn't passed), we still accept
// the local connection and dial the upstream WITHOUT bindSocket — that puts
// it on the default route, which is cellular when Wi-Fi is gone. The agent
// stays up; the user just loses the bandwidth saving until Wi-Fi comes back.
// We never tear down an in-flight session because of a Wi-Fi state change —
// existing sockets keep using whatever network they were bound to, and only
// the NEXT accept picks up the new state. That gives the system clean handover
// semantics for free.
//
// What we don't do (yet)
// ──────────────────────
// - We don't override DNS for the local side: the Go SDK resolves the host
//   we hand it as "127.0.0.1" via getaddrinfo, which is instant. The relay
//   itself resolves the REAL upstream host through wifiNet.getAllByName when
//   we have Wi-Fi so DNS doesn't accidentally leak to cellular resolvers.
// - We don't reconnect existing sessions when Wi-Fi flaps. That's by design:
//   the SDK has its own dial-loop with backoff, and trying to be cleverer
//   here would race that.
// - We don't support the Balancer mode — see comments in ProxyService for why.
//   On Balancer, the SDK GETs a JSON descriptor from the balancer and then
//   dials the registrator host it picked from that JSON. That second dial
//   bypasses our env override, so the relay would only intercept the balancer
//   GET (the cheap part) and miss the actual uplink. Caller is expected to
//   guard against mode=BALANCER before constructing this class.
class WifiReturnRelay(
    private val context: Context,
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var wifiNet: Network? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var localPort: Int = 0
    private val running = AtomicBoolean(false)
    private val sessionCounter = AtomicInteger(0)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var acceptThread: Thread? = null

    // Per-session activity stats (best-effort, debug visibility only).
    private val activeSessions = AtomicInteger(0)

    // Session-level byte counters. Split four ways so the UI can show
    // exactly what the relay routed via each interface:
    //
    //   wifi*  — bytes that traversed the upstream socket while wifiNet
    //            was non-null at dial time, i.e. genuinely on the Wi-Fi
    //            interface (relay's mobile-data savings).
    //   fallback*  — bytes routed via the process default route (cellular
    //            when bindProcessToNetwork is active) because the relay
    //            couldn't acquire a Wi-Fi Network at dial time. Still
    //            counts as "through the relay", but no savings.
    //
    // Direction is named from the relay's POV facing the registrator:
    //   *Up   — local (loopback from SDK) → upstream socket
    //           = bytes the agent SENT to the registrator
    //   *Down — upstream socket → local
    //           = bytes the agent RECEIVED from the registrator (this is
    //             the "обратный трафик" — response data flowing back to
    //             clients through the tunnel)
    //
    // Counters are session-lifetime (reset on relay restart). ProxyService
    // exposes them in conn_info fields 9-12 every 1s for the widget +
    // logs.
    @Volatile private var wifiUpBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var wifiDownBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var fallbackUpBytes = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var fallbackDownBytes = java.util.concurrent.atomic.AtomicLong(0)

    fun wifiUpBytes(): Long = wifiUpBytes.get()
    fun wifiDownBytes(): Long = wifiDownBytes.get()
    fun fallbackUpBytes(): Long = fallbackUpBytes.get()
    fun fallbackDownBytes(): Long = fallbackDownBytes.get()

    fun start(): Int {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("Relay already started on port $localPort")
        }

        // Acquire a Wi-Fi Network up front. We don't wait for it — start
        // returns immediately so the caller can hand off the loopback port
        // to the SDK; if Wi-Fi isn't available yet, the first accepted
        // connections will fall back to cellular until onAvailable fires.
        registerWifiCallback()

        // Bind to loopback only. port=0 → kernel picks a free port; we read
        // it back via getLocalPort() and hand it to the caller. Backlog 16
        // is more than the Go SDK ever needs (one control + ~8 data sockets
        // in a warm pool, all of which dial sequentially in practice).
        val srv = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
        }
        serverSocket = srv
        localPort = srv.localPort
        log("relay listening on 127.0.0.1:$localPort → $upstreamHost:$upstreamPort")

        // Single accept thread; per-session work goes to two daemon threads
        // (one per direction) spun up inside handleSession. Keeping the
        // accept loop on its own thread means a stuck pipe never blocks
        // future connects.
        val t = Thread {
            try {
                while (running.get()) {
                    val client = try {
                        srv.accept()
                    } catch (_: IOException) {
                        if (running.get()) log("relay accept failed; exiting accept loop")
                        break
                    }
                    val sid = sessionCounter.incrementAndGet()
                    handleSession(sid, client)
                }
            } catch (t: Throwable) {
                log("relay accept loop crashed: ${t.message}")
            }
        }.apply {
            name = "WifiReturnRelay-accept"
            isDaemon = true
            start()
        }
        acceptThread = t

        return localPort
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        log("relay stop requested")
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        unregisterWifiCallback()
        // We don't actively kill open sessions: io.Copy threads will exit
        // naturally when either side closes. The ServerSocket close stops
        // new accepts; existing flows drain. On BINARY the subprocess is
        // destroyed first which makes the local side hang up too; on
        // NATIVE the supervisor stop closes the SDK-side socket.
        acceptThread?.interrupt()
        acceptThread = null
    }

    /** Current local port (0 before start). */
    fun port(): Int = localPort

    // True iff the relay currently holds a usable Wi-Fi Network — new
    // outbound sockets will be bound to it. False means we're falling back
    // to the default route (cellular) until Wi-Fi returns. Polled by the
    // status updater thread in ProxyService to fill conn_info field 8 so
    // the UI can show "via Wi-Fi" vs "via Wi-Fi (fallback)" in the widget.
    fun isUsingWifi(): Boolean = wifiNet != null

    // The Wi-Fi Network handle the relay is currently bound to, or null
    // if no validated Wi-Fi is held. Exposed so ProxyService can pass it
    // to WifiInfoProbe (link speed / band / standard come from the
    // NetworkCapabilities/TransportInfo of THIS specific Network, not
    // the system default).
    fun currentWifiNetwork(): Network? = wifiNet

    // ── Session pipe ────────────────────────────────────────────────────
    private fun handleSession(sid: Int, client: Socket) {
        // Each accepted local connection gets its own outgoing dial. We
        // snapshot wifiNet AT DIAL TIME so a network change after we've
        // bound doesn't disturb the existing socket — bind state is sticky.
        val net = wifiNet
        Thread {
            try {
                val upstream = dialUpstream(net, sid) ?: run {
                    try { client.close() } catch (_: Throwable) {}
                    return@Thread
                }
                val viaWifi = net != null
                val via = if (viaWifi) "wifi" else "default"
                activeSessions.incrementAndGet()
                log("session $sid: connected via $via (active=${activeSessions.get()})")

                // Pick the right counter pair for this session. The "via"
                // label captured at dial time wins for the whole session —
                // even if wifiNet flips later, the existing sockets stay
                // on whatever network they were bound to at connect().
                val upCounter = if (viaWifi) wifiUpBytes else fallbackUpBytes
                val downCounter = if (viaWifi) wifiDownBytes else fallbackDownBytes

                // Two pipe threads. Both close BOTH sockets on EOF/error so
                // we never leak half-open state — the SDK's own retry loop
                // will dial again if it still wants the connection.
                val closeBoth = {
                    try { client.close() } catch (_: Throwable) {}
                    try { upstream.close() } catch (_: Throwable) {}
                }
                // local → upstream (SDK sending data to the registrator)
                Thread {
                    try {
                        pipe(client, upstream, upCounter)
                    } catch (_: Throwable) {
                    } finally {
                        closeBoth()
                        val remaining = activeSessions.decrementAndGet()
                        log("session $sid: closed (l→u), active=$remaining")
                    }
                }.apply {
                    name = "WifiReturnRelay-s$sid-up"
                    isDaemon = true
                    start()
                }
                // upstream → local (server sending response data back —
                // this is the "обратный трафик" the feature exists for).
                try {
                    pipe(upstream, client, downCounter)
                } catch (_: Throwable) {
                } finally {
                    closeBoth()
                }
            } catch (t: Throwable) {
                log("session $sid: dial failed: ${t.message}")
                try { client.close() } catch (_: Throwable) {}
            }
        }.apply {
            name = "WifiReturnRelay-s$sid-dn"
            isDaemon = true
            start()
        }
    }

    private fun dialUpstream(net: Network?, sid: Int): Socket? {
        // Resolve through the chosen Network so DNS doesn't accidentally hit
        // the cellular resolver while the socket itself is bound to Wi-Fi
        // (which would surface as "host unknown" on some captive Wi-Fi).
        // Fall back to plain InetAddress.getAllByName when net is null.
        val addrs: Array<InetAddress> = try {
            if (net != null) net.getAllByName(upstreamHost)
            else InetAddress.getAllByName(upstreamHost)
        } catch (e: Throwable) {
            log("session $sid: DNS failed for $upstreamHost: ${e.message}")
            return null
        }
        if (addrs.isEmpty()) {
            log("session $sid: DNS returned no addresses for $upstreamHost")
            return null
        }

        // Try resolved addresses in order. Most modern hosts return A+AAAA;
        // a transient v6 failure shouldn't block the v4 attempt.
        var lastErr: Throwable? = null
        for (addr in addrs) {
            val sock = Socket()
            try {
                if (net != null) {
                    // bindSocket MUST happen before connect — once a socket
                    // is bound to an address (which connect() does
                    // implicitly), the Network association is fixed.
                    net.bindSocket(sock)
                }
                sock.connect(InetSocketAddress(addr, upstreamPort), 10_000)
                sock.tcpNoDelay = true
                return sock
            } catch (e: Throwable) {
                lastErr = e
                try { sock.close() } catch (_: Throwable) {}
                continue
            }
        }
        log("session $sid: connect to $upstreamHost:$upstreamPort failed via " +
            "${if (net != null) "wifi" else "default"}: ${lastErr?.message}")
        return null
    }

    // Single-direction byte pump. 16 KiB buffer matches typical TCP MSS×11
    // and is what java.io BufferedStream defaults to internally on Android.
    // We don't use NIO because the volume here is per-client request/reply
    // bursts, not high-fanout — the simplicity is worth more than the wins.
    // Counter is incremented after each successful write so partial reads
    // never inflate the number.
    private fun pipe(src: Socket, dst: Socket, counter: java.util.concurrent.atomic.AtomicLong) {
        val input = src.getInputStream()
        val output = dst.getOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) {
                // EOF — propagate half-close so the other side sees clean EOF
                // instead of a connection reset.
                try { dst.shutdownOutput() } catch (_: Throwable) {}
                return
            }
            output.write(buf, 0, n)
            output.flush()
            counter.addAndGet(n.toLong())
        }
    }

    // ── Wi-Fi acquisition ───────────────────────────────────────────────
    private fun registerWifiCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // IMPORTANT: requestNetwork() forbids NET_CAPABILITY_VALIDATED in
        // the NetworkRequest itself — it's a system-managed capability,
        // only registerNetworkCallback() can filter on it. Earlier
        // revisions of this file added VALIDATED here as a "captive-portal
        // guard"; on every Android version that surfaced as
        //   "Cannot request network with VALIDATED — relay will run on
        //    default route"
        // which dropped wifiNet=null forever — uplink ran on the process
        // default (cellular when bindProcessToNetwork is on, Wi-Fi
        // otherwise) and the savings the whole feature exists for never
        // happened. Reproduced on Xiaomi Redmi Note 5 / Android 9 with
        // 1.0.102 — fix is to keep the request simple and check
        // VALIDATED reactively in onCapabilitiesChanged.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Don't claim it yet — wait for onCapabilitiesChanged to
                // confirm VALIDATED. Avoids brief windows where we route
                // through a captive-portal Wi-Fi that can't actually
                // reach the registrator.
            }
            override fun onLost(network: Network) {
                if (wifiNet == network) {
                    wifiNet = null
                    log("Wi-Fi lost: $network — falling back to cellular for new connections")
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Primary acceptance / drop path. A network that's
                // TRANSPORT_WIFI + INTERNET + VALIDATED is good for our
                // uplink. Losing VALIDATED (captive portal staled) means
                // we should fall back to the process default until the
                // OS revalidates or another Wi-Fi shows up.
                val ok = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (ok && wifiNet != network) {
                    wifiNet = network
                    log("Wi-Fi validated and bound: $network — new uplink connections will ride Wi-Fi")
                } else if (!ok && wifiNet == network) {
                    wifiNet = null
                    log("Wi-Fi de-validated: $network — falling back to default route")
                }
            }
        }
        try {
            cm.requestNetwork(req, cb)
            networkCallback = cb
        } catch (t: Throwable) {
            log("requestNetwork(Wi-Fi) failed: ${t.message} — relay will run on default route")
            Log.w("WifiReturnRelay", "requestNetwork failed", t)
        }
    }

    private fun unregisterWifiCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        networkCallback = null
        wifiNet = null
    }
}
