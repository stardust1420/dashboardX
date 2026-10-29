package com.karan.dashboardx.phone

import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Pid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagReportTest {
    @Test fun pidsUnknownBeforeConnect() {
        for (s in listOf(ConnectionStatus.IDLE_NEEDS_SETUP, ConnectionStatus.DEMO, ConnectionStatus.CONNECTING)) {
            assertEquals(listOf("PIDs: known after the adapter connects"), DiagReport.pidLines(s, setOf(Pid.OIL)))
        }
    }

    @Test fun pidsListedWhenConnected() {
        val l = DiagReport.pidLines(ConnectionStatus.CONNECTED, setOf(Pid.OIL, Pid.MAF))
        assertEquals("Supported: RPM, SPEED, COOLANT, IAT, THROTTLE, MAP, LOAD", l[0])
        assertEquals("Unsupported: MAF, OIL", l[1])
    }

    @Test fun pidsNoneUnsupported() {
        val l = DiagReport.pidLines(ConnectionStatus.RECONNECTING, emptySet())
        assertEquals("Unsupported: none", l[1])
    }

    @Test fun shareTextHasHeaderAndAllLinesInOrder() {
        val t = DiagReport.shareText(ConnectionStatus.CONNECTED, setOf(Pid.OIL), listOf("a", "b", "c"))
        val lines = t.trimEnd().lines()
        assertEquals("DashboardX diagnostics", lines[0])
        assertEquals("Status: connected", lines[1])
        assertTrue(lines.contains("Unsupported: OIL"))
        assertEquals(listOf("Log (3 lines):", "a", "b", "c"), lines.takeLast(4))
    }

    @Test fun shareTextDropsOldestLinesOverCap() {
        val lines = (1..1000).map { "line %04d".format(it) } // 9 chars + newline each
        val t = DiagReport.shareText(ConnectionStatus.DEMO, emptySet(), lines, maxChars = 2000)
        assertTrue(t.length <= 2000)
        val out = t.trimEnd().lines()
        assertEquals("line 1000", out.last())
        val logHeader = out.first { it.startsWith("Log (") }
        val kept = out.size - out.indexOf(logHeader) - 1
        assertTrue(kept in 100..199)
        assertTrue(logHeader, logHeader.contains("last $kept of 1000") && logHeader.contains("${1000 - kept} older lines omitted"))
        assertEquals("line %04d".format(1000 - kept + 1), out[out.indexOf(logHeader) + 1])
    }
}
