package com.proxyagent.app.nativeagent

import android.os.ParcelFileDescriptor
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge between [Uplink.copyChannel] and the kernel splice(2)
 * syscall, exposed through `libagentsplice.so` (see
 * `app/src/main/cpp/splice_shim.c`).
 *
 * Optional optimization — if the library doesn't load or the file
 * descriptor can't be extracted from the [SocketChannel], the caller
 * falls back to NIO + DirectByteBuffer with no data loss.
 *
 * Why a separate class and not inlined into NativeProxyAgent:
 *  - Reflection on `FileDescriptor.getInt$()` and `SocketChannelImpl.fd`
 *    is Android-only; keeping it isolated makes the rest of the agent
 *    portable to plain JVM (where copyChannel would just skip this).
 *  - Library load failure must be cached — without the cache we'd hit
 *    `System.loadLibrary` on every tunnel, which is wasteful even
 *    when it succeeds and an exception storm when it doesn't.
 */
internal class SpliceShim {
    // Instance methods — Kotlin objects don't generate JNI-friendly
    // static externals without @JvmStatic on each function (and even
    // then the binding maps to instance methods on the singleton).
    // Using a class with a single shared instance is simpler.
    external fun spliceLoop(fdSrc: Int, fdDst: Int, chunkSize: Int): Long

    /** JNI-level fd extraction. Returns a packed jlong:
     *
     *   On success (>= 0):
     *     fd       = (packed and 0xFFFFFFFFL).toInt()
     *     strategy = ((packed shr 32) and 0xFFL).toInt()
     *                1=fdVal, 2=fd.fd, 3=fd.descriptor, 4=fd.getInt$()
     *
     *   On failure (< 0): negative error code; see decodeFdError().
     *
     *  Packing the strategy ID with the fd lets the Kotlin side log
     *  exactly which path worked without an extra JNI round-trip. */
    external fun extractFd(channel: Any): Long

    /** Telemetry hook so the host (NativeProxyAgent) can route
     *  splice/fallback events into its log sink. Calls are infrequent
     *  (once-per-process for load outcome, once-per-process for first
     *  successful splice, once-per-process for first fallback reason)
     *  so there's no per-tunnel overhead. */
    fun interface Logger {
        operator fun invoke(level: String, msg: String, fields: Map<String, Any?>)
    }

