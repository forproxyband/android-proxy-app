package com.proxyagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Hard verification that the Wi-Fi return relay is actually splitting
// the routing the way we promised: agent↔registrator uplink rides
// Wi-Fi, agent→target dial rides cellular.
//
// Three probes
// ────────────
// 1. WIFI probe — `requestNetwork(TRANSPORT_WIFI)` + `network.openConnection`.
//    Public IP as observed when egressing explicitly through Wi-Fi.
// 2. CELL probe — same via TRANSPORT_CELLULAR.
// 3. DEFAULT probe — plain `URL.openConnection()` without any Network
//    binding. This is what SDK target dials actually use (they don't
//    bind explicitly). On a healthy split setup the process is bound to
//    cellular (`bindProcessToNetwork(cellularNet)`), so DEFAULT == CELL.
//    On a leaking setup (e.g. BINARY engine subprocess that doesn't
//    inherit the process bind, OR no bindProcessToNetwork applied)
//    DEFAULT == WIFI — target sees Wi-Fi IP, not cellular. We catch
//    that as LEAK_DETECTED.
//
// Verdict matrix
// ──────────────
//   wifi != cell  AND  default == cell    → SUCCESS         (true split)
//   wifi != cell  AND  default == wifi    → LEAK_DETECTED   (process not
//                                           bound — target leaks to Wi-Fi)
//   wifi != cell  AND  default fails      → SUCCESS         (can't verify
//                                           leak but at least split works)
//   wifi == cell                          → SAME_IP         (OS suppresses
//                                           one transport — can't split
//                                           at all)
//   any probe fails individually          → WIFI_PROBE_FAILED /
//                                           CELL_PROBE_FAILED /
//                                           BOTH_FAILED
//
// Why ipify
// ─────────
// We need a service that:
//   - returns ONLY the IP (no HTML, no JSON to parse) — so the test
//     is cheap and parseable with no dependencies
//   - is reachable over HTTPS so captive portals / on-path
//     intercepts can't substitute a fake response
//   - is geographically distributed enough to be reachable from
//     anywhere we deploy
// api.ipify.org and icanhazip.com both fit. We try ipify first and
// fall back to icanhazip on individual probe failure, but each
// probe still binds to the explicit Network — DNS resolution
// included.
//
// Threading
// ─────────
// Synchronous: caller wraps in a Thread and waits via runTest(). The
// total budget across both probes is OVERALL_BUDGET_MS. We don't run
// the two probes in parallel because the requestNetwork APIs aren't
// cheap when called twice in quick succession — sequential keeps the
// code simple and the budget tight.
object SplitRoutingSelfTest {

    enum class Result {
        SUCCESS,                 // wifi != cell AND (default==cell OR default unknown)
        SAME_IP,                 // wifi == cell — OS suppresses one transport
        LEAK_DETECTED,           // wifi != cell BUT default == wifi — target dials
                                 //   bypass cellular; clients would see Wi-Fi IP
        WIFI_PROBE_FAILED,       // Wi-Fi probe didn't complete
        CELL_PROBE_FAILED,       // cellular probe didn't complete
        BOTH_FAILED,             // neither probe came back
    }

    data class Report(
        val result: Result,
        val wifiPublicIp: String,    // empty when WIFI_PROBE_FAILED
        val cellPublicIp: String,    // empty when CELL_PROBE_FAILED
        // Public IP observed via the process default route (no explicit
        // bindSocket / Network handle). This is what SDK target dials
        // actually egress through. Empty when the probe couldn't run.
        val defaultPublicIp: String,
        val durationMs: Long,
        // Short diagnostic string ("ipify via wifi=ok, via cell=fail").
        // Goes into agent.log and wifi_info.json for debugging.
        val detail: String,
    )

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val OVERALL_BUDGET_MS = 18_000L  // budget grew with 3rd probe
    private val PROBE_URLS = listOf("https://api.ipify.org", "https://icanhazip.com")

