package com.karan.dashboardx.obd

/** Thread-safe ring buffer of timestamped diagnostic lines. */
class DiagLog(private val capacity: Int = 2000, private val clock: () -> Long = System::currentTimeMillis) {
    private val buf = ArrayDeque<String>()

    fun add(line: String) {
        val stamped = String.format("%tT.%<tL %s", clock(), line)
        synchronized(buf) {
            if (buf.size >= capacity) buf.removeFirst()
            buf.addLast(stamped)
        }
    }

    fun lines(): List<String> = synchronized(buf) { buf.toList() }
}
