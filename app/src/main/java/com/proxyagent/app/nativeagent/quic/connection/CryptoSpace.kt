package com.proxyagent.app.nativeagent.quic.connection

import com.proxyagent.app.nativeagent.quic.crypto.DirectionalKeys
import com.proxyagent.app.nativeagent.quic.crypto.HeaderProtection
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

    val recovery: SpaceRecovery = SpaceRecovery(space)

    /** True once both send and receive keys are installed for this space. */
    fun ready(): Boolean = send != null && receive != null

    /** Install send-direction keys from a TLS traffic secret. */
    fun installSendKeys(secret: ByteArray) {
        val keys = InitialKeys.deriveAeadKeys(secret)
        send = PacketProtection(keys)
        sendHp = HeaderProtection(keys.hp)
    }

    /** Install receive-direction keys from a TLS traffic secret. */
    fun installReceiveKeys(secret: ByteArray) {
        val keys = InitialKeys.deriveAeadKeys(secret)
        receive = PacketProtection(keys)
        receiveHp = HeaderProtection(keys.hp)
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
