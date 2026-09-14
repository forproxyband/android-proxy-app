package com.proxyagent.app.nativeagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Pins the same table the Go policy's tests pin
 * (`proxy-server-go-sdk/targetpolicy/targetpolicy_test.go`). The two engines
 * share no code — this one is a Kotlin port of that agent — so the only thing
 * keeping them answerable to the same rules is that both are held to the same
 * cases. When one side gains a class, this file gains a row.
 */
class TargetPolicyTest {

    private val policy = TargetPolicy.of(emptyList())

    private fun addr(s: String): InetAddress = InetAddress.getByName(s)

    private fun reason(host: String, port: Int = 443): String? =
        policy.refusalReason(addr(host), port)

    @Test
    fun refusesTheClassesThatReachTheDeviceOwner() {
        val cases = listOf(
            // The owner's own home network. The whole point.
            "192.168.1.1" to TargetPolicy.REASON_PRIVATE,
            "10.0.0.1" to TargetPolicy.REASON_PRIVATE,
            "172.16.0.1" to TargetPolicy.REASON_PRIVATE,
            // Carrier-grade NAT — on a handset, very often the neighbours.
            "100.64.0.1" to TargetPolicy.REASON_PRIVATE,
            "fd00::1" to TargetPolicy.REASON_PRIVATE,
            // The device's own services.
            "127.0.0.1" to TargetPolicy.REASON_LOOPBACK,
            "::1" to TargetPolicy.REASON_LOOPBACK,
            // Link-local, which is where the cloud metadata endpoint lives.
            "169.254.169.254" to TargetPolicy.REASON_LINK_LOCAL,
            "fe80::1" to TargetPolicy.REASON_LINK_LOCAL,
            // Routed to localhost by some stacks.
            "0.0.0.0" to TargetPolicy.REASON_UNSPECIFIED,
            "::" to TargetPolicy.REASON_UNSPECIFIED,
            // Multicast and broadcast: mDNS, SSDP, the local segment.
            "224.0.0.251" to TargetPolicy.REASON_MULTICAST,
            "239.255.255.250" to TargetPolicy.REASON_MULTICAST,
            "255.255.255.255" to TargetPolicy.REASON_MULTICAST,
            "ff02::1" to TargetPolicy.REASON_MULTICAST,
            // Special-purpose space.
            "192.0.0.1" to TargetPolicy.REASON_RESERVED,
            "198.18.0.1" to TargetPolicy.REASON_RESERVED,
            "192.0.2.1" to TargetPolicy.REASON_RESERVED,
            "198.51.100.1" to TargetPolicy.REASON_RESERVED,
            "203.0.113.1" to TargetPolicy.REASON_RESERVED,
            "240.0.0.4" to TargetPolicy.REASON_RESERVED,
            "2001:db8::1" to TargetPolicy.REASON_RESERVED,
        )
        for ((host, want) in cases) {
            assertEquals("$host should be refused as $want", want, reason(host))
        }
    }