    fun runTest(context: Context): Report {
        val start = System.currentTimeMillis()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
            ?: return Report(Result.BOTH_FAILED, "", "", "",
                System.currentTimeMillis() - start, "no ConnectivityManager")

        val wifiProbe = probeOnTransport(
            cm, NetworkCapabilities.TRANSPORT_WIFI, "wifi", start,
        )
        if (System.currentTimeMillis() - start > OVERALL_BUDGET_MS) {
            return Report(
                Result.BOTH_FAILED, wifiProbe ?: "", "", "",
                System.currentTimeMillis() - start,
                "overall budget exceeded after wifi probe",
            )
        }
        val cellProbe = probeOnTransport(
            cm, NetworkCapabilities.TRANSPORT_CELLULAR, "cell", start,
        )
        // Third probe — process DEFAULT route, no explicit Network bind.
        // This is what SDK target dials actually use. If process is bound
        // to cellular (bindProcessToNetwork), this equals cellProbe. If
        // not bound (or subprocess that didn't inherit), it equals the
        // active default — typically Wi-Fi on a dual-transport device.
        // Mismatch with cellProbe = leak.
        val defaultProbe = if (System.currentTimeMillis() - start < OVERALL_BUDGET_MS) {
            probeOnDefault("default")
        } else null

        val dur = System.currentTimeMillis() - start
        val wifiOk = !wifiProbe.isNullOrEmpty()
        val cellOk = !cellProbe.isNullOrEmpty()
        val defaultOk = !defaultProbe.isNullOrEmpty()
        return when {
            !wifiOk && !cellOk -> Report(
                Result.BOTH_FAILED, "", "", defaultProbe.orEmpty(), dur,
                "wifi=fail cell=fail default=${defaultProbe ?: "fail"}",
            )
            !wifiOk -> Report(
                Result.WIFI_PROBE_FAILED, "", cellProbe.orEmpty(),
                defaultProbe.orEmpty(), dur,
                "wifi=fail cell=$cellProbe default=${defaultProbe ?: "fail"}",
            )
            !cellOk -> Report(
                Result.CELL_PROBE_FAILED, wifiProbe.orEmpty(), "",
                defaultProbe.orEmpty(), dur,
                "wifi=$wifiProbe cell=fail default=${defaultProbe ?: "fail"}",
            )
            wifiProbe == cellProbe -> Report(
                Result.SAME_IP, wifiProbe.orEmpty(), cellProbe.orEmpty(),
                defaultProbe.orEmpty(), dur,
                "ips equal — OS not splitting transports",
            )
            // wifi != cell. Now distinguish SUCCESS vs LEAK_DETECTED via
            // the default probe. If default probe failed entirely, we
            // can't tell — choose the lenient path (SUCCESS) so that a
            // transient probe failure doesn't disable the relay; the next
            // retest will catch a real leak.
            defaultOk && defaultProbe == wifiProbe -> Report(
                Result.LEAK_DETECTED, wifiProbe.orEmpty(),
                cellProbe.orEmpty(), defaultProbe.orEmpty(), dur,
                "LEAK: default route uses Wi-Fi (${defaultProbe}) instead of " +
                    "cellular (${cellProbe}) — SDK target dials would expose " +
                    "Wi-Fi IP to targets",
            )
            defaultOk && defaultProbe == cellProbe -> Report(
                Result.SUCCESS, wifiProbe.orEmpty(), cellProbe.orEmpty(),
                defaultProbe.orEmpty(), dur,
                "split ok: wifi=$wifiProbe cell=$cellProbe default=cell",
            )
            defaultOk -> Report(
                // Edge case: default IP doesn't match either — could be a
                // captive portal / VPN / unusual routing. Treat as leak to
                // be safe; targets aren't seeing cellular anyway.
                Result.LEAK_DETECTED, wifiProbe.orEmpty(),
                cellProbe.orEmpty(), defaultProbe.orEmpty(), dur,
                "default route ($defaultProbe) matches neither wifi " +
                    "($wifiProbe) nor cellular ($cellProbe)",
            )
            else -> Report(
                Result.SUCCESS, wifiProbe.orEmpty(), cellProbe.orEmpty(),
                "", dur,
                "split ok: wifi=$wifiProbe cell=$cellProbe (default probe failed; " +
                    "can't verify leak, assuming clean)",
            )
        }
    }

