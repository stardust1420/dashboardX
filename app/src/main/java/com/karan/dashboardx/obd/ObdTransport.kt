package com.karan.dashboardx.obd

/** Byte pipe to an ELM327 adapter. Implementations own the send timeout (2000 ms). */
interface ObdTransport {
    suspend fun open()

    /** Sends [cmd] (transport appends CR) and returns the reply up to the '>' prompt. Throws IOException on failure or timeout. */
    suspend fun send(cmd: String): String

    fun close()
}
