package com.karan.dashboardx.car

/**
 * Rate-limited logging of surface lock/unlock failures. Logs the first failure and every change of
 * stage, exception type or message, with the number of failures since the last successful frame;
 * repeats of the same failure are only counted. [success] resets the count. One thread only.
 */
class FrameFailureLog(private val log: (String) -> Unit) {
    private var failures = 0
    private var lastKey: String? = null

    fun failure(stage: String, e: Throwable) {
        failures++
        val key = "$stage|${e.javaClass.name}|${e.message}"
        if (key == lastKey) return
        lastKey = key
        log("car: $stage failed (${e.javaClass.simpleName}: ${e.message}); $failures failure(s) since last frame")
    }

    fun success() {
        if (failures > 0) log("car: frame posted after $failures failure(s)")
        failures = 0
        lastKey = null
    }
}
