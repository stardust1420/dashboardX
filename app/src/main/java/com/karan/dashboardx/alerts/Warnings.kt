package com.karan.dashboardx.alerts

enum class Warning { OVERHEAT, REDLINE }

data class WarningResult(val active: Set<Warning>, val chime: Set<Warning>)

/** Evaluates warnings; chimes on the rising edge, at most once per cooldown per type. */
class Warnings(private val cooldownMs: Long = 5000) {
    private val wasActive = mutableSetOf<Warning>()
    private val lastChimeMs = mutableMapOf<Warning, Long>()

    fun evaluate(coolantC: Double?, rpm: Double?, redline: Int, coolantWarn: Int, nowMs: Long): WarningResult {
        val active = mutableSetOf<Warning>()
        if (coolantC != null && coolantC > coolantWarn) active += Warning.OVERHEAT
        if (rpm != null && rpm > redline) active += Warning.REDLINE

        val chime = mutableSetOf<Warning>()
        for (w in active) {
            if (w in wasActive) continue
            val last = lastChimeMs[w]
            if (last == null || nowMs - last >= cooldownMs) {
                chime += w
                lastChimeMs[w] = nowMs
            }
        }
        wasActive.clear()
        wasActive.addAll(active)
        return WarningResult(active, chime)
    }
}