    // Probes the process default route (no explicit Network handle). Uses
    // a plain URL.openConnection so we get whatever Android's default
    // routing table picks — same path SDK target dials use.
    private fun probeOnDefault(tag: String): String? {
        for (urlStr in PROBE_URLS) {
            val url = URL(urlStr)
            var conn: HttpURLConnection? = null
            try {
                conn = url.openConnection() as? HttpURLConnection ?: continue
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "ProxyAgent-SelfTest/$tag")
                if (conn.responseCode != 200) continue
                val body = BufferedReader(InputStreamReader(conn.inputStream))
                    .use { it.readText() }
                    .trim()
                if (body.isNotEmpty() && body.length <= 39 &&
                    (body.matches(IPV4_RE) || body.contains(':'))) {
                    return body
                }
            } catch (_: Throwable) {
                continue
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
        return null
    }

    // Returns the public IP observed when egressing through the given
    // transport, or null on timeout / network unavailable / HTTP error.
    // Acquires the Network via requestNetwork, opens the connection
    // through it (so DNS resolution and the socket itself stay bound
    // to the right interface), reads the body, releases the request.
    private fun probeOnTransport(
        cm: ConnectivityManager,
        transport: Int,
        tag: String,
        overallStartMs: Long,
    ): String? {
        val req = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkRef = arrayOf<Network?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkRef[0] = network
                latch.countDown()
            }
        }

        return try {
            cm.requestNetwork(req, callback)
            // Cap the per-probe wait by both the local timeout AND the
            // remaining overall budget — whichever is smaller. Prevents
            // probe 2 from running with a stale long timeout when probe 1
            // ate most of the budget.
            val remaining = (OVERALL_BUDGET_MS - (System.currentTimeMillis() - overallStartMs))
                .coerceAtLeast(0)
            val perProbe = minOf(PROBE_TIMEOUT_MS, remaining)
            if (perProbe <= 0L) return null
            val gotNetwork = latch.await(perProbe, TimeUnit.MILLISECONDS)
            if (!gotNetwork) return null
            val network = networkRef[0] ?: return null
            httpFetchVia(network, tag)
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
        }
    }

    // Fetches public IP via the given Network. Tries each fallback URL
    // in order until one returns a parseable IP. We pin the connection
    // to the Network (via network.openConnection) so even DNS goes out
    // the right interface — Android's plain HttpURLConnection would
    // otherwise resolve via the default network's DNS, which on some
    // ROMs leaks information about the request.
    private fun httpFetchVia(network: Network, tag: String): String? {
        for (urlStr in PROBE_URLS) {
            val url = URL(urlStr)
            var conn: HttpURLConnection? = null
            try {
                conn = network.openConnection(url) as? HttpURLConnection ?: continue
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "ProxyAgent-SelfTest/$tag")
                val code = conn.responseCode
                if (code != 200) continue
                val body = BufferedReader(InputStreamReader(conn.inputStream))
                    .use { it.readText() }
                    .trim()
                // ipify returns plain "1.2.3.4"; icanhazip returns the
                // same. Anything longer than 39 chars (max IPv6 textual
                // form) is suspect — treat as garbage rather than
                // pretending we have an IP.
                if (body.isNotEmpty() && body.length <= 39 &&
                    (body.matches(IPV4_RE) || body.contains(':'))) {
                    return body
                }
            } catch (_: Throwable) {
                continue
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
        return null
    }

    private val IPV4_RE = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
