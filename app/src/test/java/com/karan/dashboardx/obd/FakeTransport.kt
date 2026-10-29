package com.karan.dashboardx.obd

import java.io.IOException

/** Scripted transport. Unscripted AT commands reply "OK", anything else "NO DATA". */
class FakeTransport(
    private val script: Map<String, String>,
    private val failAfterSends: Int = Int.MAX_VALUE,
) : ObdTransport {
    val sent = mutableListOf<String>()
    var opened = false
    var closed = false

    override suspend fun open() { opened = true }

    override suspend fun send(cmd: String): String {
        if (sent.size >= failAfterSends) throw IOException("fake link lost")
        sent += cmd
        return script[cmd] ?: if (cmd.startsWith("AT")) "OK" else "NO DATA"
    }

    override fun close() { closed = true }
}
