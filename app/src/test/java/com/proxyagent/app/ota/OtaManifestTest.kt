package com.proxyagent.app.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for manifest parsing + version-compare. Vectors are the real
// manifests served by the CRM (OTA_UPDATES_PLAN.md §0).
class OtaManifestTest {

    private val currentJson = """
        [{"channel":"stable","currentVersion":"1.0.52","currentBuild":"52",
          "fileName":"6a60fe20bfa97b595b84d642","releaseDate":"2026-07-22T17:30:08Z",
          "SHA256":"16361CCD8E8A4232CC7B6EBA9076DCB2E1CC81E158570413841541C96E6EF31B"}]
    """.trimIndent()

    private val historyJson = """
        [{"status":"current","version":"1.0.52","fileName":"6a60fe20bfa97b595b84d642","build":"52"},
         {"status":"old","version":"1.0.40","fileName":"6a610527bfa97b595b84d656","build":"40"}]
    """.trimIndent()

    @Test fun parsesCurrentVersions() {
        val list = OtaManifest.parseCurrentVersions(currentJson)
        assertEquals(1, list.size)
        val r = list[0]
        assertEquals(OtaChannel.STABLE, r.channel)
        assertEquals("1.0.52", r.version)
        assertEquals(52L, r.build)
        assertEquals("6a60fe20bfa97b595b84d642", r.fileName)
        // SHA256 is normalized to lower-case.
        assertEquals("16361ccd8e8a4232cc7b6eba9076dcb2e1cc81e158570413841541c96e6ef31b", r.sha256)
    }

    @Test fun findChannelIsOrderIndependentAndTyped() {
        val list = OtaManifest.parseCurrentVersions(currentJson)
        assertEquals("1.0.52", OtaManifest.findChannel(list, OtaChannel.STABLE)?.version)
        assertNull(OtaManifest.findChannel(list, OtaChannel.BETA))
    }

    @Test fun statusComparesByBuildNumber() {
        val r = OtaManifest.parseCurrentVersions(currentJson)[0]
        assertTrue(OtaManifest.statusFor(r, 40) is UpdateStatus.Available)   // older installed
        assertTrue(OtaManifest.statusFor(r, 52) is UpdateStatus.UpToDate)    // same
        assertTrue(OtaManifest.statusFor(r, 99) is UpdateStatus.UpToDate)    // newer installed
        assertEquals(UpdateStatus.NoRelease, OtaManifest.statusFor(null, 1)) // channel absent
    }

    @Test fun parsesHistoryAndCurrentFlag() {
        val h = OtaManifest.parseHistory(historyJson)
        assertEquals(2, h.size)
        assertTrue(h[0].isCurrent)
        assertEquals(52L, h[0].build)
        assertTrue(!h[1].isCurrent)
        assertEquals(40L, h[1].build)
    }

    @Test fun emptyHistoryIsEmptyList() {
        assertTrue(OtaManifest.parseHistory("[]").isEmpty())
    }

    @Test fun unknownFieldsAreIgnored() {
        val json = """[{"channel":"dev","currentVersion":"2.0.0","currentBuild":"300",
            "fileName":"6a610557bfa97b595b84d657","SHA256":"ab","releaseDate":"x",
            "somethingNew":123,"nested":{"a":1}}]""".trimIndent()
        val list = OtaManifest.parseCurrentVersions(json)
        assertEquals(1, list.size)
        assertEquals(OtaChannel.DEV, list[0].channel)
        assertEquals(300L, list[0].build)
    }

    @Test fun channelOfNormalizesAndPreservesUnknown() {
        assertEquals(OtaChannel.BETA, OtaChannel.of("BETA"))
        assertEquals(OtaChannel.DEV, OtaChannel.of("  dev "))
        // Unknown channels are preserved, NOT collapsed into a known one.
        val canary = OtaChannel.of("Canary")
        assertEquals("canary", canary.id)
        assertEquals("Canary", canary.label)
        assertNotEquals(OtaChannel.STABLE, canary)
    }

    @Test fun unknownChannelInManifestIsNotCollapsedIntoStable() {
        val json = """[
            {"channel":"stable","currentVersion":"1.0.10","currentBuild":"10","fileName":"6a60fe20bfa97b595b84d642","SHA256":"x"},
            {"channel":"canary","currentVersion":"9.9.9","currentBuild":"999","fileName":"6a610527bfa97b595b84d656","SHA256":"y"}
        ]""".trimIndent()
        val list = OtaManifest.parseCurrentVersions(json)
        assertEquals(2, list.size)
        assertEquals(10L, OtaManifest.findChannel(list, OtaChannel.STABLE)?.build)
        assertEquals(999L, OtaManifest.findChannel(list, OtaChannel.of("canary"))?.build)
    }

    @Test fun discoverChannelsUnionsKnownPresentAndTracked() {
        val releases = OtaManifest.parseCurrentVersions(
            """[{"channel":"canary","currentVersion":"1","currentBuild":"1","fileName":"6a610557bfa97b595b84d657","SHA256":"x"}]"""
        )
        val discovered = OtaManifest.discoverChannels(releases, OtaChannel.of("nightly"))
        // Known baseline first, then the "canary" that has a release, then the tracked "nightly".
        assertEquals(
            listOf("stable", "beta", "dev", "canary", "nightly"),
            discovered.map { it.id },
        )
    }

    @Test fun discoverChannelsIsJustBaselineWhenOnlyStableRemains() {
        val releases = OtaManifest.parseCurrentVersions(
            """[{"channel":"stable","currentVersion":"1","currentBuild":"1","fileName":"6a60fe20bfa97b595b84d642","SHA256":"x"}]"""
        )
        val discovered = OtaManifest.discoverChannels(releases, OtaChannel.STABLE)
        assertEquals(listOf("stable", "beta", "dev"), discovered.map { it.id })
    }

    @Test fun rejectsMaliciousOrMalformedFileName() {
        assertTrue(OtaManifest.isValidFileName("6a60fe20bfa97b595b84d642"))
        assertTrue(!OtaManifest.isValidFileName("../../shared_prefs/cfg"))
        assertTrue(!OtaManifest.isValidFileName("short"))
        assertTrue(!OtaManifest.isValidFileName("6a60fe20bfa97b595b84d642/x"))

        // Entries with a non-ObjectId fileName are dropped at parse (path-traversal guard).
        val bad = """[{"channel":"stable","currentVersion":"1","currentBuild":"1",
            "fileName":"../../evil","SHA256":"x"}]""".trimIndent()
        assertTrue(OtaManifest.parseCurrentVersions(bad).isEmpty())
        assertTrue(OtaManifest.parseHistory(
            """[{"status":"old","version":"1","build":"1","fileName":"../../evil"}]"""
        ).isEmpty())
    }
}
