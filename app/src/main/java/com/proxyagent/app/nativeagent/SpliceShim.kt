package com.proxyagent.app.nativeagent

import java.io.FileDescriptor
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
    // Instance method — Kotlin objects don't generate JNI-friendly
    // static externals without @JvmStatic on each function (and even
    // then the binding maps to instance methods on the singleton).
    // Using a class with a single shared instance is simpler.
    external fun spliceLoop(fdSrc: Int, fdDst: Int, chunkSize: Int): Long

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

        /** Install (or replace) the telemetry hook. NativeProxyAgent
         *  calls this once per agent start with its own log sink. */
        fun setLogger(fn: Logger?) {
            logger = fn
        }

        /** Snapshot of total bytes moved through kernel splice across
         *  all tunnels in this process. Useful for surfacing in a
         *  diagnostics panel or "Save log" footer. Resets only when
         *  the process restarts. */
        fun totalSplicedBytes(): Long = totalSplicedBytes.get()

        /**
         * Attempt a kernel zero-copy bridge between two [SocketChannel]s.
         * Returns true if the splice loop ran to completion (caller can
         * treat the bridge as done). Returns false on:
         *  - library load failure (e.g. ABI mismatch, stripped APK)
         *  - file descriptor extraction failure (reflection broke)
         *  - the initial splice() syscall couldn't transfer any bytes
         *
         * On false the caller MUST fall back to a userspace copy. Once
         * splice has moved any bytes, this method commits to it — never
         * returns false mid-stream.
         */
        fun copy(src: SocketChannel, dst: SocketChannel): Boolean {
            if (!ensureLoaded()) {
                logFallbackOnce("library_not_loaded")
                return false
            }
            val fdSrc = fdOf(src) ?: run {
                logFallbackOnce("fd_extraction_failed_src")
                return false
            }
            val fdDst = fdOf(dst) ?: run {
                logFallbackOnce("fd_extraction_failed_dst")
                return false
            }
            val result = try {
                singleton.spliceLoop(fdSrc, fdDst, CHUNK_BYTES)
            } catch (t: Throwable) {
                logFallbackOnce("spliceLoop_threw:${t.javaClass.simpleName}")
                return false
            }
            // -1 from native side = couldn't even start (e.g. pipe()
            // failed); safe to fall back. >= 0 means the splice loop
            // ran and the source reached EOF or both sides closed.
            if (result < 0) {
                logFallbackOnce("native_setup_failed")
                return false
            }
            // Success — accumulate bytes and emit a one-shot "active"
            // line on first transfer of this process.
            if (result > 0) {
                totalSplicedBytes.addAndGet(result)
            }
            if (loggedFirstSuccess.compareAndSet(false, true)) {
                logger?.invoke(
                    "INFO",
                    "splice: kernel zero-copy active",
                    mapOf("first_bytes" to result),
                )
            }
            return true
        }

        /** Sticky probe — load the library at most once per process.
         *  Also emits a one-shot log line announcing the outcome. */
        private fun ensureLoaded(): Boolean {
            available.get()?.let { return it }
            val ok = try {
                System.loadLibrary("agentsplice")
                logger?.invoke(
                    "INFO",
                    "splice: libagentsplice.so loaded",
                    emptyMap(),
                )
                true
            } catch (t: Throwable) {
                // ABI mismatch (e.g. APK stripped to a single ABI and
                // the device's ABI isn't covered) or some other linker
                // failure. NIO fallback handles it transparently; we
                // log so the user can tell from the log file whether
                // the optimisation is engaged.
                logger?.invoke(
                    "WARN",
                    "splice: libagentsplice.so unavailable, NIO fallback only",
                    mapOf("error" to (t.message ?: t.javaClass.simpleName)),
                )
                false
            }
            available.compareAndSet(null, ok)
            return ok
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
         * [SocketChannel]. Uses two Android-internal but stable
         * reflection points:
         *  - `sun.nio.ch.SocketChannelImpl.fd` (a [FileDescriptor])
         *  - `FileDescriptor.getInt$()` returning the int fd
         *
         * Both have been present and stable on Android since at least
         * API 21 and on plain OpenJDK; we still wrap them in defensive
         * try/catch so a future runtime change degrades to NIO fallback
         * instead of crashing the bridge.
         */
        private fun fdOf(channel: SocketChannel): Int? {
            return try {
                var cls: Class<*>? = channel.javaClass
                var fdObj: Any? = null
                while (cls != null && fdObj == null) {
                    try {
                        val f = cls.getDeclaredField("fd")
                        f.isAccessible = true
                        fdObj = f.get(channel)
                    } catch (_: NoSuchFieldException) {
                        cls = cls.superclass
                    }
                }
                val fd = fdObj as? FileDescriptor ?: return null
                val getInt = FileDescriptor::class.java
                    .getDeclaredMethod("getInt\$")
                getInt.isAccessible = true
                val raw = getInt.invoke(fd) as Int
                if (raw < 0) null else raw
            } catch (_: Throwable) {
                null
            }
        }

        // Per-call ceiling for splice(); kernel will return less when
        // the pipe buffer (64 KiB default) fills.
        private const val CHUNK_BYTES = 1024 * 1024
    }
}
