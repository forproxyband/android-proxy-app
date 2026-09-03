package com.proxyagent.app.engine

import com.proxyagent.sdk.ProxyAgent
import com.proxyagent.sdk.ProxyAgentConfig
import com.proxyagent.sdk.quic.NativeQuicTransport
import com.proxyagent.sdk.quic.NetworkProfile

/**
 * Real SDK engine — compiled only when the SDK composite build is
 * present. Its twin in `src/noSdkEngine` is compiled otherwise; the two
 * must keep the same class name and API.
 *
 * This is the ONLY file in the app that imports `com.proxyagent.sdk.*`.
 * It is also the app's proof that the SDK works as a genuine external
 * AAR — if the published API is awkward or incomplete, it shows up here
 * first.
 */
internal object SdkEngineProvider : SdkEngineProviderApi {

    override val available: Boolean = true

    override fun describe(): String {
        // Where the classes were actually loaded from. On a stale or
        // duplicated dependency this is the line that gives it away.
        val src = try {
            ProxyAgent::class.java.protectionDomain?.codeSource?.location?.toString()
        } catch (_: Throwable) {
            null
        }
        return "com.proxyagent.sdk.ProxyAgent" + (src?.let { " from $it" } ?: "")
    }

    override fun start(
        params: SdkAgentParams,
        logSink: (level: String, msg: String, fields: Map<String, Any?>) -> Unit,
    ): AgentHandle? {
        val agent = ProxyAgent()
        agent.setLogSink { level, msg, fields -> logSink(level, msg, fields) }

        // REBOOT is deliberately NOT wired via setRebootListener: the SDK
        // also emits "REBOOT received from registrator" through the log
        // sink, and ProxyService.parseAgentLine already triggers the IP
        // cycle off that line. Subscribing to both would fire twice for
        // one REBOOT. Same reasoning as the in-tree NATIVE engine.

        val profile = toSdkProfile(params.networkProfileName)

        val cfg = if (params.balancerHost != null) {
            // Resident path — the balancer assigns a registrator.
            ProxyAgentConfig.balancer(
                host = params.balancerHost,
                port = params.balancerPort,
                agentKey = params.agentKey,
                workDir = params.workDir,
                agentUuid = params.agentUuid,
                fallbackFileUrl = params.fallbackFileUrl,
                quicTransportFactory = NativeQuicTransport.Factory(
                    uplinkSocketBinder = params.quicSocketBinder,
                    networkProfile = profile,
                ),
                networkProfile = profile,
                dnsServers = params.dnsServers,
            )
        } else {
            ProxyAgentConfig.directRegistrator(
                host = params.registratorHost ?: return null,
                port = params.registratorPort,
                agentKey = params.agentKey,
                workDir = params.workDir,
                agentUuid = params.agentUuid,
                quicTransportFactory = NativeQuicTransport.Factory(
                    uplinkSocketBinder = params.quicSocketBinder,
                    networkProfile = profile,
                ),
                networkProfile = profile,
                dnsServers = params.dnsServers,
            )
        }

        return if (agent.start(cfg)) SdkAgentHandle(agent) else null
    }

    /** App enum name -> SDK enum. The two enums are intentionally
     *  separate types; an unknown name falls back to the SDK's own
     *  default rather than throwing, because a profile mismatch must not
     *  be able to stop the agent from starting. */
    private fun toSdkProfile(name: String): NetworkProfile =
        runCatching { NetworkProfile.valueOf(name) }.getOrDefault(NetworkProfile.LOW_100)
}

private class SdkAgentHandle(private val agent: ProxyAgent) : AgentHandle {
    override fun targetUpBytes(): Long = agent.targetUpBytes()
    override fun targetDownBytes(): Long = agent.targetDownBytes()
    override fun isRunning(): Boolean = agent.getStatus().running
    override fun stop(timeoutMs: Long) = agent.stop(timeoutMs)
}
