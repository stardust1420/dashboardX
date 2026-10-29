package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid

class RetryableObdException(msg: String) : Exception(msg)

class Elm327(private val t: ObdTransport, private val log: DiagLog) {
    private suspend fun cmd(c: String): String {
        log.add("> $c")
        val r = t.send(c)
        log.add("< ${r.replace('\r', ' ').replace('\n', ' ').trim()}")
        return r
    }

    /** Runs the adapter init, discovers supported PIDs. Throws [RetryableObdException] if the car doesn't answer. */
    suspend fun init(): Set<Pid> {
        for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) cmd(c)
        val r0 = cmd("0100")
        val nums = PidCodec.parseSupported(r0, 0x00).toMutableSet()
        if (nums.isEmpty()) throw RetryableObdException("0100 gave no supported PIDs: ${PidCodec.cleanReply(r0)}")
        nums += PidCodec.parseSupported(cmd("0120"), 0x20)
        nums += PidCodec.parseSupported(cmd("0140"), 0x40)
        val supported = Pid.entries.filter { PidCodec.codeOf(it).toInt(16) in nums }.toSet()
        log.add("supported: $supported")
        return supported
    }

    suspend fun read(pid: Pid): Double? = PidCodec.decode(pid, cmd("01" + PidCodec.codeOf(pid)))
}
