package com.proxyagent.app.nativeagent.quic.connection

import com.proxyagent.app.nativeagent.quic.crypto.DirectionalKeys
import com.proxyagent.app.nativeagent.quic.crypto.HeaderProtection
import com.proxyagent.app.nativeagent.quic.crypto.Hkdf
import com.proxyagent.app.nativeagent.quic.crypto.InitialKeys
import com.proxyagent.app.nativeagent.quic.crypto.PacketProtection
import com.proxyagent.app.nativeagent.quic.recovery.SpaceRecovery
import com.proxyagent.app.nativeagent.quic.wire.PacketNumberSpace

/**
 * Per-packet-number-space crypto + bookkeeping container.
 *
 * Three live for a fully-established connection:
 *  - Initial (set up before the first packet)
 *  - Handshake (installed after the TLS ServerHello)
 *  - 1-RTT / Application (installed after server's Finished)
 *
 * Each holds:
 *  - Send / receive AEAD + HP keys
 *  - Next packet number to use
 *  - Largest received PN (for header-protection-aware decoding)
 *  - Recovery state (sent packets, received PNs for ACK gen)
 *
 * Mutable — the connection layer reads and writes from receive
 * and send threads. We keep individual fields atomic / volatile
 * where contention matters; the recovery sub-object owns its
 * own lock for compound operations.
 */
internal class CryptoSpace(val space: PacketNumberSpace) {
    var send: PacketProtection? = null
    var sendHp: HeaderProtection? = null
    var receive: PacketProtection? = null
    var receiveHp: HeaderProtection? = null

    @Volatile var nextSendPn: Long = 0L
    @Volatile var largestReceivedPn: Long = -1L
    @Volatile var largestAckedSentPn: Long = -1L

    /** Current 1-RTT key phase (RFC 9001 §6). 0 until the first key
     *  update; flipped on each adopted update. We set this bit on
     *  short headers we send and compare it on short headers we
     *  receive. Irrelevant for Initial/Handshake (long headers). */
    @Volatile var keyPhase: Int = 0
    /** Current per-direction 1-RTT traffic secrets, retained so a key
     *  update can derive the next generation via "quic ku". Null for
     *  Initial (seed-derived) and before 1-RTT keys are installed. */
    private var sendSecret: ByteArray? = null
    private var recvSecret: ByteArray? = null

    val recovery: SpaceRecovery = SpaceRecovery(space)

    /** True once both send and receive keys are installed for this space. */
    fun ready(): Boolean = send != null && receive != null

    /** Install send-direction keys from a TLS traffic secret. */
    fun installSendKeys(secret: ByteArray) {
        sendSecret = secret
        val keys = InitialKeys.deriveAeadKeys(secret)
        send = PacketProtection(keys)
        sendHp = HeaderProtection(keys.hp)
    }

    /** Install receive-direction keys from a TLS traffic secret. */
    fun installReceiveKeys(secret: ByteArray) {
        recvSecret = secret
        val keys = InitialKeys.deriveAeadKeys(secret)
        receive = PacketProtection(keys)
        receiveHp = HeaderProtection(keys.hp)
    }

    /**
     * Trial next-generation RECEIVE keys for a peer-initiated key
     * update (RFC 9001 §6). The caller decrypts the triggering packet
     * with this and, only on success, calls [commitKeyUpdate]. HP keys
     * are unchanged by a key update — and [PacketProtection] ignores
     * the hp field anyway — so reusing deriveAeadKeys here is safe.
     * Returns null if no 1-RTT receive secret is installed yet.
     */
    fun nextReceiveProtection(): PacketProtection? {
        val s = recvSecret ?: return null
        val next = Hkdf.expandLabel(s, "quic ku", length = 32)
        return PacketProtection(InitialKeys.deriveAeadKeys(next))
    }

    /**
     * Commit a key update: advance BOTH directions' secrets one
     * generation, install the new AEAD keys (HP keys untouched), and
     * flip [keyPhase]. Call only after a trial decrypt with
     * [nextReceiveProtection] has succeeded — AEAD authenticity proves
     * the update is genuine, so we never commit on a forged/bit-flipped
     * phase bit.
     */
    fun commitKeyUpdate() {
        recvSecret?.let {
            val next = Hkdf.expandLabel(it, "quic ku", length = 32)
            recvSecret = next
            receive = PacketProtection(InitialKeys.deriveAeadKeys(next))
        }
        sendSecret?.let {
            val next = Hkdf.expandLabel(it, "quic ku", length = 32)
            sendSecret = next
            send = PacketProtection(InitialKeys.deriveAeadKeys(next))
        }
        keyPhase = keyPhase xor 1
    }

    /** Install both directions at once given a [DirectionalKeys]
     *  pair. Used for Initial-level keys where both sides are
     *  derived from a known seed (the server DCID) and we can
     *  build them up front. */
    fun installInitialKeys(sendDir: DirectionalKeys, recvDir: DirectionalKeys) {
        send = PacketProtection(sendDir)
        sendHp = HeaderProtection(sendDir.hp)
        receive = PacketProtection(recvDir)
        receiveHp = HeaderProtection(recvDir.hp)
    }

    /** Allocate and return the next send packet number, advancing. */
    fun nextPn(): Long {
        val p = nextSendPn
        nextSendPn = p + 1
        return p
    }
}
