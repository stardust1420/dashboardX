package com.karan.dashboardx.obd

/** Byte pipe to an ELM327 adapter. */
interface ObdTransport {
    suspend fun open()

    /**
     * Sends [cmd] (transport appends CR) and returns the reply up to the '>' prompt.
     * Throws IOException on failure or when no prompt arrives within [timeoutMs].
     */
    suspend fun send(cmd: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String

    fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2000L
    }
}