    companion object {
        // null = not probed yet, true = lib loaded, false = unavailable.
        private val available = AtomicReference<Boolean?>(null)
        private val singleton: SpliceShim by lazy { SpliceShim() }

        // Logger hook + emit-once flags. AtomicBoolean.compareAndSet
        // guarantees each line is logged at most once per process,
        // even when many bridge threads race through copy() at startup.
        @Volatile private var logger: Logger? = null
        private val loggedFirstSuccess = AtomicBoolean(false)
        private val loggedFallback = AtomicBoolean(false)
        private val totalSplicedBytes = AtomicLong(0L)

        // Tunnel-direction counters — each successful copyChannel
        // invocation increments exactly one of these. Exposed via
        // stats() so the agent can include them in its session
        // summary log line.
        private val tunnelsSpliced = AtomicLong(0L)
        private val tunnelsFallback = AtomicLong(0L)

        // Cached human name of whichever fd-extraction strategy
        // worked on the first successful extraction. Used in the
        // "splice: kernel zero-copy active" line and in the session
        // summary so the user can see which path the kernel-level
        // bridge is actually using on this device/OS combo.
        @Volatile private var winningStrategy: String? = null

        /** Install (or replace) the telemetry hook. NativeProxyAgent
         *  calls this once per agent start with its own log sink. */
        fun setLogger(fn: Logger?) {
            logger = fn
        }

        /**
         * Pre-flight splice readiness check. Loads the native library
         * AND probes which fd-extraction strategy works on this
         * device — both on a throwaway [SocketChannel] so the first
         * real tunnel doesn't pay the cold-start cost. Emits the
         * usual "splice: kernel zero-copy active" or "splice: NIO
         * fallback engaged" line through the registered [Logger]
         * so [ProxyService.parseAgentLine] can update the widget
         * badge BEFORE the uplink even connects.
         *
         * Effect on the hot path:
         *  - `loggedFirstSuccess` / `loggedFallback` flags get
         *    flipped here, so the same lines aren't re-emitted
         *    when actual tunnels hit [copy].
         *  - `winningStrategy` is cached, so [copy] doesn't have to
         *    re-determine which extraction path works on every
         *    tunnel direction.
         *
         * Why we use a throwaway SocketChannel for probing:
         *  - `SocketChannelImpl` opens a kernel fd in its constructor
         *    (before connect), so unconnected channels have a valid
         *    FileDescriptor. That's enough for fd extraction tests.
         *  - We close the throwaway after the probe, so we leak
         *    nothing.
         *
         * Idempotent — safe to call multiple times. After the first
         * call sets the flags, subsequent calls are nearly no-ops.
         */
        fun warmup() {
            if (!ensureLoaded()) return  // already logged in ensureLoaded

            var ch: SocketChannel? = null
            val packed = try {
                ch = SocketChannel.open()
                // Probe in the same order copy() uses on real tunnels:
                // JNI first (no FileDescriptor side effects), PFD only
                // if JNI is fully blocked. Keeps the cached
                // winningStrategy consistent with the hot path.
                val viaJni = fdOf(ch)
                if (viaJni >= 0) viaJni else fdViaPfd(ch)
            } catch (t: Throwable) {
                // Always emit, not once-per-process — see the success
                // path below for the restart-friendliness reasoning.
                logger?.invoke(
                    "INFO",
                    "splice: NIO fallback engaged",
                    mapOf("reason" to "warmup_exception:${t.javaClass.simpleName}"),
                )
                loggedFallback.set(true)
                try { ch?.close() } catch (_: Throwable) {}
                return
            }
            try { ch?.close() } catch (_: Throwable) {}

            if (packed >= 0) {
                val strategy = ((packed shr 32) and 0xFFL).toInt()
                cacheStrategyOnce(strategy)
                // Always emit on warmup — not once-per-process.
                // Reason: when the user stops + restarts the agent in
                // the same :proxy process, SpliceShim state survives
                // (loadedFirstSuccess stays true from the first run).
                // Without re-emitting here, the second start's
                // ProxyService.parseAgentLine wouldn't see the
                // "kernel zero-copy active" line and would leave the
                // widget badge stuck on the bare "TCP" set by
                // "uplink connected". Per-copy invocations are still
                // silenced via the loggedFirstSuccess flag below so
                // we don't spam the log on every tunnel.
                logger?.invoke(
                    "INFO",
                    "splice: kernel zero-copy active",
                    mapOf(
                        "strategy" to (winningStrategy ?: strategyName(strategy)),
                        "via" to "warmup",
                    ),
                )
                loggedFirstSuccess.set(true)
            } else {
                // Pre-emptive fallback decision — extraction failed
                // on a virgin SocketChannel, so it's not going to work
                // for real tunnels either. Always emit on warmup
                // (same restart-friendliness reason as the success
                // path) and set the once-flag so copy() doesn't
                // duplicate.
                logger?.invoke(
                    "INFO",
                    "splice: NIO fallback engaged",
                    mapOf("reason" to "warmup:${decodeFdError(packed)}"),
                )
                loggedFallback.set(true)
            }
        }

        /** Snapshot of total bytes moved through kernel splice across
         *  all tunnels in this process. Useful for surfacing in a
         *  diagnostics panel or "Save log" footer. Resets only when
         *  the process restarts. */
        fun totalSplicedBytes(): Long = totalSplicedBytes.get()

        /** Composite stats snapshot for the session summary line. */
        data class Stats(
            val tunnelsSpliced: Long,
            val tunnelsFallback: Long,
            val bytesSpliced: Long,
            val strategy: String?,
        )

        fun stats(): Stats = Stats(
            tunnelsSpliced = tunnelsSpliced.get(),
            tunnelsFallback = tunnelsFallback.get(),
            bytesSpliced = totalSplicedBytes.get(),
            strategy = winningStrategy,
        )

        /**
         * Attempt a kernel zero-copy bridge between two [SocketChannel]s.
         * Returns true if the splice loop ran to completion (caller can
         * treat the bridge as done). Returns false on:
         *  - library load failure (e.g. ABI mismatch, stripped APK)
         *  - file descriptor extraction failure (hidden-API blocklist
         *    on a future Android changed the field layout)
         *  - the initial splice() syscall couldn't transfer any bytes
         *
         * On false the caller MUST fall back to a userspace copy. Once
         * splice has moved any bytes, this method commits to it — never
         * returns false mid-stream.
         *
         * [onBytes] (optional) is invoked exactly once on a successful
         * splice with the total byte count the kernel reported moving
         * for this direction. This is how the host wires per-direction
         * accounting (targetUp vs targetDown in NativeProxyAgent) into
         * the zero-copy path — without it the splice fast path silently
         * undercounts because no JVM-side write barrier ever fires.
         * Not called on fallback paths; the caller's NIO loop accounts
         * those itself.
         */
        fun copy(
            src: SocketChannel,
            dst: SocketChannel,
            onBytes: ((Long) -> Unit)? = null,
        ): Boolean {
            if (!ensureLoaded()) {
                tunnelsFallback.incrementAndGet()
                logFallbackOnce("library_not_loaded")
                return false
            }
            // Try the JNI strategies FIRST (fdVal → FileDescriptor.fd
            // → .descriptor → getInt$()). They only read fields, no
            // side effects, so two bridge threads racing to extract
            // fds from the same socket pair both succeed.
            //
            // PFD is the fallback because pfd.detachFd() ->
            // IoUtils.acquireRawFd() calls fd.setInt$(-1) on the
            // ORIGINAL Socket's FileDescriptor. After that the
            // sibling bridge thread (running the opposite direction
            // of the copy) sees a -1 fd on its fdViaPfd() / fdOf()
            // call and is forced into the NIO fallback path —
            // producing the asymmetric "one direction is fast, the
            // other crawls" failure mode we hit before this reorder.
            //
            // Cost of the reorder: on a future Android that fully
            // blocks even JNI field reads of the system FileDescriptor
            // class, we'd now go through PFD on every tunnel and
            // re-introduce the race. Acceptable trade — JNI extraction
            // has worked on every Android we've shipped to.
            var srcPacked = fdOf(src)
            var dstPacked = fdOf(dst)
            // If JNI failed for either side (hidden-API blocklist on
            // some future device), fall back to PFD. We do BOTH via
            // PFD so the cached `winningStrategy` reflects a single
            // path consistently. PFD's side effect is unavoidable
            // here, but at least we only pay it when JNI is fully
            // locked down.
            if (srcPacked < 0 || dstPacked < 0) {
                srcPacked = fdViaPfd(src)
                dstPacked = fdViaPfd(dst)
            }
            if (srcPacked < 0) {
                tunnelsFallback.incrementAndGet()
                logFallbackOnce("fd_extraction_failed_src:${decodeFdError(srcPacked)}")
                return false
            }
            if (dstPacked < 0) {
                tunnelsFallback.incrementAndGet()
                logFallbackOnce("fd_extraction_failed_dst:${decodeFdError(dstPacked)}")
                return false
            }
            // Decode the packed (strategy, fd) for src; the strategy
            // is the same as dst's by construction (same OS, same
            // SocketChannel impl), so we cache from src only.
            val fdSrc = (srcPacked and 0xFFFFFFFFL).toInt()
            val fdDst = (dstPacked and 0xFFFFFFFFL).toInt()
            val strategy = ((srcPacked shr 32) and 0xFFL).toInt()
            cacheStrategyOnce(strategy)

            // Emit the "active" line BEFORE entering spliceLoop —
            // that syscall blocks until either side EOFs, which on
            // a long-lived tunnel can mean many minutes of silence.
            // The user wants to know "is splice engaged right now",
            // not "did splice eventually complete", so we log as
            // soon as we have valid fds and are about to hand off
            // to the kernel.
            if (loggedFirstSuccess.compareAndSet(false, true)) {
                logger?.invoke(
                    "INFO",
                    "splice: kernel zero-copy active",
                    mapOf("strategy" to (winningStrategy ?: strategyName(strategy))),
                )
            }

            val result = try {
                singleton.spliceLoop(fdSrc, fdDst, CHUNK_BYTES)
            } catch (t: Throwable) {
                tunnelsFallback.incrementAndGet()
                logFallbackOnce("spliceLoop_threw:${t.javaClass.simpleName}")
                return false
            }
            // -1 from native side = couldn't even start (e.g. pipe()
            // failed); safe to fall back. >= 0 means the splice loop
            // ran and the source reached EOF or both sides closed.
            if (result < 0) {
                tunnelsFallback.incrementAndGet()
                logFallbackOnce("native_setup_failed")
                return false
            }
            // Direction completed successfully — accumulate counters
            // for the session summary line.
            tunnelsSpliced.incrementAndGet()
            if (result > 0) {
                totalSplicedBytes.addAndGet(result)
                // Per-call callback so the caller can route this number
                // into its own direction-aware counter (NativeProxyAgent
                // does targetUp / targetDown via bridge()). Wrapped in
                // try/catch so a buggy host doesn't crash the bridge —
                // splice already succeeded, accounting is best-effort.
                if (onBytes != null) {
                    try { onBytes(result) } catch (_: Throwable) {}
                }
            }
            return true
        }

        /** Cache the strategy name on first successful extraction.
         *  Race-safe via the @Volatile write — if two threads race
         *  the first extraction, both write the same value so the
         *  loss is harmless. */
        private fun cacheStrategyOnce(strategy: Int) {
            if (winningStrategy == null) {
                winningStrategy = strategyName(strategy)
            }
        }

        private fun strategyName(strategy: Int): String = when (strategy) {
            0 -> "ParcelFileDescriptor"
            1 -> "SocketChannelImpl.fdVal"
            2 -> "FileDescriptor.fd"
            3 -> "FileDescriptor.descriptor"
            4 -> "FileDescriptor.getInt\$()"
            else -> "unknown_$strategy"
        }

        /** Sticky probe — load the library at most once per process.
         *  Logs the outcome exactly once via the compareAndSet on
         *  [available]: only the thread that wins the race actually
         *  emits a log line, even though multiple bridge threads can
         *  hit ensureLoaded concurrently.
         *
         *  Hard-gate on Android API: on **API < 30 (Android 10 and
         *  below)** we refuse to load the .so and force the NIO
         *  fallback. Reason — on Xiaomi Redmi Note 5 (API 28, kernel
         *  4.4.153) the JNI `SocketChannelImpl.fdVal` strategy *appears*
         *  to extract a valid fd (warmup succeeds, real tunnels start)
         *  but data flow then collapses to ~7 Mbps with frequent
         *  speedtest crashes, while the userspace QUIC bridge (no
         *  splice) on the same device sustains 32 Mbps cleanly. On
         *  newer Android the hidden-API blocklist forces us onto the
         *  PFD path (Samsung A55+) which dup's a fresh fd cleanly, but
         *  on older Android `fdVal` returns the SAME fd the JVM NIO
         *  subsystem is reading from concurrently, and kernel-4.4
         *  splice semantics don't cope. NIO tier is well-tested across
         *  every Android version and gives ~30-50 Mbps — vastly better
         *  than the broken splice path here. */
        private fun ensureLoaded(): Boolean {
            available.get()?.let { return it }
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                if (available.compareAndSet(null, false)) {
                    logger?.invoke(
                        "INFO",
                        "splice: gated off on API<30, NIO fallback only",
                        mapOf("sdk" to android.os.Build.VERSION.SDK_INT.toString()),
                    )
                }
                return false
            }
            val ok = try {
                System.loadLibrary("agentsplice")
                true
            } catch (_: Throwable) {
                false
            }
            // Race-safe one-shot logging: whoever flips null→ok wins
            // and emits. Loaders that arrive late see the existing
            // value and skip the log. System.loadLibrary itself is
            // idempotent on the JVM side, so racing the load itself
            // is harmless — only the log line needed protection.
            val firstSet = available.compareAndSet(null, ok)
            if (firstSet) {
                if (ok) {
                    logger?.invoke(
                        "INFO",
                        "splice: libagentsplice.so loaded",
                        emptyMap(),
                    )
                } else {
                    logger?.invoke(
                        "WARN",
                        "splice: libagentsplice.so unavailable, NIO fallback only",
                        emptyMap(),
                    )
                }
            }
            // Return whichever value ended up in the AtomicReference
            // (ours if we won, the other thread's if we lost).
            return available.get() ?: ok
        }

