package com.proxyagent.app.ota

import android.content.Context
import com.proxyagent.app.BuildConfig
import java.io.File

// ────────────────────────────────────────────────────────────────────────
// High-level OTA orchestration: check a channel, list its history, and
// download → decrypt → verify a build into an installable APK. All methods
// are blocking (network/IO) and must be called off the main thread — callers
// use plain Thread{} + runOnUiThread, matching the rest of the app.
// ────────────────────────────────────────────────────────────────────────

object OtaManager {

    /** Progress across the prepare pipeline. [total] is -1 when unknown. */
    fun interface PrepareProgress {
        fun onPhase(phase: Phase, soFar: Long, total: Long)
    }

    enum class Phase { DOWNLOAD, VERIFY }

    /** versionCode of the currently-installed app — the compare baseline. */
    fun installedBuild(): Long = BuildConfig.VERSION_CODE.toLong()

    fun installedVersionName(): String = BuildConfig.VERSION_NAME

    /**
     * All channels that currently have a release. Network. A missing manifest
     * (404) is treated as "nothing published" → empty list, so a shrunk-to-zero
     * or reconfigured bucket doesn't surface as an error.
     */
    fun fetchCurrentReleases(): List<CurrentRelease> = try {
        OtaManifest.parseCurrentVersions(OtaClient.fetchText(OtaConfig.currentVersionsUrl()))
    } catch (_: ManifestNotFoundException) {
        emptyList()
    }

    /** Check the channel the user tracks. Network; throws on error. */
    fun check(ctx: Context): UpdateStatus = check(ctx, OtaConfig.channel(ctx))

    /** Check an explicit channel. Network; throws on error. */
    fun check(ctx: Context, channel: OtaChannel): UpdateStatus {
        val release = OtaManifest.findChannel(fetchCurrentReleases(), channel)
        return OtaManifest.statusFor(release, installedBuild())
    }

    /**
     * Full history of a channel (newest first, as published). Network. A missing
     * history manifest (channel dropped) → empty list rather than an error.
     */
    fun history(channel: OtaChannel): List<HistoryEntry> = try {
        OtaManifest.parseHistory(OtaClient.fetchText(OtaConfig.historyUrl(channel)))
    } catch (_: ManifestNotFoundException) {
        emptyList()
    }

    /** The current release record for a channel, if any (carries SHA256). */
    fun currentRelease(channel: OtaChannel): CurrentRelease? =
        OtaManifest.findChannel(fetchCurrentReleases(), channel)

    /**
     * Download the build [fileName], decrypt it, and return an installable APK.
     *
     * When [expectedSha256] is non-null (the build is a channel's `current`,
     * so the manifest publishes its hash) the decrypted output is verified
     * against it. For older builds the history manifest carries no hash, so we
     * fall back to an APK-structure sanity check and rely on the system
     * installer's signature verification (§8 / OTA_UPDATES_PLAN.md).
     *
     * The object is immutable per `fileName` (§9), so a previously prepared APK
     * is reused when present and still valid.
     */
    fun prepare(
        ctx: Context,
        fileName: String,
        expectedSha256: String?,
        progress: PrepareProgress? = null,
    ): File {
        // Defense-in-depth: fileName is used as a local path segment; never
        // trust it even though parsing already filtered it (contract §3).
        if (!OtaManifest.isValidFileName(fileName)) {
            throw IntegrityException("invalid build fileName: $fileName")
        }
        val dir = otaDir(ctx)
        val enc = File(dir, "$fileName.enc")
        val apk = File(dir, "$fileName.apk")

        // Reuse a cached, still-valid APK.
        if (apk.isFile && apk.length() > 0) {
            val ok = if (expectedSha256 != null)
                OtaCrypto.sha256Hex(apk).equals(expectedSha256, ignoreCase = true)
            else isApk(apk)
            if (ok) return apk
            apk.delete()
        }

        try {
            OtaClient.download(OtaConfig.buildUrl(fileName), enc) { soFar, total ->
                progress?.onPhase(Phase.DOWNLOAD, soFar, total)
            }

            val encLen = enc.length()
            val gotSha = OtaCrypto.decryptAndHash(enc, apk, OtaConfig.encryptionKey) { written ->
                progress?.onPhase(Phase.VERIFY, written, encLen)
            }

            if (expectedSha256 != null && !gotSha.equals(expectedSha256, ignoreCase = true)) {
                throw IntegrityException(
                    "SHA-256 mismatch for $fileName (got $gotSha, expected $expectedSha256)"
                )
            }
            if (!isApk(apk)) {
                throw IntegrityException("decrypted $fileName is not a valid APK")
            }
            return apk
        } catch (t: Throwable) {
            apk.delete()
            throw t
        } finally {
            enc.delete()   // encrypted blob is never needed once decrypted
        }
    }

    /** Remove all cached OTA artifacts. */
    fun clearCache(ctx: Context) {
        otaDir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun otaDir(ctx: Context): File = File(ctx.cacheDir, "ota").apply { mkdirs() }

    /** APK == ZIP: local file header magic `PK\x03\x04`. */
    private fun isApk(file: File): Boolean = file.inputStream().use { s ->
        val h = ByteArray(4)
        s.read(h) == 4 && h[0] == 0x50.toByte() && h[1] == 0x4b.toByte() &&
            h[2] == 0x03.toByte() && h[3] == 0x04.toByte()
    }
}
