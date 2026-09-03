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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
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
    // SDK: the same Kotlin engine, but consumed as an external AAR from
    // the proxy-agent-sdk-go repo instead of this app's in-tree copy —
    // the two run identical code, so any behavioural difference between
    // NATIVE and SDK is a packaging problem, which is exactly what this
    // engine exists to expose. Only present when the SDK build is
    // available (BuildConfig.SDK_ENGINE_AVAILABLE).
    enum class Engine { NATIVE, BINARY, SDK }

    // True for both in-process Kotlin engines. Most engine-conditional
    // logic cares about "in-process Kotlin agent vs forked Go binary",
    // not about which of the two Kotlin ones it is.
    private val Engine.isInProcessKotlin: Boolean get() = this != Engine.BINARY

    enum class Mode { MODEM, BALANCER }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false
    @Volatile private var agentProcess: Process? = null
    @Volatile private var runnerThread: Thread? = null
    @Volatile private var engine: Engine = Engine.NATIVE
    // Live in-process agent — set for engine=NATIVE and engine=SDK. Held
    // behind AgentHandle so the byte-counter and stop() call sites below
    // don't care which of the two produced it.
    @Volatile private var nativeAgent: com.proxyagent.app.engine.AgentHandle? = null
    @Volatile private var mode: Mode = Mode.MODEM
    // QUIC implementation choice ("kwik" | "native"), passed via the
    // start intent (NOT SharedPreferences — the :proxy process caches
    // prefs and misses cross-process writes from the settings UI).
    // Default is the in-house QUIC stack; kwik adapter is kept compiled
    // as a manual override but no longer exposed through the Settings UI
    // (the picker was removed). Set via the optional `quic_impl=kwik`
    // start intent extra for the rare regression-debug scenario.
    @Volatile private var quicImpl: String = "native"
    // User-selected network profile. Passed in via the start intent
    // (same rationale as quicImpl — cross-process SharedPreferences
    // is unreliable from :proxy). Defaults to LOW_100 — matches the
    // app-side default; most mobile/Wi-Fi uplinks fall below
    // 100 Mbps in practice and smaller buffers there cap worst-case
    // bufferbloat. Only the NATIVE engine honors this; binary engine
    // ignores it (no env hooks in libproxyagent.so — logged as a
    // warning at runBinary start so operators see why their setting
    // did nothing).
    @Volatile private var networkProfile: com.proxyagent.app.nativeagent.quic.NetworkProfile =
        com.proxyagent.app.nativeagent.quic.NetworkProfile.LOW_100
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var analytics: AnalyticsRecorder? = null
    @Volatile private var lastNatRefreshMs = 0L
    // Loopback relay that puts the agent↔registrator uplink on Wi-Fi while
    // outbound target dials stay on cellular. Lifecycle owned by the runX
    // engine call sites — see runNativeEngine / runBinaryEngine. Lives only
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
        // Bump on every new field added to writeConnInfo's pipe-delimited
        // line. Readers can use this to switch parsing strategy when the
        // tail grows — though getOrNull(N) keeps forward-compat free.
        //   v1: + heartbeat / pid / schema version (fields 9-11)
        //   v2: + Wi-Fi return session byte counters (fields 12-17)
        private const val CONN_INFO_SCHEMA_VERSION = 2
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
            // counts. Fields 12-17 are Wi-Fi return session byte counters,
            // refreshed on every writeConnInfo from the live relay +
            // native agent (zero when relay is off — MainActivity hides
            // the session-traffic widget line in that case). Readers must
            // use getOrNull(N) for forward compatibility so the tail
            // fields stay optional if a downgrade ever writes shorter
            // rows. `|` is escaped in cycleStage so a stray pipe in a
            // log line can't shift the field count.
            val safeStage = cycleStage.replace('|', '/')
            val pid = android.os.Process.myPid()
            val relay = wifiRelay
            val wifiUp = relay?.wifiUpBytes() ?: 0L
            val wifiDown = relay?.wifiDownBytes() ?: 0L
            val fbUp = relay?.fallbackUpBytes() ?: 0L
            val fbDown = relay?.fallbackDownBytes() ?: 0L
            val tgtUp = nativeAgent?.targetUpBytes() ?: 0L
            val tgtDown = nativeAgent?.targetDownBytes() ?: 0L
            File(filesDir, "conn_info").writeText(
                "${connStatus.name}|$rxRate|$txRate|$currentRegistrator|$activeTunnels|$connectedSinceMs|$currentUplinkTransport|$safeStage|$wifiReturnStatus|${System.currentTimeMillis()}|$pid|$CONN_INFO_SCHEMA_VERSION|$wifiUp|$wifiDown|$fbUp|$fbDown|$tgtUp|$tgtDown"
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
    // here — keeping the old keys means older binaries keep parsing correctly
    // during the rollout.
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
                // reports its outcome on the first bridge call. The BINARY
                // engine reliably does splice via Go's io.Copy on TCPConn,
                // so for it we keep "TCP (splice)" as before.
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
                            engine.isInProcessKotlin &&
                                (currentUplinkTransport == "TCP (splice)" ||
                                 currentUplinkTransport == "TCP (NIO)") ->
                                    currentUplinkTransport
                            engine.isInProcessKotlin -> "TCP"
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
                if (engine.isInProcessKotlin) currentUplinkTransport = "TCP (splice)"
            }
            line.contains("splice: NIO fallback engaged") -> {
                if (engine.isInProcessKotlin && currentUplinkTransport != "TCP (splice)") {
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
        // Cross-process rotation guard: ignore this REBOOT if another rotation
        // (a manual ↻ in :main, or one that just finished) still holds the lock
        // or we're inside the post-rotation cooldown. User-toggleable in
        // Settings; loadConfigFromFile reads the cross-process file that :main
        // mirrors the SharedPreferences into.
        val cfg = IpCycle.loadConfigFromFile(this)
        val gate = IpCycle.checkRotationGate(this, cfg)
        if (!gate.allowed) {
            val detail = if (gate.reason == "cooldown")
                "cooldown ${gate.remainingMs / 1000 + 1}s left"
            else "another rotation in progress"
            log("REBOOT ignored — rotation lock active ($detail) (reason=\"$reason\")")
            return
        }
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
                // cfg was loaded above (before the rotation-lock gate) from the
                // cross-process file MainActivity mirrors settings into —
                // SharedPreferences won't work here: this service runs in the
                // :proxy process and its prefs in-memory cache wouldn't see
                // changes made in :main.
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
                    // For NATIVE this stops the in-process supervisor and
                    // wakes the runner so it respawns on the new IP.
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
            // Null-safe cast: this runs at :proxy process-create, potentially
            // very early after a reboot auto-start. An unchecked cast that threw
            // here would crash the process before the first startForeground and
            // leave no notification at all. NotificationManager is a core service
            // so null is virtually impossible, but a missing channel would make
            // startForeground silently no-op the notification — cheap to guard.
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(
                    NotificationChannel("proxy", "Proxy Agent", NotificationManager.IMPORTANCE_LOW))
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

    // Wraps startForeground with the correct FGS type for the SDK level and,
    // crucially, does NOT let a throw escape. On an OEM that doesn't honor the
    // BOOT_COMPLETED exemption for a specialUse FGS, or if Android tightens the
    // rules, this call can throw ForegroundServiceStartNotAllowedException /
    // MissingForegroundServiceType / ForegroundServiceDidNotStartInTimeException.
    // BootReceiver's try/catch only covers startForegroundService(), not this
    // downstream call — an unwrapped throw here would crash :proxy silently with
    // no shade signal. Returns true on success. On failure posts a best-effort
    // fallback notification so a headless reboot still leaves something visible.
    private fun startForegroundSafe(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(statusText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification(statusText()))
        }
        // A prior boot-time auto-restart may have left an "auto-restart blocked"
        // fallback (id 3) in the shade. Now that we're foreground, clear it so
        // it doesn't sit misleadingly next to the live status notification.
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(3)
        } catch (_: Throwable) {}
        true
    } catch (t: Throwable) {
        log("startForeground failed: ${t.javaClass.simpleName}: ${t.message}")
        postForegroundFailedNotification(t)
        false
    }

    // Best-effort visible signal when startForeground itself fails (so the
    // outage isn't completely silent). Mirrors BootReceiver's fallback and
    // reuses notification id 3. Subject to POST_NOTIFICATIONS on API 33+ — if
    // that's denied this is a no-op, which is the same blind spot the FGS
    // notification has; see ADMIN_GUIDE on granting it for headless fleets.
    private fun postForegroundFailedNotification(cause: Throwable) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(this, "proxy")
                .setContentTitle("Proxy Agent — couldn't start foreground service")
                .setContentText("Tap to open and press START. Reason: ${cause.javaClass.simpleName}")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm.notify(3, n)
        } catch (_: Throwable) {}
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
            "binary" -> Engine.BINARY
            // A stale "sdk" pref can outlive an APK that had the SDK
            // compiled in (e.g. after an OTA of a build without it), so
            // gate on the build flag rather than trusting the pref.
            "sdk" -> if (BuildConfig.SDK_ENGINE_AVAILABLE) Engine.SDK else {
                log("Engine: 'sdk' requested but not compiled into this build — " +
                    "falling back to NATIVE (${com.proxyagent.app.engine.SdkEngineProvider.describe()})")
                Engine.NATIVE
            }
            else -> Engine.NATIVE   // "native" or unset → default to native
        }
        mode = if (intent.getStringExtra("mode") == "balancer") Mode.BALANCER else Mode.MODEM
        quicImpl = intent.getStringExtra("quic_impl") ?: "native"
        networkProfile = com.proxyagent.app.nativeagent.quic.NetworkProfile
            .fromPrefValue(intent.getStringExtra("network_profile"))
        if (host.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        // Idempotency guard. A single reboot can deliver more than one start to
        // the SAME live service instance:
        //   • BootReceiver on ACTION_BOOT_COMPLETED (and QUICKBOOT_POWERON), and
        //   • the Magisk root boot script ~10s later via RemoteControlReceiver, and
        //   • START_REDELIVER_INTENT re-delivery after a transient kill.
        // onStartCommand runs serialized on the main thread, so a prior start
        // has already set runnerThread before the next delivery arrives. Without
        // this guard the second delivery would spawn a duplicate AgentRunner +
        // StatusUpdater, leak the previous wakelock (the field is overwritten
        // without release), and open a second tunnel with the same agent id that
        // the registrator may reject. If a session is already live, just
        // re-satisfy the startForeground() contract for THIS delivery (the
        // startForegroundService caller still requires it within ~5s) and bail.
        if (runnerThread?.isAlive == true && !stopRequested) {
            log("onStartCommand: session already active — ignoring duplicate " +
                "start (action=${intent.action ?: "-"})")
            startForegroundSafe()
            return START_REDELIVER_INTENT
        }

        // Defensive bind reset. The :proxy process survives stops in
        // NATIVE/BINARY, so a previous Wi-Fi return session could have
        // left this process bound to cellular
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
        // "unknown" and time-limits it too. startForegroundSafe passes the type
        // explicitly and never throws — if the foreground transition fails
        // (OEM blocks the boot exemption, FGS rules tightened) we can't run a
        // proxy without an FGS, so bail cleanly rather than limp along killable.
        if (!startForegroundSafe()) {
            log("Aborting start — foreground transition failed")
            stopSelf()
            return START_NOT_STICKY
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
        // Wire the recorder into the Wi-Fi return counters — but ONLY
        // when the relay is alive. Wi-Fi return is opt-in; without it
        // we don't have `bindProcessToNetwork(cellular)` and the default
        // route on dual-transport devices is Wi-Fi, not cellular. So
        // NativeProxyAgent.targetUp/Down counters tick under wifi_return=
        // off too, but the bytes they're counting traversed Wi-Fi, not
        // cellular — attributing them to `cellRx/Tx` would lie.
        //
        // Honest stance: when the relay is null (wifi_return disabled,
        // or it was disabled mid-session by split_failed), we don't
        // actually know which interface the target dials used —
        // TrafficStats per-UID isn't split by interface. Return 0 for
        // both legs; the analytics bucket stores only the UID-wide
        // total rx/tx (still correct) and per-interface stays zero
        // until the user opts in. Down the line a NetworkStatsManager-
        // based recorder could fill those in without the relay, but
        // that needs PACKAGE_USAGE_STATS runtime permission.
        //
        // When the relay IS alive:
        //   wifi* = relay's upstream socket bytes bound to Wi-Fi
        //   cell* = relay fallback bytes (cellular default route)
        //         + native-agent target-dial bytes (cellular via
        //           process bind)
        // Lambdas dereference wifiRelay / nativeAgent on every tick,
        // so a relay tear-down (wifi_return disabled mid-session)
        // gracefully drops back to 0 — the recorder's baseline-delta
        // pattern with coerceAtLeast(0) absorbs the discontinuity.
        analytics = AnalyticsRecorder(
            ctx = this,
            readWifiRx = { wifiRelay?.wifiDownBytes() ?: 0L },
            readWifiTx = { wifiRelay?.wifiUpBytes() ?: 0L },
            readCellRx = {
                val r = wifiRelay
                if (r == null) 0L
                else r.fallbackDownBytes() + (nativeAgent?.targetDownBytes() ?: 0L)
            },
            readCellTx = {
                val r = wifiRelay
                if (r == null) 0L
                else r.fallbackUpBytes() + (nativeAgent?.targetUpBytes() ?: 0L)
            },
        )

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Proxy::WL")
            wakeLock?.acquire()
        } catch (_: Exception) {}

        // Status + speed updater: polls TrafficStats and battery once per second,
        // refreshes notification + conn_info file, and enforces battery /
        // no-internet auto-stops.
        Thread {
            // Null-safe: acquired at thread start which, on a reboot auto-start,
            // is very early boot. An unchecked cast returning null would throw
            // OUTSIDE the per-iteration try/catch below and kill the updater at
            // startup — the notification would then freeze at "Starting…" forever.
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
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
                    nm?.notify(1, buildNotification(statusText()))
                    writeConnInfo()
                    analytics?.tick()
                    maybeRefreshNatIp()

                    val threshold = readBatteryThreshold()
                    if (threshold > 0 && bm != null) {
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
            Engine.SDK -> Thread { runSdkEngine(host, port, key, agentId, dns) }
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
        //   - NATIVE (in-process): bindProcessToNetwork(cellular) so
        //     target dials default-route through cellular. Per-socket
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
    // process to it so all sockets created after this — including target
    // dials from the NATIVE engine's in-process supervisor — egress through
    // cellular by default. The relay's outbound sockets override this back
    // to Wi-Fi per-socket via wifiNet.bindSocket().
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
    // of each route capturing its own onSplitFail lambda. Both engines roll
    // back the effective host/port and trip the runner so the next dial uses
    // the real upstream.
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
            Engine.NATIVE, Engine.SDK -> {
                // Both in-process engines read effectiveHost/Port on every
                // dial loop iteration when building their config, so we
                // just stop the current agent and let the runner respawn
                // with the rolled-back (host, port). No subprocess to
                // kill — stop the in-process supervisor instead.
                log("wifi_return: rolling back ${engine.name} engine to direct dial " +
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
                    //   - NATIVE engine: this is UNEXPECTED —
                    //     bindProcessToNetwork(cellular) was supposed to
                    //     route target dials through cellular. Something
                    //     failed silently (ROM quirk, race with network
                    //     state). Disable the relay to avoid exposing the
                    //     Wi-Fi IP to targets.
                    //
                    //   - BINARY engine: this is EXPECTED. Subprocess
                    //     doesn't inherit bindProcessToNetwork, so target
                    //     dials go through the default route (Wi-Fi). The
                    //     user accepted this trade-off (UI normally
                    //     forces NATIVE; getting here means they
                    //     explicitly chose BINARY). Keep the relay
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
    // the same TCP/QUIC wire protocol as the binary engine, so it pairs
    // with the same registrator infrastructure.
    //
    // Logging is bridged into parseAgentLine() via a LogSink that emits
    // the same line vocabulary the binary engine writes to stdout —
    // "uplink connected ... transport=tcp", "opening tunnel target=...",
    // "REBOOT received from registrator reason=...", etc. — so the
    // existing status parser keeps working without changes.
    private fun runNativeEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        try {
            // Same Wi-Fi return wiring as the binary engine — see
            // maybeStartWifiRelay() for the conditions. NATIVE runs
            // in-process so bindProcessToNetwork(cellular) sticks.
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
                nativeAgent = com.proxyagent.app.engine.NativeAgentHandle(agent)

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
                        "native" -> {
                            // Wi-Fi-return for the in-house QUIC uplink: bind
                            // the UDP socket to whatever Network WifiReturnRelay
                            // currently considers Wi-Fi. Closure is re-invoked
                            // on every (re)dial including stall self-heal, so a
                            // network handover between dials is picked up cleanly.
                            // If wifi_return is off or no Wi-Fi is acquired, the
                            // lookup returns null and the socket falls back to
                            // the process default route (cellular under wifi_return,
                            // OS default otherwise). Kwik already does the
                            // equivalent via its socketFactory, so this is only
                            // for the native path.
                            val service = this@ProxyService
                            val binder: (java.net.DatagramSocket) -> Unit = { sock ->
                                service.wifiRelay?.currentWifiNetwork()?.bindSocket(sock)
                            }
                            com.proxyagent.app.nativeagent.quic.NativeQuicTransport.Factory(
                                uplinkSocketBinder = binder,
                                networkProfile = networkProfile,
                            )
                        }
                        else -> com.proxyagent.app.nativeagent.KwikQuicTransport.Factory()
                    },
                    networkProfile = networkProfile,
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

    // Same engine as NATIVE, reached through the external Proxy Agent
    // SDK (com.proxyagent:proxy-agent-android) instead of this app's
    // in-tree copy of it. The two run identical agent code, so this
    // engine is not here to behave differently — it is here to prove the
    // SDK works when consumed the way a third-party app would consume
    // it, across a real AAR boundary. A discrepancy between NATIVE and
    // SDK is therefore a packaging bug, and this is how we'd see one.
    //
    // The structure below intentionally mirrors runNativeEngine line for
    // line (same Wi-Fi relay wiring, same outer respawn loop, same log
    // bridging into parseAgentLine) so the comparison is like-for-like.
    // What differs is confined to the SdkEngineProvider call.
    private fun runSdkEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        val provider = com.proxyagent.app.engine.SdkEngineProvider
        if (!provider.available) {
            log("SDK engine unavailable: ${provider.describe()}")
            connStatus = ConnStatus.ERROR
            state("error"); writeConnInfo()
            return
        }
        try {
            originalHost = host
            originalPort = port
            val initialEffective = maybeStartWifiRelay(host, port)
            effectiveHost = initialEffective.first
            effectivePort = initialEffective.second
            val relayActive = effectiveHost != host
            val hostLog = if (relayActive) "$effectiveHost:$effectivePort→$host:$port" else host
            log("SDK engine: host=$hostLog port=$port key=${mask(key)} " +
                "id=${if (agentId.isEmpty()) "<empty>" else mask(agentId)} dns=$dns")
            // Where the SDK classes actually came from. If the composite
            // build ever resolves to something unexpected, this line is
            // the difference between a five-minute diagnosis and an hour.
            log("SDK engine: linked against ${provider.describe()}")
            if (wifiRelay != null) scheduleWifiReturnSelfTest()

            var outerBackoffMs = 1_000L
            while (!stopRequested) {
                val effHost = effectiveHost
                val effPort = effectivePort
                val portInt = effPort.toIntOrNull()
                if (portInt == null || portInt !in 1..65535) {
                    log("SDK engine: invalid port \"$effPort\"")
                    connStatus = ConnStatus.ERROR; state("error"); writeConnInfo()
                    return
                }

                val service = this@ProxyService
                val params = com.proxyagent.app.engine.SdkAgentParams(
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
                    // Crosses as a String — the app and the SDK each have
                    // their own NetworkProfile enum; the adapter maps it.
                    networkProfileName = networkProfile.name,
                    quicSocketBinder = { sock ->
                        service.wifiRelay?.currentWifiNetwork()?.bindSocket(sock)
                    },
                )

                connStatus = ConnStatus.CONNECTING
                val handle = provider.start(params) { level, msg, fields ->
                    // Identical formatting to the NATIVE engine so
                    // parseAgentLine — and therefore the status badge,
                    // tunnel counter and REBOOT auto-cycle — behaves the
                    // same on both. The "[sdk]" tag is only for the
                    // human reading agent.log.
                    val sb = StringBuilder()
                    sb.append("level=").append(level).append(" msg=\"").append(msg).append('"')
                    for ((k, v) in fields) {
                        if (v == null) continue
                        sb.append(' ').append(k).append('=').append(v.toString())
                    }
                    val line = sb.toString()
                    parseAgentLine(line)
                    log("[sdk] $line")
                }
                if (handle == null) {
                    log("SDK engine: agent failed to start")
                    connStatus = ConnStatus.ERROR
                    state("error"); writeConnInfo()
                    return
                }
                nativeAgent = handle
                state("running")

                while (!stopRequested && handle.isRunning()) {
                    try { Thread.sleep(500) }
                    catch (_: InterruptedException) { break }
                }
                nativeAgent = null
                try { handle.stop(timeoutMs = 2_000L) } catch (_: Throwable) {}
                if (stopRequested) break

                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                log("SDK engine: respawning in ${outerBackoffMs}ms")
                try { Thread.sleep(outerBackoffMs) }
                catch (_: InterruptedException) {
                    log("SDK engine: respawn backoff interrupted; retrying now")
                    outerBackoffMs = 1_000L
                    continue
                }
                outerBackoffMs = (outerBackoffMs * 2).coerceAtMost(30_000L)
            }
            log("SDK runner loop exited")
        } catch (e: Throwable) {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            log("SDK engine error: $sw")
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
        // The Go binary reads only env vars (see BINARIES.md §2) — its
        // QUIC stack hardcodes Brutal CC at 100 Mbps and a 32 MiB UDP
        // buffer with no env hooks. Surface that the operator's
        // network_profile choice was ignored so log-trawling explains
        // why their "MID_500" setting didn't change behaviour.
        if (networkProfile != com.proxyagent.app.nativeagent.quic.NetworkProfile.LOW_100) {
            log("WARN: network_profile=${networkProfile.name} ignored — " +
                "binary engine uses fixed 100 Mbps Brutal CC / 32 MiB UDP buf. " +
                "Switch to NATIVE engine to honor this setting.")
        }
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
            Engine.NATIVE -> {
                // The native supervisor's dial loop re-resolves and
                // re-dials each iteration. Stopping the current uplink
                // wakes the loop without tearing down the supervisor,
                // so it reconnects on the new interface.
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
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
