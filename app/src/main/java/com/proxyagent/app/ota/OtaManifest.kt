package com.proxyagent.app.ota

import com.proxyagent.app.nativeagent.MiniJson

// ────────────────────────────────────────────────────────────────────────
// Manifest parsing. Reuses the app's zero-dep MiniJson. Unknown fields are
// ignored (forward-compat, per contract §5). Entries missing a required
// field are skipped rather than failing the whole parse.
// ────────────────────────────────────────────────────────────────────────

object OtaManifest {

    // Build object names are MongoDB ObjectIds (24 hex, contract §3). The name
    // is used both as a URL segment AND a local file path, so anything else is
    // rejected to prevent path traversal from a hostile/compromised bucket
    // (e.g. fileName = "../../shared_prefs/cfg").
    private val FILE_NAME_RE = Regex("^[0-9a-fA-F]{24}$")

    fun isValidFileName(name: String): Boolean = FILE_NAME_RE.matches(name)

    /** Parse `current-versions.json` (array, at most one entry per channel). */
    fun parseCurrentVersions(json: String): List<CurrentRelease> {
        val out = ArrayList<CurrentRelease>()
        for (item in MiniJson.parseArray(json).items()) {
            val o = item.asObject() ?: continue
            val channel = o.string("channel") ?: continue
            val version = o.string("currentVersion") ?: continue
            val build = o.string("currentBuild")?.trim()?.toLongOrNull() ?: continue
            val fileName = o.string("fileName")?.trim()?.takeIf { isValidFileName(it) } ?: continue
            // Key is exactly "SHA256" (upper-case) per contract.
            val sha256 = o.string("SHA256") ?: continue
            out.add(
                CurrentRelease(
                    channel = OtaChannel.of(channel),
                    version = version,
                    build = build,
                    sha256 = sha256.lowercase(),
                    fileName = fileName,
                    releaseDate = o.string("releaseDate") ?: "",
                )
            )
        }
        return out
    }

    /** Parse `versions-<channel>.json` (array; may be empty). */
    fun parseHistory(json: String): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        for (item in MiniJson.parseArray(json).items()) {
            val o = item.asObject() ?: continue
            val version = o.string("version") ?: continue
            val fileName = o.string("fileName")?.trim()?.takeIf { isValidFileName(it) } ?: continue
            // Skip (don't default to 0): a build=0 entry would surface as a bogus
            // "downgrade to build 0" candidate in the UI.
            val build = o.string("build")?.trim()?.toLongOrNull() ?: continue
            out.add(
                HistoryEntry(
                    status = o.string("status") ?: "old",
                    version = version,
                    build = build,
                    fileName = fileName,
                )
            )
        }
        return out
    }

    /** Find a channel's record by the `channel` field (order-independent, §5). */
    fun findChannel(releases: List<CurrentRelease>, channel: OtaChannel): CurrentRelease? =
        releases.firstOrNull { it.channel == channel }

    /**
     * The set of channels to offer for selection, given the current-versions
     * manifest and the channel the user tracks. Union of: the documented
     * baseline (stable/beta/dev), every channel that currently has a release
     * (surfaces newly-added channels), and the tracked channel (so a channel
     * that has since disappeared stays visible until the user switches away).
     * Known channels are listed first, new ones appended in manifest order.
     *
     * Note: a brand-new channel with history but NO current release can't be
     * auto-discovered (the manifests carry no channel index) — it appears once
     * it has a current release, or if it is one of the known baseline names.
     */
    fun discoverChannels(releases: List<CurrentRelease>, tracked: OtaChannel): List<OtaChannel> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<OtaChannel>()
        fun add(c: OtaChannel) { if (seen.add(c.id)) out.add(c) }
        OtaChannel.KNOWN.forEach { add(it) }
        releases.forEach { add(it.channel) }
        add(tracked)
        return out
    }

    /**
     * Compare a channel's current release against the installed build.
     * `null` release (channel absent) → [UpdateStatus.NoRelease].
     */
    fun statusFor(release: CurrentRelease?, installedBuild: Long): UpdateStatus = when {
        release == null -> UpdateStatus.NoRelease
        release.build > installedBuild -> UpdateStatus.Available(release)
        else -> UpdateStatus.UpToDate(release)
    }
}