        /** Emit one line per process the first time we fall back, with
         *  the reason. Subsequent fallbacks are silent — the user just
         *  wants to know "did splice work" once, not per-tunnel. */
        private fun logFallbackOnce(reason: String) {
            if (loggedFallback.compareAndSet(false, true)) {
                logger?.invoke(
                    "INFO",
                    "splice: NIO fallback engaged",
                    mapOf("reason" to reason),
                )
            }
        }

        /**
         * Extract the raw POSIX file descriptor from a connected
         * [SocketChannel] via the JNI shim. We delegate to native
         * code instead of Java reflection because Android 28+
         * "non-SDK API restrictions" blocklist both
         *  - `sun.nio.ch.SocketChannelImpl.fd` (private + hidden), and
         *  - `FileDescriptor.getInt$()` (hidden, suffix indicates
         *    Dalvik/ART internal),
         * with the block becoming hard (no exemption) for apps
         * targeting API 30+. JNI's `GetFieldID` and `GetIntField`
         * are explicitly excluded from this enforcement — they
         * operate at the JVM level and can read private fields of
         * system classes the same way they read fields of user
         * classes. See the JNI extractFd implementation for details.
         *
         * Returns -1 on any failure (channel was null, field layout
         * unrecognised, JNI lookup threw) — the bridge falls back to
         * NIO without data loss.
         */
        /** Returns the packed (strategy, fd) jlong directly from JNI,
         *  or a negative error code. The caller decodes; we keep the
         *  packing here in Kotlin land so the C side stays minimal. */
        private fun fdOf(channel: SocketChannel): Long {
            return try {
                singleton.extractFd(channel)
            } catch (_: Throwable) {
                -1L
            }
        }

