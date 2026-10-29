package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid

object PidCodec {
    /** Mode 01 PID code as two hex chars. Use `with(PidCodec) { pid.code }` or [codeOf]. */
    val Pid.code: String
        get() = when (this) {
            Pid.RPM -> "0C"
            Pid.SPEED -> "0D"
            Pid.COOLANT -> "05"
            Pid.IAT -> "0F"
            Pid.THROTTLE -> "11"
            Pid.MAF -> "10"
            Pid.MAP -> "0B"
            Pid.LOAD -> "04"
            Pid.OIL -> "5C"
        }

    fun codeOf(pid: Pid): String = pid.code

    private val errorMarkers = listOf(
        "NODATA", "?", "STOPPED", "ERROR", "UNABLETOCONNECT", "CANERROR", "BUSINIT",
    )

    /** Whole reply on one line without separators (for log and error messages only). */
    fun cleanReply(raw: String): String = cleanLine(raw.replace('\r', ' ').replace('\n', ' '))

    /** One reply line: upper case, no "SEARCHING...", spaces, tabs or prompt. */
    private fun cleanLine(line: String): String =
        line.uppercase()
            .replace("SEARCHING...", "")
            .filter { it != ' ' && it != '>' && it != '\t' }

    /** Cleaned non-empty lines of [raw]: one per frame (multi-ECU replies have several). */
    private fun lines(raw: String): List<String> =
        raw.split('\r', '\n').map(::cleanLine).filter { it.isNotEmpty() }

    /**
     * The first [n] data bytes (after [header]) of every line that starts with [header] and carries
     * at least [n] bytes. Error and negative-response lines are skipped.
     */
    private fun frames(raw: String, header: String, n: Int): List<IntArray> =
        lines(raw)
            .filter { it.startsWith(header) && errorMarkers.none(it::contains) }
            .mapNotNull { bytes(it.substring(header.length), n) }

    private fun bytes(data: String, n: Int): IntArray? {
        if (data.length < n * 2) return null
        return IntArray(n) { data.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }
    }

    private fun byteCount(pid: Pid): Int = when (pid) {
        Pid.RPM, Pid.MAF -> 2
        else -> 1
    }

    /** Value from the first line that starts with 41<code> and has enough bytes, or null. */
    fun decode(pid: Pid, raw: String): Double? {
        val b = frames(raw, "41" + pid.code, byteCount(pid)).firstOrNull() ?: return null
        return when (pid) {
            Pid.RPM -> (256 * b[0] + b[1]) / 4.0
            Pid.MAF -> (256 * b[0] + b[1]) / 100.0
            Pid.SPEED, Pid.MAP -> b[0].toDouble()
            Pid.COOLANT, Pid.IAT, Pid.OIL -> b[0] - 40.0
            Pid.THROTTLE, Pid.LOAD -> b[0] * 100.0 / 255.0
        }
    }

    /**
     * Parses a 41 00/20/40 reply; bit MSB of first data byte = PID base+1. When several ECUs
     * answer, the masks of every matching line are unioned.
     */
    fun parseSupported(raw: String, base: Int): Set<Int> {
        val header = "41" + "%02X".format(base)
        var mask = 0L
        for (b in frames(raw, header, 4)) {
            mask = mask or (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        }
        return (0 until 32).filter { (mask shr (31 - it)) and 1L == 1L }.map { base + it + 1 }.toSet()
    }
}
