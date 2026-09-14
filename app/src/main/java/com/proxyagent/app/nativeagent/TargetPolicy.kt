package com.proxyagent.app.nativeagent

import java.net.InetAddress

/**
 * Refuses buyer-chosen destinations that must never be dialled from the device
 * this agent runs on.
 *
 * The device belongs to an ordinary person who installed a partner's app. They
 * never see what is sent through it, but everything it reaches, it reaches from
 * inside their home: their router's admin page, a NAS, a printer, a camera, the
 * app's own loopback listeners. Nobody buys residential egress in order to
 * arrive at 192.168.1.1 — a buyer who names one is using the exit node to get
 * somewhere they could not otherwise reach.
 *
 * Server-side checks exist too, but they cannot finish the job: they decide on
 * the hostname the buyer supplied, or at best on an address resolved from the
 * server's own vantage point, and neither is necessarily what this handset will
 * dial. A short TTL, a split-horizon answer, a different resolver — and the
 * address reached is not the address anyone examined. Only the device knows,
 * and only at the moment it resolves. So this check runs here, on the resolved
 * address, immediately before connect.
 *
 * The classification mirrors `targetpolicy` in the Go SDK
 * (`proxy-server-go-sdk/targetpolicy`, copied to
 * `proxy-agent-sdk-go/internal/targetpolicy`). It is a re-implementation rather
 * than a copy because this engine is a Kotlin port of that agent and shares no
 * code with it; [TargetPolicyTest] pins the same cases the Go tests pin, so the
 * two stay answerable to the same table.
 */