    @Test
    fun leavesOrdinaryDestinationsAlone() {
        for (host in listOf("93.184.216.34", "8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertNull("$host is an ordinary destination and must pass", reason(host))
        }
    }

    @Test
    fun theAllowListWinsOverAddressClasses() {
        val p = TargetPolicy.of(listOf("10.0.0.0/8", "192.0.2.7"))
        assertNull("an allow-listed range must pass",
            p.refusalReason(addr("10.1.2.3"), 443))
        assertNull("a bare address in the allow-list is a single host",
            p.refusalReason(addr("192.0.2.7"), 443))
        assertEquals("allow-listing one range must not disable the rest",
            TargetPolicy.REASON_PRIVATE, p.refusalReason(addr("192.168.1.1"), 443))
        assertEquals("a neighbour of an allow-listed host is not allow-listed",
            TargetPolicy.REASON_RESERVED, p.refusalReason(addr("192.0.2.8"), 443))
    }

    @Test
    fun refusesDeniedPortsOnOrdinaryHosts() {
        val p = TargetPolicy.of(emptyList(), setOf(25, 445))
        assertEquals(TargetPolicy.REASON_PORT, p.refusalReason(addr("93.184.216.34"), 25))
        assertEquals(TargetPolicy.REASON_PORT, p.refusalReason(addr("93.184.216.34"), 445))
        assertNull(p.refusalReason(addr("93.184.216.34"), 443))
    }

    @Test
    fun theAllowListDoesNotDefeatThePortList() {
        // An allow-listed range says "this network is mine and reachable". It
        // does not say "and nothing else about the policy applies there".
        val p = TargetPolicy.of(listOf("10.0.0.0/8"), setOf(25))
        assertNull(p.refusalReason(addr("10.1.2.3"), 443))
        assertEquals(TargetPolicy.REASON_PORT, p.refusalReason(addr("10.1.2.3"), 25))
    }

    @Test
    fun malformedAllowListEntriesAreDroppedNotHonoured() {
        val entries = listOf("10.0.0.0/8", "not-a-cidr", "10.0.0.0/99", "", "example.com")
        val bad = TargetPolicy.invalidEntries(entries)
        assertTrue("a garbage entry must be reported", bad.contains("not-a-cidr"))
        assertTrue("an out-of-range prefix must be reported", bad.contains("10.0.0.0/99"))
        assertTrue("a hostname is not a CIDR and must not be resolved", bad.contains("example.com"))

        // And the good entry still works, while nothing else was let through.
        val p = TargetPolicy.of(entries)
        assertNull(p.refusalReason(addr("10.1.2.3"), 443))
        assertEquals(TargetPolicy.REASON_PRIVATE, p.refusalReason(addr("192.168.1.1"), 443))
    }

    // Addresses that carry an IPv4 address inside an IPv6 one. On a handset these
    // are not theoretical: mobile carriers run IPv6-only access with NAT64, so
    // 64:ff9b::a9fe:a9fe genuinely reaches the metadata endpoint from a phone.
    // The Go twin is held to the same table.
    @Test
    fun embeddedIPv4FormsAreClassifiedByWhatTheyCarry() {
        val cases = listOf(
            "64:ff9b::7f00:1" to TargetPolicy.REASON_LOOPBACK,
            "64:ff9b::a9fe:a9fe" to TargetPolicy.REASON_LINK_LOCAL,
            "64:ff9b::c0a8:101" to TargetPolicy.REASON_PRIVATE,
            "::7f00:1" to TargetPolicy.REASON_LOOPBACK,
            "::c0a8:101" to TargetPolicy.REASON_PRIVATE,
            "2002:7f00:1::1" to TargetPolicy.REASON_LOOPBACK,
        )
        for ((host, want) in cases) {
            assertEquals("$host reaches $want through an IPv6 wrapper", want, reason(host))
        }
    }

    // The half that would break real users if it were wrong: NAT64 carrying an
    // ordinary address must pass, or an IPv6-only carrier's subscribers lose the
    // IPv4 internet. 6to4 and the compatible form are refused either way — both
    // are deprecated and nothing legitimate emits them.
    @Test
    fun nat64ToAnOrdinaryAddressIsAllowed() {
        assertNull(reason("64:ff9b::5db8:d822"))
        assertNull(reason("64:ff9b::808:808"))
        assertEquals(TargetPolicy.REASON_RESERVED, reason("2002:5db8:d822::1"))
        assertEquals(TargetPolicy.REASON_RESERVED, reason("::5db8:d822"))
    }

    @Test
    fun thisNetworkIsRefused() {
        assertEquals(TargetPolicy.REASON_RESERVED, reason("0.0.0.1"))
        assertEquals(TargetPolicy.REASON_RESERVED, reason("0.255.255.255"))
    }
}
