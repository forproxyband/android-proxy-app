package com.proxyagent.app.engine

/**
 * Stub SDK engine — compiled when the proxy-agent SDK repo is NOT
 * checked out beside this one, so the app still builds standalone.
 *
 * Keep this file's class name and API identical to the real
 * implementation in `src/sdkEngine`; exactly one of the two is on the
 * source path (see app/build.gradle.kts).
 */
internal object SdkEngineProvider : SdkEngineProviderApi {

    override val available: Boolean = false

    override fun describe(): String =
        "not compiled in — build with the proxy-agent-sdk-go repo checked " +
            "out at ../proxy-agent-sdk-go to enable the SDK engine"

    override fun start(
        params: SdkAgentParams,
        logSink: (level: String, msg: String, fields: Map<String, Any?>) -> Unit,
    ): AgentHandle? {
        // Unreachable through the UI — Settings hides the SDK option when
        // BuildConfig.SDK_ENGINE_AVAILABLE is false. Reachable via a
        // stale `engine=sdk` preference carried over from an APK that
        // did have it, so report rather than crash.
        logSink("ERROR", "SDK engine is not available in this build", emptyMap())
        return null
    }
}