internal class TargetPolicy private constructor(
    private val allow: List<Cidr>,
    private val denyPorts: Set<Int>,
) {

    /**
     * Returns the reason this target is refused, or null when it may be dialled.
     *
     * The vocabulary matches the Go policy's, so a refusal logged on a device
     * and one logged on a server read the same in an investigation.
     */
    fun refusalReason(addr: InetAddress, port: Int): String? {
        // Port first, and the ordering is not incidental: the allow-list must
        // not defeat it. An operator who allows a range is saying "this range
        // is mine and it is reachable", not "ignore the rest of the policy
        // there" — a refused port stays refused inside an allowed network. The
        // Go policy orders these the same way for the same reason.
        if (port in denyPorts) return REASON_PORT
        if (allow.any { it.contains(addr) }) return null
        return classify(addr)
    }

    private fun classify(addr: InetAddress): String? {
        // IPv6 forms that carry an IPv4 address inside them. A handset is exactly
        // where these work: mobile carriers run IPv6-only access networks with
        // NAT64, so 64:ff9b::a9fe:a9fe really does reach 169.254.169.254 from a
        // phone, and no IPv4 predicate sees it.
        embeddedV4(addr)?.let { (inner, deprecated) ->
            classify(inner)?.let { return it }
            // 6to4 and the IPv4-compatible form are deprecated and nothing
            // legitimate emits them, so they are refused whatever they carry.
            // NAT64 is not: it is how an IPv6-only phone reaches the IPv4
            // internet, and refusing it would take the ordinary web away from
            // the devices this policy protects.
            return if (deprecated) REASON_RESERVED else null
        }

        if (addr.isAnyLocalAddress) return REASON_UNSPECIFIED
        if (addr.isLoopbackAddress) return REASON_LOOPBACK
        if (addr.isLinkLocalAddress) return REASON_LINK_LOCAL
        if (addr.isMulticastAddress) return REASON_MULTICAST
        // Covers 10/8, 172.16/12, 192.168/16 for IPv4 and the deprecated
        // fec0::/10 for IPv6. Everything else private is in the tables below.
        if (addr.isSiteLocalAddress) return REASON_PRIVATE

        val b = addr.address
        return if (b.size == 4) classifyV4(b) else classifyV6(b)
    }

    /**
     * Extracts the IPv4 address carried inside an IPv6 transition address, with a
     * flag saying whether the wrapper itself is a deprecated form that should be
     * refused regardless of what it wraps. Mirrors embeddedV4 in the Go policy.
     *
     * The v4-mapped form ::ffff:a.b.c.d is absent on purpose: Java hands those
     * back as an Inet4Address already.
     */
    private fun embeddedV4(addr: InetAddress): Pair<InetAddress, Boolean>? {
        val b = addr.address
        if (b.size != 16) return null

        fun at(i: Int) = InetAddress.getByAddress(byteArrayOf(b[i], b[i + 1], b[i + 2], b[i + 3]))

        return when {
            NAT64.contains(b) -> at(12) to false
            SIX_TO_FOUR.contains(b) -> at(2) to true
            V4_COMPAT.contains(b) -> {
                // :: and ::1 are the unspecified and loopback addresses and have
                // their own predicates; the rest of ::/96 is the deprecated form.
                if (b[12].toInt() == 0 && b[13].toInt() == 0 && b[14].toInt() == 0 &&
                    (b[15].toInt() and 0xFF) <= 1
                ) null else at(12) to true
            }
            else -> null
        }
    }

    private fun classifyV4(b: ByteArray): String? {
        for ((cidr, reason) in V4_SPECIAL) {
            if (cidr.contains(b)) return reason
        }
        return null
    }

    private fun classifyV6(b: ByteArray): String? {
        for ((cidr, reason) in V6_SPECIAL) {
            if (cidr.contains(b)) return reason
        }
        return null
    }

    companion object {
        const val REASON_LOOPBACK = "loopback"
        const val REASON_PRIVATE = "private"
        const val REASON_LINK_LOCAL = "link-local"
        const val REASON_UNSPECIFIED = "unspecified"
        const val REASON_MULTICAST = "multicast"
        const val REASON_RESERVED = "reserved"
        const val REASON_PORT = "port"

        /**
         * Builds a policy. Unparseable entries are dropped rather than thrown:
         * this runs inside a partner's application, and taking their app down
         * over a malformed configuration string is not ours to do. Dropping an
         * allow-list entry fails towards refusing more, which is the safe
         * direction; [invalidEntries] reports what was dropped so the caller
         * can log it.
         */
        fun of(allowCidrs: List<String>, denyPorts: Set<Int> = emptySet()): TargetPolicy =
            TargetPolicy(allowCidrs.mapNotNull(Cidr::parse), denyPorts)

        /** The entries [of] could not parse, for logging. */
        fun invalidEntries(allowCidrs: List<String>): List<String> =
            allowCidrs.filter { Cidr.parse(it) == null }

        // Special-purpose ranges the java.net predicates do not cover.
        // Deliberately the same table as the Go policy's.
        private val V4_SPECIAL: List<Pair<Cidr, String>> = listOf(
            // Carrier-grade NAT. On a mobile handset this is very often the
            // network the device itself is on, which makes its neighbours
            // reachable from here and from nowhere else.
            cidr("100.64.0.0/10") to REASON_PRIVATE,
            // RFC 1122 "this network". Only 0.0.0.0 itself is isAnyLocalAddress,
            // yet the whole /8 is unroutable and some stacks treat 0.0.0.1 as
            // localhost.
            cidr("0.0.0.0/8") to REASON_RESERVED,
            cidr("255.255.255.255/32") to REASON_MULTICAST,
            cidr("192.0.0.0/24") to REASON_RESERVED,
            cidr("198.18.0.0/15") to REASON_RESERVED,
            cidr("192.0.2.0/24") to REASON_RESERVED,
            cidr("198.51.100.0/24") to REASON_RESERVED,
            cidr("203.0.113.0/24") to REASON_RESERVED,
            cidr("240.0.0.0/4") to REASON_RESERVED,
        )

        private val V6_SPECIAL: List<Pair<Cidr, String>> = listOf(
            cidr("fc00::/7") to REASON_PRIVATE,
            cidr("100::/64") to REASON_RESERVED,
            cidr("2001:db8::/32") to REASON_RESERVED,
        )

        // Same three wrappers the Go policy knows about.
        private val NAT64 = cidr("64:ff9b::/96")
        private val SIX_TO_FOUR = cidr("2002::/16")
        private val V4_COMPAT = cidr("::/96")

        private fun cidr(s: String): Cidr =
            Cidr.parse(s) ?: throw IllegalStateException("built-in CIDR is malformed: $s")
    }

    /** A network prefix, compared on raw bytes so v4 and v6 share one path. */
    internal class Cidr private constructor(
        private val network: ByteArray,
        private val bits: Int,
    ) {
        fun contains(addr: InetAddress): Boolean = contains(addr.address)

        fun contains(candidate: ByteArray): Boolean {
            if (candidate.size != network.size) return false
            var remaining = bits
            var i = 0
            while (remaining >= 8) {
                if (candidate[i] != network[i]) return false
                remaining -= 8
                i++
            }
            if (remaining == 0) return true
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            return (candidate[i].toInt() and mask) == (network[i].toInt() and mask)
        }

        companion object {
            fun parse(spec: String): Cidr? {
                val text = spec.trim()
                if (text.isEmpty()) return null
                val slash = text.indexOf('/')
                val hostPart = if (slash < 0) text else text.substring(0, slash)

                // A bare address is a /32 or /128 — an operator writing a single
                // machine into the allow-list should not have to say so twice.
                val addr = parseLiteral(hostPart) ?: return null
                val full = addr.address.size * 8
                if (slash < 0) return Cidr(addr.address, full)

                val bits = text.substring(slash + 1).trim().toIntOrNull() ?: return null
                if (bits < 0 || bits > full) return null
                return Cidr(addr.address, bits)
            }

            /**
             * Parses a literal without ever consulting DNS. InetAddress.getByName
             * resolves anything that is not a literal, which on this path would
             * turn a typo in a configuration string into a name lookup.
             */
            private fun parseLiteral(text: String): InetAddress? {
                val looksNumeric = text.any { it == ':' } ||
                    (text.isNotEmpty() && text.all { it.isDigit() || it == '.' })
                if (!looksNumeric) return null
                return try {
                    InetAddress.getByName(text)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}

/**
 * Raised when [TargetPolicy] refuses a target.
 *
 * A distinct type because the difference matters twice on the way out: the
 * failure streak that tears a session down must not count it (a refusal says
 * nothing about the health of the data plane, and counting it would let a buyer
 * take a handset off the network by aiming enough opens at a blocked address),
 * and the reason sent back to the server must not carry the resolved address.
 */
internal class TargetRefusedException(val reason: String) :
    java.io.IOException("target refused by device policy ($reason)")
