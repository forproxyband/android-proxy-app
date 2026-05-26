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
// The test
// ────────
// 1. Request a Wi-Fi Network in parallel with the default. Open an
//    HTTPS connection to api.ipify.org through THAT network. Read the
//    public IP we egressed as.
// 2. Request a Cellular Network in parallel. Same probe to ipify.
// 3. If both probes succeed AND the two IPs differ → SUCCESS:
//    physically split routing works.
// 4. If both succeed but the IPs are equal → SAME_IP. This means the
//    OS is suppressing one transport (almost always cellular when
//    Wi-Fi is up), and our bindSocket calls in the relay don't
//    actually segregate traffic — the agent's outbound dial leaks
//    over Wi-Fi too, and the target sees the Wi-Fi IP instead of the
//    mobile exit IP we wanted. This defeats the whole purpose of the
//    app, so the relay must be disabled.
// 5. If a probe fails individually → WIFI_PROBE_FAILED /
//    CELL_PROBE_FAILED. Caller decides what to do; the typical
//    pattern is "relay still works but in fallback mode (cellular
//    only); rerun the test when network state changes".
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
        SUCCESS,                 // both probes ok, IPs differ
        SAME_IP,                 // both probes ok, IPs equal — relay can't split
        WIFI_PROBE_FAILED,       // Wi-Fi probe didn't complete
        CELL_PROBE_FAILED,       // cellular probe didn't complete
        BOTH_FAILED,             // neither probe came back
    }

    data class Report(
        val result: Result,
        val wifiPublicIp: String,   // empty when WIFI_PROBE_FAILED
        val cellPublicIp: String,   // empty when CELL_PROBE_FAILED
        val durationMs: Long,
        // Short diagnostic string ("ipify via wifi=ok, via cell=fail").
        // Goes into agent.log and wifi_info.json for debugging.
        val detail: String,
    )

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val OVERALL_BUDGET_MS = 15_000L
    private val PROBE_URLS = listOf("https://api.ipify.org", "https://icanhazip.com")

    fun runTest(context: Context): Report {
        val start = System.currentTimeMillis()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
            ?: return Report(Result.BOTH_FAILED, "", "",
                System.currentTimeMillis() - start, "no ConnectivityManager")

        val wifiProbe = probeOnTransport(
            cm, NetworkCapabilities.TRANSPORT_WIFI, "wifi", start,
        )
        // Bail early if the overall budget is already blown — saves us
        // from a cellular probe that would just timeout anyway.
        if (System.currentTimeMillis() - start > OVERALL_BUDGET_MS) {
            return Report(
                Result.BOTH_FAILED, wifiProbe ?: "", "",
                System.currentTimeMillis() - start,
                "overall budget exceeded after wifi probe",
            )
        }
        val cellProbe = probeOnTransport(
            cm, NetworkCapabilities.TRANSPORT_CELLULAR, "cell", start,
        )

        val dur = System.currentTimeMillis() - start
        val wifiOk = !wifiProbe.isNullOrEmpty()
        val cellOk = !cellProbe.isNullOrEmpty()
        return when {
            !wifiOk && !cellOk -> Report(
                Result.BOTH_FAILED, "", "", dur,
                "wifi=fail cell=fail",
            )
            !wifiOk -> Report(
                Result.WIFI_PROBE_FAILED, "", cellProbe.orEmpty(), dur,
                "wifi=fail cell=$cellProbe",
            )
            !cellOk -> Report(
                Result.CELL_PROBE_FAILED, wifiProbe.orEmpty(), "", dur,
                "wifi=$wifiProbe cell=fail",
            )
            wifiProbe == cellProbe -> Report(
                Result.SAME_IP, wifiProbe.orEmpty(), cellProbe.orEmpty(), dur,
                "ips equal — OS not splitting transports",
            )
            else -> Report(
                Result.SUCCESS, wifiProbe.orEmpty(), cellProbe.orEmpty(), dur,
                "split ok: wifi=$wifiProbe cell=$cellProbe",
            )
        }
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
