package com.karan.dashboardx.obd

import com.karan.dashboardx.data.Pid

object PollSchedule {
    /**
     * RPM and throttle every cycle; speed and airflow (MAF, else MAP) on even cycles;
     * coolant, IAT and oil every 10th cycle; load on cycle 5 of each 10.
     */
    fun pidsFor(cycle: Int, supported: Set<Pid>): List<Pid> {
        val l = mutableListOf(Pid.RPM, Pid.THROTTLE)
        if (cycle % 2 == 0) {
            l += Pid.SPEED
            l += if (Pid.MAF in supported) Pid.MAF else Pid.MAP
        }
        if (cycle % 10 == 0) l += listOf(Pid.COOLANT, Pid.IAT, Pid.OIL)
        if (cycle % 10 == 5) l += Pid.LOAD
        return l.filter { it in supported }
    }
}
