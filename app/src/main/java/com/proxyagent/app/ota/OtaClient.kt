package com.proxyagent.app.ota

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ────────────────────────────────────────────────────────────────────────
// HTTP access to the public R2 bucket. Plain HttpURLConnection (matches the
// rest of the app — no OkHttp). Manifests are fetched uncached (contract §3
// Cache-Control: no-cache); build objects are downloaded to a file with
// Range-resume and progress. No auth — the bucket is public.
// ────────────────────────────────────────────────────────────────────────

object OtaClient {

    private const val UA = "ProxyAgent-Android-OTA"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val MANIFEST_READ_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    /** Progress for a running download. [total] is -1 when the size is unknown. */
    fun interface DownloadProgress {
        fun onProgress(bytesSoFar: Long, total: Long)
    }

    /** Fetch a manifest as text (uncached). Throws on non-200. */
    fun fetchText(url: String): String {
        val conn = open(url)
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.readTimeout = MANIFEST_READ_TIMEOUT_MS
        try {
            val code = conn.responseCode
            // 404 is a normal "not published" state for a dynamic channel/app,
            // surfaced as a typed exception the caller treats as "no data".
            if (code == 404) throw ManifestNotFoundException("GET $url → HTTP 404")
            if (code != 200) throw IOException("GET $url → HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Download [url] to [dest], resuming a partial file via Range when present.
     * Throws [BuildGoneException] on 404 (object deleted after manifest read).
     */
    fun download(url: String, dest: File, progress: DownloadProgress? = null) {
        dest.parentFile?.mkdirs()
        val existing = if (dest.isFile) dest.length() else 0L

        val conn = open(url)
        conn.requestMethod = "GET"
        conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        try {
            val code = conn.responseCode
            if (code == 404) throw BuildGoneException("build object gone: $url")

            val append: Boolean
            val total: Long
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {           // 206 — resume accepted
                    append = true
                    // Content-Range: bytes start-end/total
                    val cr = conn.getHeaderField("Content-Range")
                    total = cr?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                }
                HttpURLConnection.HTTP_OK -> {                // 200 — full body, start over
                    append = false
                    // contentLengthLong / getHeaderFieldLong are API 24+; parse
                    // the header directly to stay safe on minSdk 23.
                    total = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                }
                else -> throw IOException("GET $url → HTTP $code")
            }

            var soFar = if (append) existing else 0L
            conn.inputStream.buffered().use { input ->
                FileOutputStream(dest, append).buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        soFar += n
                        progress?.onProgress(soFar, total)
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        // OTA fetches carry the encryption key's ciphertext + install payloads;
        // refuse plaintext transport outright (defense-in-depth vs a misconfigured
        // base URL or a downgrade attempt), regardless of app-wide cleartext policy.
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IOException("refusing non-HTTPS OTA URL: $url")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        return conn
    }
}
