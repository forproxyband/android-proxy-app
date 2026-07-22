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
    private const val KEY_LAST_CHECK = "ota_last_check_ms"
    private const val KEY_AUTO_UPDATE = "ota_auto_update"

    val baseUrl: String get() = BuildConfig.OTA_BASE_URL.trimEnd('/')
    val appId: String get() = BuildConfig.OTA_APP_ID
    val platform: String get() = BuildConfig.OTA_PLATFORM
    val encryptionKey: String get() = BuildConfig.OTA_ENCRYPTION_KEY

    /**
     * Whether OTA is wired for this build type. The app id + key are per build
     * type (release vs debug are separate CRM apps); a build whose CRM app
     * isn't registered yet ships blank values, and OTA is disabled for it.
     */
    fun isConfigured(): Boolean = appId.isNotBlank() && baseUrl.isNotBlank()

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

    /** Stamp "now" as the last successful update check (widget / worker / screen). */
    fun recordCheck(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    /** Epoch-ms of the last successful check, or 0 if never. */
    fun lastCheckMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, 0).getLong(KEY_LAST_CHECK, 0L)

    /** Whether the background worker should silently install updates (needs root). */
    fun autoUpdate(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_AUTO_UPDATE, false)

    fun setAutoUpdate(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }
}
