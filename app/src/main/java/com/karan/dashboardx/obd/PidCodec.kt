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

    fun cleanReply(raw: String): String =
        raw.uppercase()
            .replace("SEARCHING...", "")
            .filter { it != '\r' && it != '\n' && it != ' ' && it != '>' }

    /** Returns the data bytes (after the 41<code> header) of the first matching frame, or null. */
    private fun frameData(clean: String, header: String): String? {
        if (clean.isEmpty() || clean.startsWith("7F")) return null
        if (errorMarkers.any { clean.contains(it) }) return null
        var i = clean.indexOf(header)
        while (i >= 0 && i % 2 != 0) i = clean.indexOf(header, i + 1) // byte-aligned only
        if (i < 0) return null
        return clean.substring(i + header.length)
    }

    private fun bytes(data: String, n: Int): IntArray? {
        if (data.length < n * 2) return null
        return IntArray(n) { data.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }
    }

    fun decode(pid: Pid, raw: String): Double? {
        val data = frameData(cleanReply(raw), "41" + pid.code) ?: return null
        return when (pid) {
            Pid.RPM -> bytes(data, 2)?.let { (256 * it[0] + it[1]) / 4.0 }
            Pid.MAF -> bytes(data, 2)?.let { (256 * it[0] + it[1]) / 100.0 }
            Pid.SPEED, Pid.MAP -> bytes(data, 1)?.let { it[0].toDouble() }
            Pid.COOLANT, Pid.IAT, Pid.OIL -> bytes(data, 1)?.let { it[0] - 40.0 }
            Pid.THROTTLE, Pid.LOAD -> bytes(data, 1)?.let { it[0] * 100.0 / 255.0 }
        }
    }

    /** Parses a 41 00/20/40 reply; bit MSB of first data byte = PID base+1. */
    fun parseSupported(raw: String, base: Int): Set<Int> {
        val header = "41" + "%02X".format(base)
        val data = frameData(cleanReply(raw), header) ?: return emptySet()
        val b = bytes(data, 4) ?: return emptySet()
        val mask = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        return (0 until 32).filter { (mask shr (31 - it)) and 1L == 1L }.map { base + it + 1 }.toSet()
    }
}
