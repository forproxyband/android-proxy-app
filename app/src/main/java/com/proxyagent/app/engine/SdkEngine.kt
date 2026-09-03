package com.proxyagent.app.engine

import java.io.File
import java.net.DatagramSocket

/**
 * Boundary between the app and the external Proxy Agent SDK.
 *
 * The SDK is an optional composite-build dependency (see
 * settings.gradle.kts): present when the `proxy-agent-sdk-go` repo is
 * checked out beside this one, absent otherwise. Nothing in `main` may
 * reference `com.proxyagent.sdk.*` directly or the app stops building
 * without it — so every SDK type is confined behind these interfaces,
 * implemented in the `src/sdkEngine` source set and stubbed in
 * `src/noSdkEngine`.
 *
 * Note the deliberate asymmetry with the in-tree NATIVE engine: that one
 * is compiled in unconditionally and `ProxyService` talks to it
 * directly. This indirection exists only because the SDK might not be
 * on the classpath.
 */

/** What the app needs from a running agent, whichever engine produced
 *  it. Signatures match the in-tree agent's so existing call sites in
 *  ProxyService are unchanged. */
internal interface AgentHandle {
    /** Bytes written to target hosts — the proxy's exit leg. */
    fun targetUpBytes(): Long

    /** Bytes read back from target hosts. */
    fun targetDownBytes(): Long

    /** False once the supervisor has exited (fatal error, external
     *  stop). ProxyService's respawn loop polls this. */
    fun isRunning(): Boolean

    fun stop(timeoutMs: Long = 5_000L)
}

/**
 * Everything the SDK engine needs to start, in types `main` owns.
 *
 * [networkProfileName] crosses as a String on purpose: the app and the
 * SDK each have their own `NetworkProfile` enum in different packages,
 * and the adapter maps between them. Passing the app's enum here would
 * be harmless but passing the SDK's would leak an SDK type into `main`.
 */
internal data class SdkAgentParams(
    /** Registrator host — set for direct/modem mode, null for balancer. */
    val registratorHost: String?,
    val registratorPort: Int,
    /** Balancer host — set for balancer (resident) mode, null otherwise. */
    val balancerHost: String?,
    val balancerPort: Int,
    val fallbackFileUrl: String?,
    val agentKey: String,
    val agentUuid: String?,
    val dnsServers: String,
    val workDir: File,
    val networkProfileName: String,
    /**
     * Applied to the QUIC uplink's UDP socket before connect, for Wi-Fi
     * return. Null keeps the process default route. Re-invoked on every
     * redial, so a network handover between dials is picked up.
     */
    val quicSocketBinder: ((DatagramSocket) -> Unit)?,
)

/**
 * Starts agents backed by the external SDK. Obtain via
 * [sdkEngineProvider]; check [available] before offering the engine to
 * the user.
 */
internal interface SdkEngineProviderApi {
    /** False in builds where the SDK was not on the classpath. */
    val available: Boolean

    /** Human-readable identification of what is actually linked in —
     *  logged at start so a stale or unexpected SDK is visible. */
    fun describe(): String

    /**
     * Start an agent. [logSink] receives the SDK's structured log
     * records; ProxyService reformats them into the same line
     * vocabulary its status parser already understands.
     *
     * @return a handle, or null if the agent refused to start.
     */
    fun start(
        params: SdkAgentParams,
        logSink: (level: String, msg: String, fields: Map<String, Any?>) -> Unit,
    ): AgentHandle?
}
