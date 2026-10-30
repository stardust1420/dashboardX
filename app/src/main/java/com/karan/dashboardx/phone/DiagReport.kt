package com.karan.dashboardx.phone

import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Pid

/** Pure text building for the diagnostics screen and its Share payload. */
object DiagReport {
    fun statusLine(status: ConnectionStatus): String = "Status: " + when (status) {
        ConnectionStatus.IDLE_NEEDS_SETUP -> "not connected (needs setup)"
        ConnectionStatus.DEMO -> "demo mode"
        ConnectionStatus.CONNECTING -> "connecting"
        ConnectionStatus.CONNECTED -> "connected"
        ConnectionStatus.RECONNECTING -> "reconnecting"
    }

    /**
     * Supported/unsupported PID lines. PID support is only known once the adapter has connected
     * (ObdSource fills `unsupported` after init), so other states say so instead of listing.
     */
    fun pidLines(status: ConnectionStatus, unsupported: Set<Pid>): List<String> {
        if (status != ConnectionStatus.CONNECTED && status != ConnectionStatus.RECONNECTING) {
            return listOf("PIDs: known after the adapter connects")
        }
        val supported = Pid.entries.filter { it !in unsupported }
        val missing = Pid.entries.filter { it in unsupported }
        return listOf(
            "Supported: " + (supported.joinToString(", ") { it.name }.ifEmpty { "none" }),
            "Unsupported: " + (missing.joinToString(", ") { it.name }.ifEmpty { "none" }),
        )
    }

    /** Default Share cap: keeps the Intent well under the ~1 MB Binder transaction limit. */
    const val SHARE_MAX_CHARS = 200_000

    /**
     * Header plus the log, oldest first. If the whole text would exceed [maxChars], the oldest
     * log lines are dropped and a note says how many.
     */
    fun shareText(
        status: ConnectionStatus,
        unsupported: Set<Pid>,
        lines: List<String>,
        maxChars: Int = SHARE_MAX_CHARS,
    ): String {
        val header = buildString {
            appendLine("DashboardX diagnostics")
            appendLine(statusLine(status))
            pidLines(status, unsupported).forEach { appendLine(it) }
        }
        var budget = maxChars - header.length - 64 // room for the "Log" line
        var keepFrom = lines.size
        while (keepFrom > 0 && budget - (lines[keepFrom - 1].length + 1) >= 0) {
            budget -= lines[keepFrom - 1].length + 1
            keepFrom--
        }
        return buildString {
            append(header)
            if (keepFrom == 0) appendLine("Log (${lines.size} lines):")
            else appendLine("Log (last ${lines.size - keepFrom} of ${lines.size} lines; $keepFrom older lines omitted):")
            for (i in keepFrom until lines.size) appendLine(lines[i])
        }
    }
}
