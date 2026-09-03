package com.proxyagent.app.engine

import com.proxyagent.app.nativeagent.NativeProxyAgent

/**
 * Adapts the app's in-tree NATIVE engine to [AgentHandle].
 *
 * Its only reason to exist is so `ProxyService` can hold one field for
 * whichever in-process agent is live — the in-tree copy or the one from
 * the external SDK — instead of branching at every byte-counter and
 * stop() call site.
 *
 * Unlike the SDK adapter this class is always compiled in: the in-tree
 * engine is not optional.
 */
internal class NativeAgentHandle(private val agent: NativeProxyAgent) : AgentHandle {
    override fun targetUpBytes(): Long = agent.targetUpBytes()
    override fun targetDownBytes(): Long = agent.targetDownBytes()
    override fun isRunning(): Boolean = agent.getStatus().running
    override fun stop(timeoutMs: Long) = agent.stop(timeoutMs)
}
