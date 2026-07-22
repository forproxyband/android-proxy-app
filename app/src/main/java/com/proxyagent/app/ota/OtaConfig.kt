package com.proxyagent.app.ota

import android.content.Context
import com.proxyagent.app.BuildConfig

// ────────────────────────────────────────────────────────────────────────
// OTA configuration: R2 coordinates (from BuildConfig) + the user-selected
// channel (persisted in the shared "cfg" SharedPreferences, same store the
// rest of the app uses). URL builders follow the contract exactly:
//   <base>/updates/app/<appId>/<platform>/<object>
// ────────────────────────────────────────────────────────────────────────

object OtaConfig {

    /** SharedPreferences store shared across the app (see MainActivity). */
    private const val PREFS = "cfg"
    private const val KEY_CHANNEL = "ota_channel"

    val baseUrl: String get() = BuildConfig.OTA_BASE_URL.trimEnd('/')
    val appId: String get() = BuildConfig.OTA_APP_ID
    val platform: String get() = BuildConfig.OTA_PLATFORM
    val encryptionKey: String get() = BuildConfig.OTA_ENCRYPTION_KEY

    /** Directory segment for this app+platform in R2. */
    private fun dir(): String = "$baseUrl/updates/app/$appId/$platform"

    /** `current-versions.json` — actual versions for all channels. */
    fun currentVersionsUrl(): String = "${dir()}/current-versions.json"

    /** `versions-<channel>.json` — full channel history. */
    fun historyUrl(channel: OtaChannel): String = "${dir()}/versions-${channel.id}.json"

    /** Encrypted build object. `fileName` MUST come from a manifest, never constructed. */
    fun buildUrl(fileName: String): String = "${dir()}/$fileName"

    /** The channel the user currently tracks; defaults to OTA_DEFAULT_CHANNEL. */
    fun channel(ctx: Context): OtaChannel {
        val stored = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CHANNEL, null)
        return OtaChannel.of(stored ?: BuildConfig.OTA_DEFAULT_CHANNEL)
    }

    /** Persist the tracked channel. */
    fun setChannel(ctx: Context, channel: OtaChannel) {
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_CHANNEL, channel.id)
            .apply()
    }
}
