package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid

class RetryableObdException(msg: String) : Exception(msg)

/**
 * ELM327 protocol on top of an [ObdTransport].
 *
 * Logging: init commands and replies are logged in full. Poll traffic ([read]) is logged in full
 * only for the first [VERBOSE_CYCLES] poll cycles after [init]; after that only replies that carry
 * no data (errors, NO DATA) are logged, plus a one-line summary every [SUMMARY_MS]. The caller
 * marks the end of each poll cycle with [endCycle].
 */
class Elm327(
    private val t: ObdTransport,
    private val log: DiagLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cycle = 0
    private var polled = 0
    private var noData = 0
    private var lastRpm: Double? = null
    private var lastSummaryMs = 0L
    private var lastNonData: String? = null

    private suspend fun cmd(c: String, timeoutMs: Long = ObdTransport.DEFAULT_TIMEOUT_MS): String {
        log.add("> $c")
        val r = t.send(c, timeoutMs)
        log.add("< ${oneLine(r)}")
        return r
    }

    private fun oneLine(r: String) = r.replace('\r', ' ').replace('\n', ' ').trim()

    /** Runs the adapter init, then [probe]. Throws [RetryableObdException] if the car doesn't answer. */
    suspend fun init(): Set<Pid> {
        cmd("ATZ", ATZ_TIMEOUT_MS)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) cmd(c)
        return probe()
    }

    /**
     * Asks the car for its supported PIDs (0100/0120/0140, then 0160..01A0 while advertised). The first 0100 after ATSP0 makes the
     * adapter search for the protocol, which can take several seconds, hence the long timeout.
     * Throws [RetryableObdException] if the car doesn't answer (ignition off, UNABLE TO CONNECT).
     */
    suspend fun probe(): Set<Pid> {
        val r0 = cmd("0100", PROBE_TIMEOUT_MS)
        val nums = PidCodec.parseSupported(r0, 0x00).toMutableSet()
        if (nums.isEmpty()) throw RetryableObdException("0100 gave no supported PIDs: ${PidCodec.cleanReply(r0)}")
        nums += PidCodec.parseSupported(cmd("0120", PROBE_TIMEOUT_MS), 0x20)
        nums += PidCodec.parseSupported(cmd("0140", PROBE_TIMEOUT_MS), 0x40)
        // Not polled: only logged, to learn whether this car reports its gear (PID A4) directly.
        // Each range is asked for only if the previous one advertises it, so most cars add nothing.
        for (base in listOf(0x60, 0x80, 0xA0)) {
            if (base !in nums) break
            nums += PidCodec.parseSupported(cmd("01%02X".format(base), PROBE_TIMEOUT_MS), base)
        }
        log.add("gear PID A4: ${if (GEAR_PID in nums) "supported" else "not supported"}")
        val supported = Pid.entries.filter { PidCodec.codeOf(it).toInt(16) in nums }.toSet()
        log.add("supported: $supported")
        cycle = 0
        polled = 0
        noData = 0
        lastNonData = null
        lastSummaryMs = clock()
        log.add("polling; logging the first $VERBOSE_CYCLES cycles, then a summary every ${SUMMARY_MS / 1000} s")
        return supported
    }

    suspend fun read(pid: Pid): Double? {
        val c = "01" + PidCodec.codeOf(pid)
        val verbose = cycle < VERBOSE_CYCLES
        if (verbose) log.add("> $c")
        val r = t.send(c)
        val v = PidCodec.decode(pid, r)
        polled++
        if (v == null) {
            noData++
            val line = oneLine(r)
            val key = "$c|$line"
            if (verbose) {
                log.add("< $line")
            } else if (key != lastNonData) {
                log.add("> $c")
                log.add("< $line")
            }
            lastNonData = key
        } else {
            if (verbose) log.add("< ${oneLine(r)}")
            if (pid == Pid.RPM) lastRpm = v
        }
        return v
    }

    /** Marks the end of a poll cycle; logs the periodic summary when it is due. */
    fun endCycle() {
        cycle++
        val now = clock()
        if (now - lastSummaryMs >= SUMMARY_MS) {
            val rpm = lastRpm?.let { Math.round(it).toString() } ?: "none"
            log.add("polled $polled cmds, $noData no-data, last rpm $rpm")
            lastSummaryMs = now
            polled = 0
            noData = 0
            lastNonData = null
        }
    }

    companion object {
        const val ATZ_TIMEOUT_MS = 3000L
        const val PROBE_TIMEOUT_MS = 10_000L
        const val VERBOSE_CYCLES = 3
        const val SUMMARY_MS = 30_000L
        /** Mode 01 "transmission actual gear" (SAE J1979). */
        const val GEAR_PID = 0xA4
    }
}
