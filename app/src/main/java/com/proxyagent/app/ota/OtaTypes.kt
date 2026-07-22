package com.proxyagent.app.ota

// ────────────────────────────────────────────────────────────────────────
// OTA data model. Mirrors the CRM manifest contract (OTA_UPDATES_PLAN.md /
// mobile-app-ota-updates-ru.md §5-6). Pure Kotlin, no Android deps, so the
// parser and version-compare logic are unit-testable on the plain JVM.
// ────────────────────────────────────────────────────────────────────────

/**
 * A release channel, identified by its lower-case manifest id. Modelled as an
 * open string value (NOT a closed enum) because channels are dynamic on the
 * CRM side: the documented set is stable/beta/dev, but channels can be dropped
 * (only stable remaining) or new ones added later. An unknown id is preserved
 * as-is — never collapsed into a known channel.
 */
@JvmInline
value class OtaChannel(val id: String) {

    /** Human label: nice names for the known set, Title-case for anything new. */
    val label: String
        get() = when (id) {
            "stable" -> "Stable"
            "beta" -> "Beta"
            "dev" -> "Dev"
            else -> id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    companion object {
        val STABLE = OtaChannel("stable")
        val BETA = OtaChannel("beta")
        val DEV = OtaChannel("dev")

        /** Documented baseline set (contract §4) — always offered for selection. */
        val KNOWN: List<OtaChannel> = listOf(STABLE, BETA, DEV)

        /** Normalize a raw channel id (trimmed, lower-case). */
        fun of(id: String): OtaChannel = OtaChannel(id.trim().lowercase())
    }
}

/**
 * One entry of `current-versions.json` — the authoritative "actual version"
 * for a channel. `build` is the numeric [currentBuild] parsed as a Long
 * (the field is a digit-string in the manifest); it maps to the installed
 * app's `versionCode`.
 */
data class CurrentRelease(
    val channel: OtaChannel,
    val version: String,
    val build: Long,
    val sha256: String,
    val fileName: String,
    val releaseDate: String,
)

/**
 * One entry of `versions-<channel>.json` — the channel history. `current`
 * marks the actual version, everything else is `old`. Used by the manual
 * version picker / downgrade UI.
 */
data class HistoryEntry(
    val status: String,      // "current" | "old"
    val version: String,
    val build: Long,
    val fileName: String,
) {
    val isCurrent: Boolean get() = status.equals("current", ignoreCase = true)
}

/** Outcome of a channel check against the installed build. */
sealed class UpdateStatus {
    /** Channel has no actual version (record absent from current-versions.json). */
    data object NoRelease : UpdateStatus()

    /** Installed build is >= the channel's current build. */
    data class UpToDate(val release: CurrentRelease) : UpdateStatus()

    /** Channel's current build is newer than the installed build. */
    data class Available(val release: CurrentRelease) : UpdateStatus()
}

/** Thrown when a build object 404s (deleted between manifest read and download). */
class BuildGoneException(message: String) : java.io.IOException(message)

/** Thrown when a manifest 404s (channel/app absent). Treated as "no data", not an error. */
class ManifestNotFoundException(message: String) : java.io.IOException(message)

/** Thrown when the decrypted file's SHA-256 does not match the manifest. */
class IntegrityException(message: String) : java.io.IOException(message)