        /** Strategy 0 — extract fd via the public ParcelFileDescriptor
         *  API. Bypasses Android non-SDK enforcement entirely because
         *  every call we make is to a public-API method; the hidden
         *  socket.getFileDescriptor$() call happens INSIDE the framework
         *  (PFD class), and the hidden-API check sees the framework
         *  class as the caller, which is always allowed.
         *
         *  Returns a packed (strategy=0, fd) jlong on success, or a
         *  negative error code matching extractFd's convention. */
        private fun fdViaPfd(channel: SocketChannel): Long {
            return try {
                val socket = channel.socket() ?: return -1L
                val pfd = ParcelFileDescriptor.fromSocket(socket)
                    ?: return -3L
                // detachFd reads the int AND marks the PFD detached
                // so its finalizer won't close the underlying fd
                // later. The original Socket keeps a separate
                // reference to the same FileDescriptor object, which
                // is unaffected by PFD's internal mFd=null.
                val fd = pfd.detachFd()
                if (fd < 0) -10L else packStrategyFd(0, fd)
            } catch (_: Throwable) {
                -1L
            }
        }

        /** Mirror of the C-side pack() helper — keeps the encoding
         *  consistent between Kotlin (PFD strategy) and native
         *  (strategies 1-4). Layout: high 32 bits = strategy ID,
         *  low 32 bits = fd. */
        private fun packStrategyFd(strategy: Int, fd: Int): Long {
            return (strategy.toLong() shl 32) or (fd.toLong() and 0xFFFFFFFFL)
        }

        /** Map the negative return codes from [SpliceShim.extractFd]
         *  to short, log-friendly diagnostic strings so a fallback
         *  log line lands the actual reason in agent.log rather than
         *  a generic "didn't work". Keep this in sync with the
         *  C-side return codes in splice_shim.c. */
        private fun decodeFdError(code: Long): String = when (code.toInt()) {
            -1 -> "channel_null"
            -2 -> "fd_field_not_found"     // SocketChannelImpl.fd not visible
            -3 -> "fd_object_null"          // channel not connected yet
            -4 -> "hidden_api_blocked"      // all int-extraction paths refused
            -10 -> "negative_fd"            // socket reports invalid fd
            else -> "code_$code"
        }

        // Per-call ceiling for splice(); kernel will return less when
        // the pipe buffer (64 KiB default) fills.
        private const val CHUNK_BYTES = 1024 * 1024
    }
}
