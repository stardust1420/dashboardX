package com.karan.dashboardx.obd

import java.io.IOException

/**
 * Scripted transport. [sequences] replies are used first, one per send of that command; then
 * [script]; then unscripted AT commands reply "OK", anything else "NO DATA".
 * Records every command and the timeout it was sent with.
 */
class FakeTransport(
    private val script: Map<String, String>,
    private val failAfterSends: Int = Int.MAX_VALUE,
    sequences: Map<String, List<String>> = emptyMap(),
) : ObdTransport {
    val sent = mutableListOf<String>()
    val timeouts = mutableListOf<Long>()
    var opened = false
    var closed = false
    private val queues = sequences.mapValues { ArrayDeque(it.value) }

    override suspend fun open() { opened = true }

    override suspend fun send(cmd: String, timeoutMs: Long): String {
        if (sent.size >= failAfterSends) throw IOException("fake link lost")
        sent += cmd
        timeouts += timeoutMs
        queues[cmd]?.removeFirstOrNull()?.let { return it }
        return script[cmd] ?: if (cmd.startsWith("AT")) "OK" else "NO DATA"
    }

    override fun close() { closed = true }
}
