package com.proxyagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProxyService : Service() {

    enum class ConnStatus { STARTING, CONNECTING, CONNECTED, RECONNECTING, ERROR, STOPPED }
    // NATIVE: pure-Kotlin port of the Go SDK, runs in-process. Default for
    // new installs. BINARY: ProcessBuilder fork of libproxyagent.so (legacy).
    // AAR: gomobile-built SDK (.so) loaded via Class.forName.
    enum class Engine { NATIVE, BINARY, AAR }
    enum class Mode { MODEM, BALANCER }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false
    @Volatile private var agentProcess: Process? = null
    @Volatile private var runnerThread: Thread? = null
    @Volatile private var engine: Engine = Engine.NATIVE
    // Live native agent — only set when engine=NATIVE.
    @Volatile private var nativeAgent: com.proxyagent.app.nativeagent.NativeProxyAgent? = null
    @Volatile private var mode: Mode = Mode.MODEM
    // QUIC implementation choice ("kwik" | "native"), passed via the
    // start intent (NOT SharedPreferences — the :proxy process caches
    // prefs and misses cross-process writes from the settings UI).
    @Volatile private var quicImpl: String = "kwik"
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var analytics: AnalyticsRecorder? = null
    @Volatile private var lastNatRefreshMs = 0L
    // Loopback relay that puts the agent↔registrator uplink on Wi-Fi while
    // outbound target dials stay on cellular. Lifecycle owned by the runX
    // engine call sites — see runBinaryEngine / runAarEngine. Lives only
    // when wifi_return=true && mode=MODEM (see WifiReturnRelay top-of-file).
    @Volatile private var wifiRelay: WifiReturnRelay? = null
    // Real upstream (host, port) the agent should ultimately talk to —
    // either directly or through the Wi-Fi return relay. originalHost/Port
    // stay constant for the engine's lifetime; effectiveHost/Port are
    // what we actually feed to the SDK (loopback when the relay is up,
    // original otherwise). The split-routing self-test may flip
    // effective→original mid-flight on a SAME_IP failure — see
    // handleSplitFailureForCurrentEngine. Fields instead of locals so the
    // runner loop and helpers all share the same source of truth.
    @Volatile private var originalHost: String = ""
    @Volatile private var originalPort: String = ""
    @Volatile private var effectiveHost: String = ""
    @Volatile private var effectivePort: String = ""
    // NetworkCallback dedicated to re-running the split-routing self-test
    // whenever a new Wi-Fi network attaches. Separate from the one in
    // WifiReturnRelay (that one drives socket binding; this one drives
    // re-verification). Both reference the same ConnectivityManager but
    // their lifecycles are coupled — we unregister both in doStop.
    private var wifiReturnRetestCallback: ConnectivityManager.NetworkCallback? = null
    // Cellular Network we hold via requestNetwork. Required because we
    // call bindProcessToNetwork(cellularNet) before starting the relay so
    // SDK target dials inherit cellular as the process default route. If
    // we don't do this, the default route is Wi-Fi (priority on dual-
    // transport devices), and target dials leak the Wi-Fi public IP to
    // targets instead of the mobile exit IP. The relay's own outbound
    // sockets stay on Wi-Fi via per-socket bindSocket (overrides the
    // process default).
    @Volatile private var cellularNet: Network? = null
    private var cellularNetworkCallback: ConnectivityManager.NetworkCallback? = null
    // Single in-flight self-test thread guard. Re-test triggers (network
    // change, manual button) coalesce while a previous run is still going.
    @Volatile private var selfTestInFlight = false

    @Volatile private var connStatus: ConnStatus = ConnStatus.STARTING
    @Volatile private var rxRate = 0L
    @Volatile private var txRate = 0L
    @Volatile private var currentRegistrator = ""
    @Volatile private var activeTunnels = 0
    @Volatile private var connectedSinceMs = 0L
    // Human-readable uplink transport label exposed via conn_info so the
    // UI can show "QUIC" / "TCP (splice)" / "TCP+yamux" / "WebSocket".
    // Filled from the SDK's "uplink connected … transport=quic|tcp" log
    // (v2.0.14-quic+); older builds without the key default to "TCP+yamux"
    // or "WebSocket" depending on which "connected" line variant we saw.
    @Volatile private var currentUplinkTransport: String = ""
    // Short human-readable description of the current REBOOT auto-cycle step,
    // pushed into conn_info field 7 so MainActivity can show "ROTATING · …"
    // instead of "RECONNECTING…" while the radio is intentionally bouncing.
    // Empty when no cycle is in flight.
    @Volatile private var cycleStage: String = ""
    // Wi-Fi return relay status — written into conn_info field 8 so the UI
    // can show whether the uplink is actually flowing over Wi-Fi or fell
    // back to cellular. One of:
    //   ""              — relay disabled (never enabled, or auto-stopped clean)
    //   "wifi"          — relay up AND currently bound to a validated Wi-Fi
    //   "wifi_fallback" — relay up but no Wi-Fi network held; new sockets go
    //                     through the default route (cellular)
    //   "split_failed"  — self-test detected that Wi-Fi and cellular share
    //                     the same public IP (OS suppressing one transport),
    //                     relay was force-disabled. Sticky until next start.
    // Refreshed every 1s by the status updater thread (see onStartCommand),
    // except the split_failed sticky state which the updater preserves.
    @Volatile private var wifiReturnStatus: String = ""
    // Sticky flag: when the split-routing self-test fails with SAME_IP, we
    // disable the relay and set this to true so the status updater leaves
    // wifiReturnStatus="split_failed" instead of clearing it back to "".
    // Reset only in doStop / next service start.
    @Volatile private var wifiReturnSplitFailed: Boolean = false
    // Sticky flag: BINARY engine + LEAK_DETECTED is an expected outcome
    // (subprocess doesn't inherit bindProcessToNetwork), but the user
    // should still see the warning in the widget. The relay stays alive
    // for mobile-data savings on the uplink; this just stops the status
    // updater clobbering "leak_known" back to "wifi" on the next tick.
    @Volatile private var wifiReturnLeakKnown: Boolean = false
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastStatsAt = 0L

    private val regSelectedRe = Regex("""host=(\S+) port=(\d+)""")
    private val wsUrlRe = Regex("""url=wss?://([^/\s"]+)""")
    // New yamux-uplink log line: `msg="uplink dialing" endpoint=host:port`.
    // No URL anymore (transport is plain TCP + yamux), so we read the
    // endpoint key directly. Used to fill currentRegistrator in direct/
    // modem mode where there's no preceding "selected registrator" line.
    private val endpointRe = Regex("""endpoint=([^\s"]+):(\d+)""")
    // `transport=quic|tcp` appears on the post-AUTH "uplink connected" log
    // line and on "uplink dialing" / "transport dial failed" diagnostics.
    // We only act on the post-AUTH one to avoid flipping the badge mid-probe.
    private val transportRe = Regex("""\btransport=(\w+)""")
    private val directRegRe = Regex("""direct registrator configured.*?host=(\S+) port=(\d+)""")
    private val rebootReasonRe = Regex("""reason=(.*)$""")
    @Volatile private var autoCycling = false

    @Synchronized
    private fun log(msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val logFile = File(filesDir, "agent.log")
            logFile.appendText("$ts $msg\n")
            Log.d("ProxyAgent", msg)
            if (logFile.length() > MAX_LOG_BYTES) trimLog(logFile)
        } catch (_: Exception) {}
    }

    // Keep the last KEEP_LOG_BYTES of agent.log; rotate when it grows past MAX_LOG_BYTES.
    // Whole `log()` is synchronized so no writer races with the trim.
    private fun trimLog(logFile: File) {
        try {
            val len = logFile.length()
            if (len <= KEEP_LOG_BYTES) return
            val skip = len - KEEP_LOG_BYTES
            val tail: ByteArray
            logFile.inputStream().use { input ->
                var skipped = 0L
                while (skipped < skip) {
                    val s = input.skip(skip - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                // Advance to next '\n' so we don't start from mid-line
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                }
                tail = input.readBytes()
            }
            logFile.writeBytes(tail)
        } catch (_: Throwable) {}
    }

    companion object {
        private const val MAX_LOG_BYTES = 30L * 1024 * 1024  // trigger rotation
        private const val KEEP_LOG_BYTES = 25L * 1024 * 1024 // keep this much tail
        // Identifies the conn_info layout the writer produced. Bumped when
        // the field set changes incompatibly; not currently consumed (UI
        // uses positional getOrNull) but recorded so future readers can
        // branch on layout if/when we ever want to drop or reorder fields.
        // v1 = first layout with heartbeat (field 9) + pid (field 10) +
        // schema_version (field 11). Older snapshots have neither and the
        // heartbeat-stale check naturally treats them as void.
        private const val CONN_INFO_SCHEMA_VERSION = 1
    }

    private fun state(s: String) {
        try { File(filesDir, "proxy_state").writeText(s) } catch (_: Exception) {}
    }

    private fun writeConnInfo() {
        try {
            // Field 6 (currentUplinkTransport) was added in v2.0.14-quic;
            // field 7 (cycleStage) was added with the IP-rotation UI surface;
            // field 8 (wifiReturnStatus) was added with the Wi-Fi return
            // relay feature; field 9 is a wall-clock heartbeat refreshed on
            // every writeConnInfo (≈1Hz from the status updater + on every
            // state transition) so MainActivity can detect when this process
            // died without a graceful doStop — typically PACKAGE_REPLACED
            // kill mid-session — and stop trusting the stale file (otherwise
            // the UI keeps showing CONNECTED + accumulating uptime forever);
            // field 10 is the writer's pid (debug aid in post-mortem log
            // analysis — pid in conn_info that doesn't match any live
            // ProxyService process means the file is a leftover from a
            // previous incarnation); field 11 is CONN_INFO_SCHEMA_VERSION
            // so future readers can branch on layout without sniffing field
            // counts. Readers must use getOrNull(N) for forward compatibility
            // so the tail fields stay optional if a downgrade ever writes
            // shorter rows. `|` is escaped in cycleStage so a stray pipe in
            // a log line can't shift the field count.
            val safeStage = cycleStage.replace('|', '/')
            val pid = android.os.Process.myPid()
            File(filesDir, "conn_info").writeText(
                "${connStatus.name}|$rxRate|$txRate|$currentRegistrator|$activeTunnels|$connectedSinceMs|$currentUplinkTransport|$safeStage|$wifiReturnStatus|${System.currentTimeMillis()}|$pid|$CONN_INFO_SCHEMA_VERSION"
            )
        } catch (_: Exception) {}
    }

    private fun readBatteryThreshold(): Int = try {
        File(filesDir, "battery_threshold").readText().trim().toIntOrNull() ?: 0
    } catch (_: Throwable) { 0 }

    // System-level check: is there a validated, non-suspended internet path right now?
    // Returns false when the OS has marked the active network as "no internet" — e.g.
    // cellular data exhausted, captive portal, or carrier-side suspension.
    private fun systemSaysInternetUp(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
            if (Build.VERSION.SDK_INT >= 28 &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) return false
            true
        } catch (_: Throwable) { true }  // be conservative: assume up on error
    }

    private fun mask(s: String): String {
        if (s.isEmpty()) return "<empty>"
        if (s.length <= 6) return "****"
        return s.substring(0, 3) + "****" + s.substring(s.length - 3)
    }

    // SDK ≥ v2.0.10 dropped WebSocket and now uses plain TCP + yamux on the
    // uplink. Log strings changed too, so we match both old and new wording
    // here — keeping the old keys means older binaries (and the AAR before
    // it gets bumped) keep parsing correctly during the rollout.
    //
    //   old WS                          new yamux uplink
    //   "ws connected"                  "uplink connected"
    //   "tunnel opened"                 "opening tunnel"
    //   "ws dialing"                    "uplink dialing"   (endpoint=host:port)
    //   "ws read error"/"close 1006"/   "uplink accept loop ended" /
    //   "ws close frame"                "uplink control loop ended" /
    //                                   "uplink dial failed" /
    //                                   "uplink yamux init failed" /
    //                                   "uplink AUTH …"
    // Centralised setter for currentRegistrator. When the Wi-Fi return relay
    // is active, the SDK dials and logs the loopback address (127.0.0.1:
    // <localPort>), not the real registrator — that's correct on the wire
    // but useless in the UI. Substitute the real upstream so the widget
    // shows the actual host:port. Empty values fall through unchanged so
    // reconnect/clear paths still work via direct assignment.
    private fun applyCurrentRegistrator(value: String) {
        val sanitized = if (value.isNotEmpty() && originalHost.isNotEmpty() && (
                value.startsWith("127.0.0.1:") ||
                value.startsWith("localhost:") ||
                value.startsWith("[::1]:") ||
                value.startsWith("::1:")
            )
        ) {
            "$originalHost:$originalPort"
        } else value
        currentRegistrator = sanitized
        if (sanitized.isNotEmpty()) analytics?.setRegistrator(sanitized)
    }

    private fun parseAgentLine(line: String) {
        when {
            line.contains("tunnel opened") || line.contains("opening tunnel") -> {
                activeTunnels++
                analytics?.onTunnelOpen()
            }
            line.contains("tunnel closed") -> {
                activeTunnels = (activeTunnels - 1).coerceAtLeast(0)
                analytics?.onTunnelClose()
            }
            line.contains("ws connected") || line.contains("uplink connected") -> {
                connStatus = ConnStatus.CONNECTED
                connectedSinceMs = System.currentTimeMillis()
                // Detect the underlying uplink transport for the status card.
                //   • v2.0.14-quic+ logs `uplink connected … transport=quic|tcp`
                //   • v2.0.10..v2.0.13 logged the same "uplink connected" line
                //     without the key — always TCP+yamux there.
                //   • pre-v2.0.10 logged "ws connected" with no key — WebSocket.
                //
                // For the NATIVE engine the TCP fast path may or may not use
                // kernel splice — depends on whether libagentsplice.so loaded
                // and fd extraction works on this device. We can't tell yet
                // at "uplink connected" time (the first tunnel hasn't opened),
                // so we start with a neutral "TCP" and refine to either
                // "TCP (splice)" or "TCP (NIO)" when the splice subsystem
                // reports its outcome on the first bridge call. BINARY/AAR
                // engines reliably do splice via Go's io.Copy on TCPConn,
                // so for them we keep "TCP (splice)" as before.
                currentUplinkTransport = if (line.contains("uplink connected")) {
                    when (transportRe.find(line)?.groupValues?.get(1)?.lowercase(Locale.US)) {
                        "quic" -> "QUIC"
                        "tcp" -> when {
                            // NATIVE: don't clobber a splice/NIO state
                            // that the SpliceShim warmup may have
                            // already published. Warmup runs inside
                            // start() — before the supervisor dials —
                            // so its "kernel zero-copy active" /
                            // "NIO fallback engaged" log line arrives
                            // BEFORE this "uplink connected" line.
                            // Without this guard we'd overwrite the
                            // accurate badge with a bare "TCP".
                            engine == Engine.NATIVE &&
                                (currentUplinkTransport == "TCP (splice)" ||
                                 currentUplinkTransport == "TCP (NIO)") ->
                                    currentUplinkTransport
                            engine == Engine.NATIVE -> "TCP"
                            else -> "TCP (splice)"
                        }
                        null -> "TCP+yamux"
                        else -> transportRe.find(line)?.groupValues?.get(1)?.uppercase(Locale.US).orEmpty()
                    }
                } else {
                    "WebSocket"
                }
                // Old WS log carried `url=wss://host:port/…`; new uplink log
                // has only `uuid=…`. currentRegistrator on the new path is
                // filled earlier by "selected registrator …", "direct
                // registrator configured", or the "uplink dialing" branch.
                // All goes through applyCurrentRegistrator so the Wi-Fi
                // return loopback (127.0.0.1:<localPort>) gets rewritten
                // to the real upstream before it reaches the widget.
                wsUrlRe.find(line)?.let {
                    applyCurrentRegistrator(it.groupValues[1])
                }
            }
            line.contains("selected") && line.contains("registrator") -> {
                regSelectedRe.find(line)?.let {
                    applyCurrentRegistrator("${it.groupValues[1]}:${it.groupValues[2]}")
                }
            }
            line.contains("direct registrator configured") -> {
                directRegRe.find(line)?.let {
                    applyCurrentRegistrator("${it.groupValues[1]}:${it.groupValues[2]}")
                }
            }
            line.contains("ws read error") ||
                line.contains("close 1006") ||
                line.contains("ws close frame") ||
                line.contains("uplink dial failed") ||
                line.contains("uplink yamux init failed") ||
                line.contains("uplink control stream open failed") ||
                line.contains("uplink AUTH send failed") ||
                line.contains("uplink AUTH reply read failed") ||
                line.contains("uplink AUTH denied") ||
                line.contains("uplink accept loop ended") ||
                line.contains("uplink control loop ended") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                analytics?.resetActiveTunnels()
            }
            line.contains("balancer selection failed") ||
                line.contains("no registrator available") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                analytics?.resetActiveTunnels()
            }
            line.contains("ws dialing") ||
                line.contains("uplink dialing") ||
                line.contains("balancer request") -> {
                if (connStatus != ConnStatus.CONNECTED) connStatus = ConnStatus.CONNECTING
                // "uplink dialing" carries `endpoint=host:port` — surface
                // that as the registrator address so direct/modem mode
                // shows something useful before any tunnel opens. Loopback
                // dials are rewritten by applyCurrentRegistrator below.
                endpointRe.find(line)?.let {
                    applyCurrentRegistrator("${it.groupValues[1]}:${it.groupValues[2]}")
                }
            }
            // Server-side REBOOT command. The SDK logs this whenever the
            // registrator pushes REBOOT (over the WS in pre-2.0.10 builds,
            // over the yamux control stream in 2.0.10+), regardless of
            // whether the local-WS relay is enabled. Same behavior as the
            // manual ↻ button: toggle cellular to grab a new carrier IP,
            // then reconnect.
            line.contains("REBOOT received from registrator") -> {
                val reason = rebootReasonRe.find(line)?.groupValues?.get(1).orEmpty().trim()
                triggerAutoIpCycle(reason)
            }
            // NATIVE engine splice diagnostics — refine the "TCP" badge
            // into either "TCP (splice)" or "TCP (NIO)" depending on
            // whether the kernel zero-copy shim activated. We only
            // touch the label when the current value is the bare "TCP"
            // we set in the "uplink connected" branch, OR when an
            // earlier fallback decision is being upgraded to splice.
            //
            // Ordering policy: success wins over fallback. If splice
            // ever activates this session, the badge becomes
            // "TCP (splice)" and STAYS there — even if some later
            // tunnel hits NIO fallback (which shouldn't happen given
            // SpliceShim's once-per-process strategy caching, but
            // we're defensive in case future Android versions cause
            // partial breakage).
            line.contains("splice: kernel zero-copy active") -> {
                if (engine == Engine.NATIVE) currentUplinkTransport = "TCP (splice)"
            }
            line.contains("splice: NIO fallback engaged") -> {
                if (engine == Engine.NATIVE && currentUplinkTransport != "TCP (splice)") {
                    currentUplinkTransport = "TCP (NIO)"
                }
            }
        }
    }

    private fun triggerAutoIpCycle(reason: String) {
        if (autoCycling) {
            log("Auto IP-cycle already in progress; ignoring REBOOT (reason=\"$reason\")")
            return
        }
        if (stopRequested) return
        autoCycling = true
        cycleStage = "starting"
        writeConnInfo()   // push the new stage into UI within 1s of the trigger
        Thread {
            try {
                log("REBOOT auto-cycle: starting (reason=\"$reason\", engine=${engine.name})")
                // cycleAndVerify drives the 10s+60s nuclear ladder under a 180s
                // budget, plus optional APN swap / IMEI rotation fallbacks. The
                // subprocess will see its WS read error during airplane-on and
                // enter the SDK's internal backoff loop on its own; we don't
                // kill it pre-emptively because that would race our own
                // runner's backoff sleep, restarting it mid-toggle.
                val baseline = try {
                    File(filesDir, "nat_ip").readText().trim()
                } catch (_: Throwable) { "" }
                // Read cycle config from the cross-process file written by
                // MainActivity on settings save. SharedPreferences won't work
                // here: this service runs in the :proxy process and its prefs
                // in-memory cache wouldn't see changes made in :main.
                val cfg = IpCycle.loadConfigFromFile(this)
                val result = IpCycle.cycleAndVerify(
                    context = this,
                    knownIp = baseline,
                    log = { msg ->
                        // UI surface: latest stage line shows up as
                        // "ROTATING · <stage>" in MainActivity within ~1s.
                        cycleStage = msg
                        log("REBOOT auto-cycle: $msg")
                    },
                    config = cfg,
                )
                // Persist the rotation event with full result detail so the
                // analytics screen can show the IP change + outcome. Done
                // right after cycleAndVerify so even if the post-cycle logic
                // below errors we still have the event row.
                AnalyticsStore.recordCycleEvent(
                    this,
                    CycleEvent(
                        tMs = System.currentTimeMillis(),
                        kind = AnalyticsStore.CYCLE_AUTO,
                        oldIp = result.oldIp,
                        newIp = result.newIp,
                        changed = result.changed,
                        reason = result.reason,
                        attempts = result.attempts,
                        durationMs = result.totalMs,
                    ),
                )
                val secs = result.totalMs / 1000
                when {
                    result.reason == "no_toggle_method" ->
                        log("REBOOT auto-cycle: no root + no WRITE_SECURE_SETTINGS; reconnecting on existing IP")
                    result.changed -> {
                        log("REBOOT auto-cycle: IP ${result.oldIp} -> ${result.newIp} in ${result.attempts} try(s) / ${secs}s")
                        try { File(filesDir, "nat_ip").writeText(result.newIp) } catch (_: Throwable) {}
                        analytics?.setNatIp(result.newIp)
                    }
                    result.newIp.isNotEmpty() ->
                        log("REBOOT auto-cycle: IP unchanged (${result.newIp}) after ${result.attempts} try(s) / ${secs}s")
                    else ->
                        log("REBOOT auto-cycle: ${result.reason} after ${result.attempts} try(s) / ${secs}s")
                }
                if (!stopRequested) {
                    // For BINARY this kills the subprocess + interrupts our
                    // runner so it re-dials with fresh state on the new IP.
                    // For AAR this is a no-op; the in-process SDK reconnects
                    // on its own after the WS read error from the toggle.
                    forceReconnect("REBOOT auto-cycle")
                    log("REBOOT auto-cycle: reconnect kicked")
                }
            } catch (e: Throwable) {
                log("REBOOT auto-cycle error: ${e.message}")
            } finally {
                autoCycling = false
                cycleStage = ""
                writeConnInfo()   // clear "ROTATING · …" badge immediately
                // If the Wi-Fi return relay is active, the cellular probe
                // result we cached in wifi_info.json now reflects the OLD
                // cellular IP — the whole point of rotation is to change it.
                // Schedule ONE self-test ~5s after the cycle settles so the
                // widget shows the fresh cellular exit IP. We deliberately
                // suppress per-Wi-Fi-onAvailable retests during autoCycling
                // (see registerWifiReturnRetestCallback) so this is the only
                // retest that fires per rotation — no double-firing, no
                // wasted cellular data.
                if (wifiRelay != null && !stopRequested && !wifiReturnSplitFailed) {
                    schedulePostRotationSelfTest()
                }
            }
        }.apply { name = "AutoIpCycler"; isDaemon = true; start() }
    }

    private fun readSpeedUnitsBytes(): Boolean = try {
        File(filesDir, "speed_units").readText().trim() == "bytes"
    } catch (_: Throwable) { false }

    private fun humanRate(bytesPerSec: Long): String {
        if (bytesPerSec < 0) return "—"
        return if (readSpeedUnitsBytes()) {
            when {
                bytesPerSec < 1024 -> "${bytesPerSec}B/s"
                bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024}KB/s"
                bytesPerSec < 1024L * 1024 * 1024 -> "%.1fMB/s".format(Locale.US, bytesPerSec / 1024.0 / 1024.0)
                else -> "%.1fGB/s".format(Locale.US, bytesPerSec / 1024.0 / 1024.0 / 1024.0)
            }
        } else {
            val bits = bytesPerSec * 8
            when {
                bits < 1000 -> "${bits}b/s"
                bits < 1000 * 1000 -> "${bits / 1000}Kb/s"
                bits < 1000L * 1000 * 1000 -> "%.1fMb/s".format(Locale.US, bits / 1000.0 / 1000.0)
                else -> "%.1fGb/s".format(Locale.US, bits / 1000.0 / 1000.0 / 1000.0)
            }
        }
    }

    private fun statusText(): String {
        val base = when (connStatus) {
            ConnStatus.STARTING -> "Starting…"
            ConnStatus.CONNECTING -> "Connecting…"
            ConnStatus.CONNECTED ->
                if (currentRegistrator.isNotEmpty()) "Connected · $currentRegistrator"
                else "Connected"
            ConnStatus.RECONNECTING -> "Reconnecting…"
            ConnStatus.ERROR -> "Error"
            ConnStatus.STOPPED -> "Stopped"
        }
        return if (connStatus == ConnStatus.CONNECTED)
            "$base · ↓${humanRate(rxRate)} ↑${humanRate(txRate)}"
        else base
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "proxy")
            .setContentTitle("Proxy Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop", PendingIntent.getService(this, 0,
                Intent(this, ProxyService::class.java).apply { action = "STOP" },
                PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()

    // Public IP refresh runs in-service so analytics buckets always have a
    // best-known NAT value, even if the UI never opened. Throttled to once
    // every 5 minutes — public IP shouldn't change unless we cycle.
    private fun maybeRefreshNatIp() {
        val now = System.currentTimeMillis()
        if (now - lastNatRefreshMs < 5 * 60_000L) return
        lastNatRefreshMs = now
        Thread {
            try {
                for (url in listOf("https://api.ipify.org", "https://icanhazip.com")) {
                    try {
                        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "ProxyAgent-Android")
                        }
                        val ip = conn.inputStream.bufferedReader().use { it.readText().trim() }
                        conn.disconnect()
                        if (ip.isNotEmpty() && ip.length < 40 &&
                            (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ip.contains(":"))) {
                            try { File(filesDir, "nat_ip").writeText(ip) } catch (_: Throwable) {}
                            analytics?.setNatIp(ip)
                            return@Thread
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "SvcNatIpFetch"; start() }
    }

    private fun refreshTrafficStats() {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong()) {
            rxRate = -1; txRate = -1; return
        }
        val now = System.currentTimeMillis()
        if (lastStatsAt > 0) {
            val dtMs = (now - lastStatsAt).coerceAtLeast(1)
            rxRate = ((rx - lastRx) * 1000 / dtMs).coerceAtLeast(0)
            txRate = ((tx - lastTx) * 1000 / dtMs).coerceAtLeast(0)
        }
        lastRx = rx; lastTx = tx; lastStatsAt = now
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("proxy", "Proxy Agent", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        // Stamp the log with the running build at process-create time. Cheap
        // and one-shot per :proxy lifetime. The agent.log file is shared
        // across upgrades and can accumulate days of lines from older app
        // versions; without this marker, post-incident debugging guesses at
        // when each update landed. Surrounding `===` makes the line trivial
        // to grep for ("=== app v") and clearly separates from regular log.
        log("=== app v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) " +
            "pid=${android.os.Process.myPid()} ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            doStop(); return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getStringExtra("port") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val agentId = intent.getStringExtra("id")?.trim().orEmpty()
        val dnsRaw = intent.getStringExtra("dns")?.trim().orEmpty()
        val dns = dnsRaw.ifEmpty { "1.1.1.1,8.8.8.8" }
        engine = when (intent.getStringExtra("engine")) {
            "aar" -> Engine.AAR
            "binary" -> Engine.BINARY
            else -> Engine.NATIVE   // "native" or unset → default to native
        }
        mode = if (intent.getStringExtra("mode") == "balancer") Mode.BALANCER else Mode.MODEM
        quicImpl = intent.getStringExtra("quic_impl") ?: "kwik"
        if (host.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        // Defensive bind reset. The :proxy process survives stops in
        // NATIVE/BINARY (only AAR self-kills), so a previous Wi-Fi
        // return session could have left this process bound to cellular
        // even when the user has since unticked the checkbox. Without
        // an explicit reset here, all outbound traffic from :proxy
        // (registrator dial, NAT-IP probe, target dials) would silently
        // go through mobile data, with no visible "wifi_return"
        // signalling in the current session's logs. Costs nothing when
        // no bind exists. See also stopWifiRelayIfRunning where this
        // is also called on shutdown.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm?.boundNetworkForProcess != null) {
                log("Cleaning up stale process bind from previous session " +
                    "(boundNetworkForProcess=${cm.boundNetworkForProcess})")
                cm.bindProcessToNetwork(null)
            }
        } catch (_: Throwable) {}

        connStatus = ConnStatus.STARTING
        // Android 14+ ties FGS time-limits to the type. specialUse has no
        // 6h/24h dataSync cap, but the system silently treats absent type as
        // "unknown" and time-limits it too. Pass type explicitly.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(statusText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification(statusText()))
        }
        state("starting")
        writeConnInfo()
        // Persist a "was running" flag so PackageReplacedReceiver can decide
        // whether to auto-restart after an app update. PACKAGE_REPLACED kills
        // both processes without ever touching this file, so its presence
        // post-kill = the previous session was alive when the update landed.
        // doStop() (user STOP, auto-stop, onDestroy) removes it explicitly so
        // an intentional stop doesn't get unintentionally resurrected.
        try { File(filesDir, "was_running").writeText("1") } catch (_: Throwable) {}

        // Spin up the analytics recorder before any agent log lines arrive so
        // tunnel-open/close events are counted from the very first connection.
        analytics = AnalyticsRecorder(this)

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Proxy::WL")
            wakeLock?.acquire()
        } catch (_: Exception) {}

        // Status + speed updater: polls TrafficStats and battery once per second,
        // refreshes notification + conn_info file, and enforces battery /
        // no-internet auto-stops.
        Thread {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            var noInternetSince = 0L
            val noInternetGraceMs = 30_000L
            while (!stopRequested) {
                try {
                    refreshTrafficStats()
                    // Refresh Wi-Fi return status before writeConnInfo() so
                    // the UI sees state transitions within ~1s of Wi-Fi
                    // appearing/disappearing. Cheap: just a @Volatile read.
                    // Priority: split_failed (relay disabled, worst case)
                    // > leak_known (relay alive but target dials leak —
                    // BINARY engine expected case) > the wifi/fallback
                    // transient signals.
                    wifiReturnStatus = when {
                        wifiReturnSplitFailed -> "split_failed"
                        wifiReturnLeakKnown -> "leak_known"
                        wifiRelay != null -> if (wifiRelay!!.isUsingWifi()) "wifi" else "wifi_fallback"
                        else -> ""
                    }
                    nm.notify(1, buildNotification(statusText()))
                    writeConnInfo()
                    analytics?.tick()
                    maybeRefreshNatIp()

                    val threshold = readBatteryThreshold()
                    if (threshold > 0) {
                        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        if (level in 0..threshold) {
                            log("Auto-stop: battery $level% <= threshold $threshold%")
                            doStop("Battery $level% ≤ $threshold% — auto-stopped")
                            break
                        }
                    }

                    // System-level "no internet" detection. Stops the agent if OS
                    // reports no validated internet for `noInternetGraceMs`, instead
                    // of burning CPU/battery in the subprocess' dial loop.
                    //
                    // Suppressed while a REBOOT auto-cycle is in flight — that step
                    // intentionally kills cellular for up to ~90 seconds (airplane +
                    // rild restart, optionally RAT switch / APN swap / IMEI rotate),
                    // so a 30-second no-internet grace would trip mid-rotation and
                    // auto-stop the agent right as we're about to come back with a
                    // fresh IP. Manual ↻ from the UI doesn't hit this path because
                    // the activity stops the service before cycling.
                    val now = System.currentTimeMillis()
                    if (autoCycling) {
                        noInternetSince = 0L
                    } else if (!systemSaysInternetUp()) {
                        if (noInternetSince == 0L) noInternetSince = now
                        if (now - noInternetSince >= noInternetGraceMs) {
                            val secs = (now - noInternetSince) / 1000
                            log("Auto-stop: system reports no internet for ${secs}s")
                            doStop("No internet (${secs}s) — auto-stopped")
                            break
                        }
                    } else {
                        noInternetSince = 0L
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "StatusUpdater"; isDaemon = true; start() }

        log("Engine: ${engine.name}  Mode: ${mode.name}")
        val runner = when (engine) {
            Engine.NATIVE -> Thread { runNativeEngine(host, port, key, agentId, dns) }
            Engine.BINARY -> Thread { runBinaryEngine(host, port, key, agentId, dns) }
            Engine.AAR -> Thread { runAarEngine(host, port, key, agentId, dns) }
        }
        runner.name = "AgentRunner"
        runner.isDaemon = true
        runnerThread = runner
        runner.start()

        registerNetworkCallback()
        return START_REDELIVER_INTENT
    }

    // Resolves the host/port the SDK should dial. Normally it's the user-
    // configured (host, port). When the Wi-Fi return relay is enabled
    // (wifi_return=true, Modem mode), we spin the relay up here and return
    // a loopback (host, port) so the SDK connects to 127.0.0.1:<localPort>
    // — the relay then forwards each session to the real upstream with the
    // outgoing socket bound to a Wi-Fi Network when one is available, or
    // through the default route (cellular) when it isn't.
    //
    // Returns null host on failure to start the relay — caller falls back
    // to dialing the original (host, port) directly so a relay bug never
    // bricks the agent.
    private fun maybeStartWifiRelay(host: String, port: String): Pair<String, String> {
        // Balancer mode bypass: the SDK gets the real registrator (host,
        // port) from the balancer's JSON response and dials that directly,
        // so a loopback relay on (balancer_host, balancer_port) would only
        // see the GET /getRegistrator and miss the actual uplink. The UI
        // already greys out the checkbox in Balancer mode (MainActivity),
        // and the save handler stores wifi_return=false in that case, but
        // this is a second guard in case a stale cycle_cfg.json from a
        // previous Modem-mode save sticks around.
        if (mode != Mode.MODEM) return host to port
        val cfg = IpCycle.loadConfigFromFile(this)
        if (!cfg.wifiReturn) return host to port

        // Only "local_relay" is implemented. Future methods would dispatch
        // here on cfg.wifiReturnMethod; for now anything else logs + falls
        // back to direct dial so an unknown method doesn't silently disable
        // the agent.
        if (cfg.wifiReturnMethod != "local_relay") {
            log("wifi_return enabled with unsupported method=\"${cfg.wifiReturnMethod}\" — bypassing relay")
            return host to port
        }
        val portInt = port.toIntOrNull()
        if (portInt == null || portInt !in 1..65535) {
            log("wifi_return: invalid upstream port \"$port\"; bypassing relay")
            return host to port
        }
        // Engine-specific behaviour for the cellular process bind:
        //   - In-process engines (NATIVE, AAR): bindProcessToNetwork(cellular)
        //     so target dials default-route through cellular. Per-socket
        //     wifiNet.bindSocket() in the relay overrides this for the
        //     uplink only. Skip the relay if cellular isn't reachable —
        //     no point running with a guaranteed target-dial leak.
        //   - BINARY (forked subprocess): bindProcessToNetwork doesn't
        //     survive fork+exec, so the subprocess's target dials WILL
        //     egress through the default route (typically Wi-Fi). The
        //     UI normally prevents this combination (BINARY radio is
        //     disabled while the Wi-Fi return box is on — see
        //     applyEngineGateForWifiReturn). If a stale pref / direct
        //     file edit still lands us here, we keep the relay running:
        //     uplink savings work, target dials leak Wi-Fi. The self-test
        //     reports LEAK_DETECTED and the widget surfaces a warning
        //     status ("leak_known") rather than a hard failure.
        if (engine != Engine.BINARY) {
            if (!bindProcessToCellularBlocking()) {
                log("wifi_return: cellular network unavailable within budget; " +
                    "skipping relay to avoid leaking Wi-Fi IP to targets.")
                return host to port
            }
        } else {
            log("wifi_return: BINARY engine — relay will save mobile data on " +
                "uplink, but target dials inside the subprocess will egress " +
                "through Wi-Fi (default route). Self-test will report " +
                "LEAK_DETECTED; widget shows 'leak_known' status.")
        }
        try {
            val relay = WifiReturnRelay(
                context = this,
                upstreamHost = host,
                upstreamPort = portInt,
                log = { msg -> log("wifi-relay: $msg") },
            )
            val localPort = relay.start()
            wifiRelay = relay
            val bindInfo = if (cellularNet != null) "process bound to cellular $cellularNet"
                else "no process bind (BINARY engine — target dials may leak)"
            log("wifi_return: relay up on 127.0.0.1:$localPort → $host:$portInt ($bindInfo)")
            return "127.0.0.1" to localPort.toString()
        } catch (t: Throwable) {
            log("wifi_return: relay start failed (${t.message}) — unbinding and falling back to direct dial")
            unbindProcessFromCellular()
            return host to port
        }
    }

    // Acquires a cellular Network via requestNetwork and binds the :proxy
    // process to it so all sockets created after this — including the Go
    // SDK's target dials in the AAR engine — egress through cellular by
    // default. The relay's outbound sockets override this back to Wi-Fi
    // per-socket via wifiNet.bindSocket().
    //
    // Blocking up to 10s for the cellular Network to appear; returns false
    // on timeout / system error. Caller is expected to bail (skip relay)
    // on false because the alternative is silent Wi-Fi leakage.
    //
    // The callback survives this call: future onAvailable events re-bind
    // (handles cellular reattach after IP rotation), and onLost
    // deliberately does NOT unbind — leaving the process bound to a dead
    // network makes new sockets fail with ENETUNREACH, which is the
    // correct behaviour (we'd rather fail than silently leak to Wi-Fi).
    private fun bindProcessToCellularBlocking(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        val ref = arrayOf<Network?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ref[0] = network
                cellularNet = network
                try {
                    cm.bindProcessToNetwork(network)
                    log("wifi_return: bindProcessToNetwork(cellular=$network)")
                } catch (t: Throwable) {
                    log("wifi_return: bindProcessToNetwork failed: ${t.message}")
                }
                latch.countDown()
            }
            override fun onLost(network: Network) {
                if (cellularNet == network) {
                    // Deliberate: don't unbind. New sockets will fail with
                    // ENETUNREACH until a fresh cellular Network arrives
                    // (via onAvailable), at which point we re-bind to it.
                    // That's better than letting target dials silently
                    // leak to Wi-Fi during cellular outages.
                    log("wifi_return: cellular Network lost ($network); " +
                        "process stays bound — new target dials will fail " +
                        "until cellular reattaches")
                    cellularNet = null
                }
            }
        }
        return try {
            cm.requestNetwork(req, cb)
            cellularNetworkCallback = cb
            val ok = latch.await(10, TimeUnit.SECONDS)
            if (!ok || ref[0] == null) {
                log("wifi_return: cellular requestNetwork timed out (10s)")
                try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
                cellularNetworkCallback = null
                cellularNet = null
                return false
            }
            true
        } catch (t: Throwable) {
            log("wifi_return: requestNetwork(CELLULAR) failed: ${t.message}")
            try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
            cellularNetworkCallback = null
            cellularNet = null
            false
        }
    }

    private fun unbindProcessFromCellular() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cellularNetworkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
        cellularNetworkCallback = null
        cellularNet = null
        // Restore Android's default behaviour: no process-wide binding,
        // sockets follow the system's preferred default network.
        try {
            cm?.bindProcessToNetwork(null)
            log("wifi_return: process unbound from cellular (default routing restored)")
        } catch (_: Throwable) {}
    }

    private fun stopWifiRelayIfRunning() {
        val r = wifiRelay
        if (r != null) {
            wifiRelay = null
            try { r.stop() } catch (t: Throwable) {
                log("wifi_return: relay stop error: ${t.message}")
            }
            unregisterWifiReturnRetestCallback()
            // Reset the initial-test flag so a fresh start (e.g. user
            // retries by toggling and re-saving) gets a fresh
            // verification cycle.
            initialSelfTestDone = false
        }
        // ALWAYS unbind, even if `wifiRelay` was null when we got here.
        // Reason: bindProcessToCellularBlocking() commits the process
        // bind BEFORE relay startup. If the relay creation later threw
        // (or the service was killed by the OS between bind and assign),
        // `wifiRelay` ends up null but the bind is still live — and in
        // the NATIVE engine the :proxy process survives stop, so the
        // next session inherits a stale cellular bind that silently
        // funnels every outbound socket (including NAT-IP probes)
        // through mobile data instead of the default route. The
        // bindProcessToNetwork(null) call is a cheap no-op when nothing
        // is bound, so making this unconditional is safe.
        unbindProcessFromCellular()
    }

    // Shared split-routing failure handler. Both the initial self-test and
    // the post-rotation retest call this when SAME_IP comes back, instead
    // of each route capturing its own onSplitFail lambda. BINARY rolls back
    // the effective env vars and respawns the subprocess; AAR stops the
    // whole service (in-process Go env can't be re-initialised cleanly).
    private fun handleSplitFailureForCurrentEngine() {
        when (engine) {
            Engine.BINARY -> {
                log("wifi_return: rolling back BINARY engine to direct dial " +
                    "($originalHost:$originalPort)")
                effectiveHost = originalHost
                effectivePort = originalPort
                try { agentProcess?.destroy() } catch (_: Throwable) {}
                runnerThread?.interrupt()
            }
            Engine.AAR -> {
                log("wifi_return: AAR engine can't roll back in-process; auto-stopping")
                doStop("Wi-Fi return: split routing not confirmed — disable the checkbox to use cellular directly")
            }
            Engine.NATIVE -> {
                // Same posture as BINARY — the native agent reads
                // effectiveHost/Port on every dial loop iteration via
                // NativeProxyAgent.Config, so we just stop the current
                // agent and let the runner respawn with the rolled-back
                // (host, port). No subprocess to kill — stop the in-
                // process supervisor instead.
                log("wifi_return: rolling back NATIVE engine to direct dial " +
                    "($originalHost:$originalPort)")
                effectiveHost = originalHost
                effectivePort = originalPort
                try { nativeAgent?.stop() } catch (_: Throwable) {}
                runnerThread?.interrupt()
            }
        }
    }

    // Schedules the split-routing self-test after a short delay (lets the
    // Wi-Fi Network in WifiReturnRelay actually attach before we probe),
    // then re-arms on subsequent Wi-Fi changes for the lifetime of the
    // relay. Engine-specific rollback on SAME_IP is dispatched through
    // handleSplitFailureForCurrentEngine() — no per-call callback needed.
    private fun scheduleWifiReturnSelfTest() {
        // First run on a delayed thread. We don't tie this to a network
        // callback because we want a test to fire even if Wi-Fi was
        // already attached when the relay started.
        Thread {
            try { Thread.sleep(5_000L) } catch (_: InterruptedException) { return@Thread }
            if (stopRequested) return@Thread
            runWifiReturnSelfTestNow()
        }.apply { name = "WifiReturnSelfTest"; isDaemon = true; start() }

        registerWifiReturnRetestCallback()
    }

    private fun registerWifiReturnRetestCallback() {
        if (wifiReturnRetestCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Latch onto network change events: a new Wi-Fi SSID, a
            // disconnect/reconnect, an IP change due to DHCP renewal — any
            // of those invalidates the previous test's assumptions. We
            // debounce by 2s because Android often fires onAvailable
            // multiple times in quick succession during handover.
            @Volatile private var lastFireMs = 0L
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastFireMs < 2_000L) return
                lastFireMs = now
                if (stopRequested || wifiRelay == null) return
                // Skip retests during a REBOOT auto-cycle: airplane mode
                // toggling kills Wi-Fi too, and when it comes back this
                // callback fires, but it's NOT a genuine network change —
                // the underlying split-routing property hasn't changed,
                // only the cellular public IP has. We schedule exactly
                // one deliberate retest from triggerAutoIpCycle.finally
                // instead. Without this guard, every rotation triggers a
                // self-test that burns mobile data on the cellular probe
                // for no diagnostic value.
                if (autoCycling) {
                    log("wifi_return: skipping retest — IP rotation in flight")
                    return
                }
                log("wifi_return: Wi-Fi changed — re-running self-test")
                Thread {
                    // Give the new network ~3s to actually become usable
                    // (validation, captive-portal check, etc.) before
                    // hitting ipify on it.
                    try { Thread.sleep(3_000L) } catch (_: InterruptedException) { return@Thread }
                    // Re-check after the sleep — autoCycling might have
                    // started in the gap (manual REBOOT race).
                    if (stopRequested || autoCycling) return@Thread
                    runWifiReturnSelfTestNow()
                }.apply { name = "WifiReturnReTest"; isDaemon = true; start() }
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            wifiReturnRetestCallback = cb
        } catch (t: Throwable) {
            log("wifi_return: re-test callback register failed: ${t.message}")
        }
    }

    private fun unregisterWifiReturnRetestCallback() {
        val cb = wifiReturnRetestCallback ?: return
        wifiReturnRetestCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
    }

    // Tracks whether we've completed at least one self-test for the current
    // relay lifecycle. Resets when the relay is (re)started. Used to
    // distinguish the INITIAL test (must verify the feature actually works)
    // from later RETESTS (transient failures are tolerable). On initial
    // BOTH_FAILED we disable the relay; on retest BOTH_FAILED we keep it.
    @Volatile private var initialSelfTestDone: Boolean = false

    // Runs the actual test + persists wifi_info.json + dispatches result.
    // Guarded so re-trigger fires while a test is still in flight coalesce
    // into a no-op instead of stacking. Engine-specific rollback on
    // SAME_IP / LEAK_DETECTED / initial-BOTH_FAILED runs via
    // handleSplitFailureForCurrentEngine().
    private fun runWifiReturnSelfTestNow() {
        if (selfTestInFlight) {
            log("wifi_return: self-test already in flight; skipping retrigger")
            return
        }
        // Belt-and-braces: never burn the cellular probe while the radio
        // is mid-rotation. Callers already gate on autoCycling but this
        // covers any direct caller we add later.
        if (autoCycling) {
            log("wifi_return: refusing to self-test during IP rotation")
            return
        }
        selfTestInFlight = true
        try {
            // Snapshot the Wi-Fi link characteristics — these stay in
            // wifi_info.json even if the split-routing probe fails, so
            // the UI can still show what Wi-Fi network is attached.
            val wifiNet = wifiRelay?.currentWifiNetwork()
            val wifiSnap = WifiInfoProbe.snapshot(this, wifiNet)

            val report = SplitRoutingSelfTest.runTest(this)
            log("wifi_return: self-test result=${report.result} " +
                "wifi_ip=${report.wifiPublicIp} cell_ip=${report.cellPublicIp} " +
                "default_ip=${report.defaultPublicIp} took=${report.durationMs}ms " +
                "detail=\"${report.detail}\"")
            writeWifiInfoJson(report, wifiSnap)

            when (report.result) {
                SplitRoutingSelfTest.Result.SUCCESS -> {
                    log("wifi_return: split routing VERIFIED (wifi=${report.wifiPublicIp}, " +
                        "cell=${report.cellPublicIp}, default=${report.defaultPublicIp})")
                }
                SplitRoutingSelfTest.Result.SAME_IP -> {
                    log("wifi_return: SAME IP on both transports — relay would be ineffective and target's exit IP " +
                        "would be Wi-Fi, not cellular. Disabling relay per policy.")
                    // Flip the sticky flag BEFORE stopping the relay so
                    // the status updater (which polls 1Hz) never sees a
                    // transient "" state between relay-stop and our explicit
                    // status write. wifiReturnStatus itself is also primed
                    // so the very next conn_info write carries it even if
                    // the status thread hasn't ticked yet.
                    wifiReturnSplitFailed = true
                    wifiReturnStatus = "split_failed"
                    stopWifiRelayIfRunning()
                    writeConnInfo()
                    handleSplitFailureForCurrentEngine()
                }
                SplitRoutingSelfTest.Result.LEAK_DETECTED -> {
                    // The default-route probe came back with the Wi-Fi IP
                    // instead of cellular. Two scenarios with different
                    // remediation:
                    //
                    //   - In-process engine (NATIVE / AAR): this is
                    //     UNEXPECTED — bindProcessToNetwork(cellular) was
                    //     supposed to route target dials through cellular.
                    //     Something failed silently (ROM quirk, race with
                    //     network state). Disable the relay to avoid
                    //     exposing the Wi-Fi IP to targets.
                    //
                    //   - BINARY engine: this is EXPECTED. Subprocess
                    //     doesn't inherit bindProcessToNetwork, so target
                    //     dials go through the default route (Wi-Fi). The
                    //     user accepted this trade-off (UI normally
                    //     forces an in-process engine; getting here means
                    //     they explicitly chose BINARY). Keep the relay
                    //     running for the mobile-data savings on the
                    //     uplink, but flag the leak in the widget so
                    //     they're not surprised when targets see Wi-Fi IP.
                    if (engine == Engine.BINARY) {
                        log("wifi_return: LEAK detected on BINARY engine " +
                            "(default=${report.defaultPublicIp} == wifi). " +
                            "Known limitation — keeping relay for uplink savings, " +
                            "widget will show 'leak_known'.")
                        wifiReturnLeakKnown = true
                        wifiReturnStatus = "leak_known"
                        writeConnInfo()
                    } else {
                        log("wifi_return: LEAK DETECTED on in-process engine — " +
                            "default route=${report.defaultPublicIp} (== wifi ${report.wifiPublicIp}), " +
                            "expected cellular ${report.cellPublicIp}. " +
                            "Disabling relay to prevent target IP exposure.")
                        wifiReturnSplitFailed = true
                        wifiReturnStatus = "split_failed"
                        stopWifiRelayIfRunning()
                        writeConnInfo()
                        handleSplitFailureForCurrentEngine()
                    }
                }
                SplitRoutingSelfTest.Result.WIFI_PROBE_FAILED -> {
                    // Wi-Fi probe didn't get back; could be momentary
                    // captive portal flap. Don't disable — let the next
                    // re-test fire on the next network change.
                    log("wifi_return: Wi-Fi probe failed — keeping relay (may retest on next network change)")
                }
                SplitRoutingSelfTest.Result.CELL_PROBE_FAILED -> {
                    // Same logic — cellular momentarily unavailable. Worst
                    // case the relay falls back to the default route on
                    // its own and we re-test later.
                    log("wifi_return: cellular probe failed — keeping relay")
                }
                SplitRoutingSelfTest.Result.BOTH_FAILED -> {
                    // On INITIAL test (just-started relay): treat as
                    // verification failure and disable. Both probes failing
                    // at startup almost always means the system rejected
                    // our requestNetwork calls — e.g. missing
                    // CHANGE_NETWORK_STATE permission, or a hostile ROM
                    // that blocks parallel transport requests. Either way
                    // the relay would be running blind: we can't confirm
                    // split routing, can't verify the Wi-Fi binding stuck.
                    // Better to fail loud now than leak target dials.
                    //
                    // On RETESTS (after Wi-Fi changes / post-rotation):
                    // tolerate it. Could be a transient probe outage —
                    // captive-portal handover, cellular bouncing during a
                    // tower switch. The initial test already verified
                    // the feature works on this device.
                    if (!initialSelfTestDone) {
                        log("wifi_return: initial self-test failed BOTH probes — " +
                            "split routing cannot be verified. Disabling relay. " +
                            "Common cause: CHANGE_NETWORK_STATE permission missing " +
                            "from manifest, or system blocks parallel requestNetwork calls.")
                        wifiReturnSplitFailed = true
                        wifiReturnStatus = "split_failed"
                        stopWifiRelayIfRunning()
                        writeConnInfo()
                        handleSplitFailureForCurrentEngine()
                    } else {
                        log("wifi_return: both probes failed on retest — keeping relay (transient assumed; initial test already verified)")
                    }
                }
            }
            // Mark first test complete only after the result has been
            // acted on, so a SAME_IP / LEAK / initial-BOTH_FAILED that
            // leads to stop+rollback doesn't leave this true for the
            // (impossible) next test.
            initialSelfTestDone = true
        } catch (t: Throwable) {
            log("wifi_return: self-test crashed: ${t.message}")
        } finally {
            selfTestInFlight = false
        }
    }

    // Fires exactly one self-test after a REBOOT auto-cycle completes,
    // delayed enough for cellular + Wi-Fi to fully reattach. Idempotent
    // via runWifiReturnSelfTestNow's selfTestInFlight guard.
    //
    // Why we don't just rely on the Wi-Fi onAvailable retest callback
    // ────────────────────────────────────────────────────────────────
    // During rotation, airplane mode kills Wi-Fi too (default Android
    // behaviour). When Wi-Fi comes back, onAvailable fires — and on its
    // own that WOULD trigger a retest. We suppress those retests
    // explicitly (see registerWifiReturnRetestCallback) because:
    //   1. Every retest spends ~6s and one cellular HTTP request, which
    //      during a rotation storm (REBOOTs every few minutes) adds up.
    //   2. The split-routing property hasn't actually changed — only the
    //      cellular public IP has. We just need to refresh the cached
    //      value, not re-verify the routing.
    //   3. If we don't suppress, onAvailable might fire mid-cycle (some
    //      ROMs reattach Wi-Fi before cellular), the test fires, the
    //      cellular probe fails because radio is still bouncing, we cache
    //      a CELL_PROBE_FAILED result — and the UI panics for no reason.
    // So we run exactly one post-cycle test from here.
    private fun schedulePostRotationSelfTest() {
        Thread {
            // 5s settle: longer than the basic post-airplane-off reattach
            // wait (3s) so cellular is fully up and the new public IP is
            // reachable through ipify.
            try { Thread.sleep(5_000L) } catch (_: InterruptedException) { return@Thread }
            if (stopRequested || autoCycling || wifiRelay == null) return@Thread
            log("wifi_return: post-rotation self-test — refreshing cached IPs")
            runWifiReturnSelfTestNow()
        }.apply { name = "WifiReturnPostCycleTest"; isDaemon = true; start() }
    }

    // Persists the latest split-routing test result + Wi-Fi link info to
    // wifi_info.json. MainActivity reads this to render the widget's
    // two-IP display and feed the log-export header. Schema is
    // additive-only; older readers ignore unknown keys via JSONObject.opt*.
    private fun writeWifiInfoJson(
        report: SplitRoutingSelfTest.Report,
        wifi: WifiInfoProbe.Snapshot,
    ) {
        try {
            val o = org.json.JSONObject()
            o.put("public_ip_wifi", report.wifiPublicIp)
            o.put("public_ip_cell", report.cellPublicIp)
            o.put("public_ip_default", report.defaultPublicIp)
            o.put("link_speed_mbps", wifi.linkSpeedMbps)
            o.put("frequency_mhz", wifi.frequencyMhz)
            o.put("band", wifi.band)
            o.put("standard", wifi.standard)
            o.put("wifi_attached", wifi.attached)
            o.put("test_result", report.result.name)
            o.put("test_detail", report.detail)
            o.put("test_duration_ms", report.durationMs)
            o.put("tested_at_ms", System.currentTimeMillis())
            File(filesDir, "wifi_info.json").writeText(o.toString())
        } catch (t: Throwable) {
            log("wifi_return: wifi_info.json write failed: ${t.message}")
        }
    }

    // In-process engine using the pure-Kotlin port of the SDK (see
    // com.proxyagent.app.nativeagent.NativeProxyAgent). Default engine
    // for new installs — no subprocess, no Go runtime, no JNI. Speaks
    // the same TCP/QUIC wire protocol as the binary and AAR engines, so
    // it pairs with the same registrator infrastructure.
    //
    // Logging is bridged into parseAgentLine() via a LogSink that emits
    // the same line vocabulary the binary/AAR engines write to stdout —
    // "uplink connected ... transport=tcp", "opening tunnel target=...",
    // "REBOOT received from registrator reason=...", etc. — so the
    // existing status parser keeps working without changes.
    private fun runNativeEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        try {
            // Same Wi-Fi return wiring as the AAR engine — see
            // maybeStartWifiRelay() for the conditions. NATIVE also
            // runs in-process so bindProcessToNetwork(cellular) sticks.
            originalHost = host
            originalPort = port
            val initialEffective = maybeStartWifiRelay(host, port)
            effectiveHost = initialEffective.first
            effectivePort = initialEffective.second
            val relayActive = effectiveHost != host
            val hostLog = if (relayActive) "$effectiveHost:$effectivePort→$host:$port" else host
            log("Native engine: host=$hostLog port=$port key=${mask(key)} " +
                "id=${if (agentId.isEmpty()) "<empty>" else mask(agentId)} dns=$dns")
            if (wifiRelay != null) scheduleWifiReturnSelfTest()

            // Outer respawn loop. The native supervisor handles its own
            // dial/reconnect/backoff internally; we wrap that in a
            // BINARY-style outer loop so any external stop+restart
            // (forceReconnect on network change, wifi_return split-
            // routing rollback) picks up the latest effectiveHost/Port.
            var outerBackoffMs = 1_000L
            while (!stopRequested) {
                val effHost = effectiveHost
                val effPort = effectivePort
                val portInt = effPort.toIntOrNull()
                if (portInt == null || portInt !in 1..65535) {
                    log("Native engine: invalid port \"$effPort\"")
                    connStatus = ConnStatus.ERROR; state("error"); writeConnInfo()
                    return
                }

                val agent = com.proxyagent.app.nativeagent.NativeProxyAgent()
                nativeAgent = agent

                // Bridge native log lines into parseAgentLine() so the UI
                // (currentRegistrator, transport badge, tunnel counter,
                // REBOOT auto-cycle) keeps working unchanged. Format
                // mirrors the Go SDK's structured log line.
                agent.setLogSink { level, msg, fields ->
                    val sb = StringBuilder()
                    sb.append("level=").append(level).append(" msg=\"").append(msg).append('"')
                    for ((k, v) in fields) {
                        if (v == null) continue
                        sb.append(' ').append(k).append('=').append(v.toString())
                    }
                    val line = sb.toString()
                    parseAgentLine(line)
                    log("[native] $line")
                }

                // REBOOT routing: NativeProxyAgent emits the same
                // "REBOOT received from registrator reason=..." log
                // line as the Go SDK, and parseAgentLine (called from
                // the LogSink above) already triggers
                // triggerAutoIpCycle off it. We deliberately do NOT
                // wire setRebootListener here — both paths would fire
                // for the same REBOOT, and although triggerAutoIpCycle
                // is idempotent via the autoCycling flag, the duplicate
                // call adds a confusing "Auto IP-cycle already in
                // progress; ignoring REBOOT" line to every rotation.
                // The typed listener stays on NativeProxyAgent's public
                // API for third-party integrators who don't wire a
                // LogSink.

                val cfg = com.proxyagent.app.nativeagent.NativeProxyAgent.Config(
                    registratorHost = if (mode == Mode.MODEM) effHost else null,
                    registratorPort = if (mode == Mode.MODEM) portInt else 0,
                    balancerHost = if (mode == Mode.BALANCER) host else null,
                    balancerPort = if (mode == Mode.BALANCER) port.toIntOrNull() ?: 0 else 0,
                    fallbackFileUrl = if (mode == Mode.BALANCER)
                        "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json"
                    else null,
                    agentKey = key,
                    agentUuid = agentId.ifBlank { null },
                    dnsServers = dns,
                    workDir = filesDir,
                    httpTimeoutMs = 5000,
                    dialTimeoutMs = 5000,
                    heartbeatIntervalSec = 60,
                    enableHeartbeat = true,
                    // QUIC factory chosen by the user in Settings (key
                    // `quic_impl`), delivered via the start intent (see
                    // the `quicImpl` field — reading SharedPreferences
                    // here would get a stale cached value in the :proxy
                    // process). Both ship in the APK so we A/B between the
                    // in-house stack and the kwik library without a rebuild.
                    // Default kwik until the in-house QUIC is field-
                    // validated (Phase 11 in nativeagent/quic/DESIGN.md).
                    quicTransportFactory = when (quicImpl) {
                        "native" -> com.proxyagent.app.nativeagent.quic.NativeQuicTransport.Factory()
                        else -> com.proxyagent.app.nativeagent.KwikQuicTransport.Factory()
                    },
                )

                connStatus = ConnStatus.CONNECTING
                if (!agent.start(cfg)) {
                    log("Native engine: agent.start returned false")
                    return
                }
                state("running")

                // Block until the agent's supervisor exits (forceReconnect
                // / split-rollback / fatal error) or the user requests
                // stop. The supervisor handles disconnect/reconnect by
                // itself between these events.
                while (!stopRequested && agent.getStatus().running) {
                    try { Thread.sleep(500) }
                    catch (_: InterruptedException) { break }
                }
                nativeAgent = null
                try { agent.stop(timeoutMs = 2_000L) } catch (_: Throwable) {}
                if (stopRequested) break

                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                log("Native engine: respawning in ${outerBackoffMs}ms")
                try { Thread.sleep(outerBackoffMs) }
                catch (_: InterruptedException) {
                    log("Native engine: respawn backoff interrupted; retrying now")
                    outerBackoffMs = 1_000L
                    continue
                }
                outerBackoffMs = (outerBackoffMs * 2).coerceAtMost(30_000L)
            }
            log("Native runner loop exited")
        } catch (e: Throwable) {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            log("Native engine error: $sw")
            connStatus = ConnStatus.ERROR
            state("error"); writeConnInfo()
        }
    }

    private fun runBinaryEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        val binary = File(applicationInfo.nativeLibraryDir, "libproxyagent.so")
        if (!binary.exists()) {
            log("ERROR: libproxyagent.so missing at ${binary.absolutePath}")
            connStatus = ConnStatus.ERROR
            state("error"); writeConnInfo()
            return
        }
        try { binary.setExecutable(true, false) } catch (_: Throwable) {}
        log("Binary: ${binary.absolutePath} size=${binary.length()}")
        // Spin up the Wi-Fi return relay (if enabled) BEFORE the runner loop
        // so the very first dial of the subprocess uses the loopback address.
        // The relay lives across subprocess respawns (the agent reconnect /
        // SDK backoff loop dials repeatedly); we only tear it down in doStop.
        //
        // originalHost/Port + effectiveHost/Port are fields on the service so
        // handleSplitFailureForCurrentEngine() can mutate effective→original
        // mid-flight on a SAME_IP self-test failure. The runner loop reads
        // the @Volatile effective* fields each iteration, so a destroy()
        // from the failure handler trips readLine EOF and the next pb.start
        // picks up the new values automatically.
        originalHost = host
        originalPort = port
        val initialEffective = maybeStartWifiRelay(host, port)
        effectiveHost = initialEffective.first
        effectivePort = initialEffective.second
        if (wifiRelay != null) scheduleWifiReturnSelfTest()

        var backoffMs = 1000L
        while (!stopRequested) {
            try {
                // effectiveHost/effectivePort may point at the loopback relay
                // (Wi-Fi return) instead of the real registrator. Log the real
                // upstream too so debugging stays sane.
                val relayActive = effectiveHost != host
                val hostLog = if (relayActive) "$effectiveHost:$effectivePort→$host:$port" else host
                log("Launching subprocess: mode=${mode.name} host=$hostLog port=$port key=${mask(key)} id=${if (agentId.isEmpty()) "<empty>" else mask(agentId)} dns=$dns")
                connStatus = ConnStatus.CONNECTING
                val pb = ProcessBuilder(binary.absolutePath).redirectErrorStream(true)
                pb.environment().apply {
                    put("agent_key", key)
                    put("enable_netagent", "true")
                    put("HOME", filesDir.absolutePath)
                    put("TMPDIR", cacheDir.absolutePath)
                    put("dns_servers", dns)
                    when (mode) {
                        Mode.MODEM -> {
                            // Direct registrator: SDK skips balancer/fallback when both
                            // registrator_host AND registrator_port are set.
                            put("registrator_host", effectiveHost)
                            put("registrator_port", effectivePort)
                            if (agentId.isNotEmpty()) put("agent_uuid", agentId)
                        }
                        Mode.BALANCER -> {
                            // Balancer path is never routed through the Wi-Fi
                            // relay (see maybeStartWifiRelay) — these always
                            // get the real (host, port).
                            put("balancer_host", host)
                            put("balancer_port", port)
                            put("fallback_file_url",
                                "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
                        }
                    }
                }
                val proc = pb.start()
                agentProcess = proc
                state("running")
                log("Subprocess started")

                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    parseAgentLine(line)
                    log("[agent] $line")
                }
                val code = proc.waitFor()
                agentProcess = null
                log("Subprocess exited code=$code")
                if (stopRequested) break
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                backoffMs = 1000L
            } catch (e: Throwable) {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                log("Subprocess error: $sw")
                if (stopRequested) break
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
            }
            log("Restarting in ${backoffMs}ms")
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                // Woken up by forceReconnect (network change). Skip backoff.
                log("Backoff interrupted; retrying now")
                backoffMs = 1000L
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        log("Runner loop exited")
    }

    // In-process engine using the gomobile AAR (proxyagent.sdk.agent.Agent).
    //
    // Go's runtime caches env into runtime.envs at JNI_OnLoad time and never
    // re-reads it afterwards. libc's setenv() does NOT update that cache, so
    // any config we want Go to see must be in libc's environ BEFORE
    // libgojni.so is loaded.
    //
    // To prevent ART from eagerly resolving go.Seq / Agent during method
    // verification (which would load the .so before our setenv runs), we
    // pull those classes via Class.forName AFTER setenv. This pushes the
    // System.loadLibrary("gojni") call past our environment setup.
    //
    // On stop we kill the :proxy process so the next start re-initializes
    // with fresh env.
    private fun runAarEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        try {
            log("Capturing native stdout/stderr…")
            captureNativeOutput()

            // Same Wi-Fi return wiring as the binary engine — see
            // maybeStartWifiRelay() for the conditions. The AAR's in-process
            // Go runtime also dials whatever (host, port) we set in env, so
            // the loopback substitution works identically. Note: the relay
            // lives in the :proxy process alongside the Go runtime, so it
            // dies with the AAR's process kill in doStop (no separate
            // teardown ordering needed beyond stopWifiRelayIfRunning()).
            originalHost = host
            originalPort = port
            val initialEffective = maybeStartWifiRelay(host, port)
            effectiveHost = initialEffective.first
            effectivePort = initialEffective.second
            val relayActive = effectiveHost != host
            val hostLog = if (relayActive) "$effectiveHost:$effectivePort→$host:$port" else host
            log("Setting environment: mode=${mode.name} host=$hostLog port=$port key=${mask(key)} id=${if (agentId.isEmpty()) "<empty>" else mask(agentId)} dns=$dns")

            // Self-test for AAR: can't roll back in-process (Go env is
            // cached at JNI_OnLoad), so on split-routing failure we must
            // auto-stop the whole service (see handleSplitFailureForCurrentEngine).
            if (wifiRelay != null) scheduleWifiReturnSelfTest()
            // The SDK's Go config helper checks both lowercase ("balancer_host")
            // and SCREAMING_SNAKE ("BALANCER_HOST") names — set both so we
            // don't depend on the SDK's casing convention.
            fun setBoth(name: String, value: String) {
                Os.setenv(name, value, true)
                Os.setenv(name.uppercase(Locale.ROOT), value, true)
            }
            setBoth("agent_key", key)
            setBoth("enable_netagent", "true")
            setBoth("dns_servers", dns)
            Os.setenv("HOME", filesDir.absolutePath, true)
            Os.setenv("TMPDIR", cacheDir.absolutePath, true)

            when (mode) {
                Mode.MODEM -> {
                    // Direct registrator: SDK has no Java setRegistrator helper,
                    // so we rely on env vars (config.FromEnvAndFlags reads
                    // registrator_host/REGISTRATOR_HOST). Set BEFORE Go runtime
                    // initializes via Class.forName("go.Seq") below.
                    // effectiveHost/effectivePort point at the Wi-Fi return
                    // relay's loopback address when that feature is enabled;
                    // otherwise they're identical to host/port.
                    setBoth("registrator_host", effectiveHost)
                    setBoth("registrator_port", effectivePort)
                    if (agentId.isNotEmpty()) setBoth("agent_uuid", agentId)
                }
                Mode.BALANCER -> {
                    // Balancer never goes through the Wi-Fi relay — see
                    // maybeStartWifiRelay() for the reason.
                    setBoth("balancer_host", host)
                    setBoth("balancer_port", port)
                    setBoth("fallback_file_url",
                        "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
                }
            }

            connStatus = ConnStatus.CONNECTING

            // Diagnostic: dump what libc reports back, to confirm setenv stuck
            // in the current process. Shorter form so the agent_key isn't logged.
            try {
                val k = Os.getenv("agent_key") ?: "<null>"
                val keyMsg = "agent_key=${if (k == "<null>") k else "set(${k.length}b)"}"
                when (mode) {
                    Mode.MODEM -> {
                        val rh = Os.getenv("registrator_host") ?: "<null>"
                        val rp = Os.getenv("registrator_port") ?: "<null>"
                        val uu = Os.getenv("agent_uuid") ?: "<null>"
                        log("libc env check: registrator_host=$rh registrator_port=$rp agent_uuid=${if (uu == "<null>") uu else "set(${uu.length}b)"} $keyMsg")
                    }
                    Mode.BALANCER -> {
                        val h = Os.getenv("balancer_host") ?: "<null>"
                        val p = Os.getenv("balancer_port") ?: "<null>"
                        log("libc env check: balancer_host=$h balancer_port=$p $keyMsg")
                    }
                }
            } catch (_: Throwable) {}

            log("Loading Go runtime via Class.forName(\"go.Seq\")…")
            val seqClass = Class.forName("go.Seq")
            seqClass.getMethod("setContext", android.content.Context::class.java)
                .invoke(null, applicationContext)

            log("Loading Agent class via Class.forName…")
            val agentClass = Class.forName("proxyagent.sdk.agent.Agent")

            // Newer SDKs expose Java setters that call Go's os.Setenv internally
            // — that's the only way to get values into runtime.envs from JNI.
            // Fall back to setenv-only on older AARs that don't have them.
            var sdkSettersOk = true
            fun callSetter(name: String, argTypes: Array<Class<*>>, vararg args: Any?) {
                try {
                    agentClass.getMethod(name, *argTypes).invoke(null, *args)
                } catch (t: NoSuchMethodException) {
                    sdkSettersOk = false
                    log("Agent.$name not found — falling back to libc setenv only")
                } catch (t: Throwable) {
                    sdkSettersOk = false
                    log("Agent.$name error: ${t.message}")
                }
            }
            val portLong = port.toLongOrNull() ?: 0L
            callSetter("setAgentKey",
                arrayOf<Class<*>>(String::class.java), key)
            callSetter("setEnableNetAgent",
                arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!), true)
            if (mode == Mode.BALANCER) {
                callSetter("setBalancer",
                    arrayOf<Class<*>>(String::class.java, Long::class.javaPrimitiveType!!),
                    host, portLong)
                callSetter("setFallbackURL",
                    arrayOf<Class<*>>(String::class.java),
                    "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
            }
            // For modem mode, registrator_host/port/agent_uuid go via env only —
            // the SDK has no Java setRegistrator/setAgentUUID at this AAR version.
            log("SDK setters ${if (sdkSettersOk) "applied" else "partial — some fell back to env"}")

            try {
                agentClass.getMethod("setDNSServers", String::class.java).invoke(null, dns)
                log("Agent.setDNSServers applied")
            } catch (t: Throwable) {
                log("Agent.setDNSServers unavailable: ${t.message}")
            }

            log("Calling Agent.startAgent()")
            agentClass.getMethod("startAgent").invoke(null)
            state("running")
            log("Agent.startAgent returned")
        } catch (e: Throwable) {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            log("AAR engine error: $sw")
            connStatus = ConnStatus.ERROR
            state("error"); writeConnInfo()
        }
    }

    // Pipe Go's stdout/stderr (fd 1/2) into our log so log parsing works.
    // Also tail logcat for tags gomobile typically writes to.
    private fun captureNativeOutput() {
        try {
            val fds = Os.pipe()
            val readFd = fds[0]
            val writeFd = fds[1]
            Os.dup2(writeFd, 1)
            Os.dup2(writeFd, 2)
            Os.close(writeFd)
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(FileInputStream(readFd)))
                    while (true) {
                        val line = reader.readLine() ?: break
                        parseAgentLine(line)
                        log("[go] $line")
                    }
                } catch (_: Throwable) {}
            }.apply { name = "NativeStdoutReader"; isDaemon = true; start() }
        } catch (e: Throwable) {
            log("stdout capture failed: ${e.message}")
        }

        try {
            val proc = ProcessBuilder(
                "logcat", "-T", "1", "-v", "time",
                "GoLog:V", "Go:V", "*:S"
            ).redirectErrorStream(true).start()
            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        parseAgentLine(line)
                        log("[logcat] $line")
                    }
                } catch (_: Throwable) {}
            }.apply { name = "LogcatTailer"; isDaemon = true; start() }
        } catch (e: Throwable) {
            log("logcat tail failed: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var lastNet: Network? = null
                override fun onAvailable(network: Network) {
                    val prev = lastNet
                    lastNet = network
                    if (prev != null && prev != network) {
                        forceReconnect("network changed: $prev → $network")
                    } else {
                        log("Network available: $network")
                    }
                }
                override fun onLost(network: Network) {
                    log("Network lost: $network")
                    if (lastNet == network) lastNet = null
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Fires on every small change; we rely on onAvailable for actual switches.
                }
            }
            networkCallback = cb
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
        } catch (e: Throwable) {
            log("Network callback register failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        networkCallback = null
    }

    // Immediately tears down the current WS connection and wakes the backoff so a
    // fresh TCP socket is established on the new interface. Called when the default
    // network swaps (WiFi ↔ cellular, or AP change) — the old socket is bound to an
    // IP that's no longer valid.
    private fun forceReconnect(reason: String) {
        log("Force reconnect: $reason")
        when (engine) {
            Engine.BINARY -> {
                try { agentProcess?.destroy() } catch (_: Throwable) {}
                runnerThread?.interrupt()
            }
            Engine.AAR -> {
                // AAR engine handles network changes internally via the Go-side
                // dial loop; nothing to tear down here.
            }
            Engine.NATIVE -> {
                // Same model as AAR: the native supervisor's dial loop
                // re-resolves and re-dials each iteration. Stopping the
                // current uplink wakes the loop without tearing down the
                // supervisor, so it reconnects on the new interface.
                try { nativeAgent?.stop() } catch (_: Throwable) {}
                runnerThread?.interrupt()
            }
        }
    }

    private fun doStop(autoStopReason: String = "") {
        if (stopRequested) return
        stopRequested = true
        // Clear the auto-restart flag — any path through doStop is an
        // intentional shutdown (user STOP, battery auto-stop, onDestroy from
        // the system, Wi-Fi-return split rollback), and we don't want
        // PackageReplacedReceiver to resurrect the session if an update
        // happens to land while we're still cleaning up.
        try { File(filesDir, "was_running").delete() } catch (_: Throwable) {}
        unregisterNetworkCallback()
        try { analytics?.flush() } catch (_: Throwable) {}
        // Tear down the Wi-Fi return relay before we stop the engine so the
        // SDK's last reconnect attempt (if any) sees a dead loopback port
        // and gives up cleanly instead of trying to dial through a relay
        // that's about to lose its accept thread mid-handshake.
        stopWifiRelayIfRunning()
        // Clear the conn_info field 8 indicator so the UI doesn't keep
        // showing "via Wi-Fi" after we've stopped. The status updater
        // thread is also about to exit, so we can't rely on its 1Hz tick
        // to do this for us. Also clear the sticky flags so a subsequent
        // start gets a fresh test verdict.
        wifiReturnStatus = ""
        wifiReturnSplitFailed = false
        wifiReturnLeakKnown = false
        when (engine) {
            Engine.BINARY -> try {
                agentProcess?.let { p ->
                    log("Terminating subprocess")
                    p.destroy()
                    try {
                        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            log("Subprocess did not exit gracefully; forcing")
                            p.destroyForcibly()
                        }
                    } catch (_: InterruptedException) { p.destroyForcibly() }
                }
            } catch (t: Throwable) { log("Stop error: ${t.message}") }
            Engine.AAR -> try {
                log("Calling Agent.stopAgent()")
                Class.forName("proxyagent.sdk.agent.Agent")
                    .getMethod("stopAgent").invoke(null)
            } catch (t: Throwable) { log("Agent.stopAgent error: ${t.message}") }
            Engine.NATIVE -> try {
                log("Stopping native agent")
                nativeAgent?.stop(timeoutMs = 3_000L)
                nativeAgent = null
            } catch (t: Throwable) { log("Native stop error: ${t.message}") }
        }
        agentProcess = null
        connStatus = ConnStatus.STOPPED
        currentRegistrator = ""
        activeTunnels = 0
        connectedSinceMs = 0L
        currentUplinkTransport = ""
        state(if (autoStopReason.isNotEmpty()) "auto_stopped" else "stopped")
        try {
            if (autoStopReason.isNotEmpty())
                File(filesDir, "stop_reason").writeText(autoStopReason)
            else
                File(filesDir, "stop_reason").delete()
        } catch (_: Throwable) {}
        writeConnInfo()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        if (autoStopReason.isNotEmpty()) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val n = NotificationCompat.Builder(this, "proxy")
                    .setContentTitle("Proxy Agent stopped")
                    .setContentText(autoStopReason)
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .build()
                nm.notify(2, n)
            } catch (_: Throwable) {}
        }
        stopSelf()

        // libc setenv does not update Go's cached env after runtime init, so the
        // AAR engine cannot be cleanly restarted in the same process. Killing
        // the :proxy process forces the next Start to load a fresh Go runtime
        // with the new env. The binary engine re-execs the subprocess instead,
        // so it doesn't need this.
        if (engine == Engine.AAR) {
            Thread {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                android.os.Process.killProcess(android.os.Process.myPid())
            }.apply { name = "AarProcKiller"; isDaemon = true; start() }
        }
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
