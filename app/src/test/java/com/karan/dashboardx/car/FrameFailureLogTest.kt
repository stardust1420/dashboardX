package com.karan.dashboardx.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameFailureLogTest {
    private val lines = mutableListOf<String>()
    private val log = FrameFailureLog { lines += it }

    @Test fun repeatedSameFailure_logsOnce() {
        repeat(100) { log.failure("lock", IllegalStateException("surface released")) }
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("lock") && lines[0].contains("IllegalStateException") &&
            lines[0].contains("surface released") && lines[0].contains("1 failure"))
    }

    @Test fun changedTypeMessageOrStage_logsAgainWithRunningCount() {
        log.failure("lock", IllegalStateException("a"))
        log.failure("lock", IllegalStateException("a"))
        log.failure("lock", IllegalStateException("b"))      // message changed
        log.failure("lock", IllegalArgumentException("b"))   // type changed
        log.failure("unlock", IllegalArgumentException("b")) // stage changed
        assertEquals(4, lines.size)
        assertTrue(lines[1], lines[1].contains("3 failure"))
        assertTrue(lines[2], lines[2].contains("4 failure"))
        assertTrue(lines[3], lines[3].contains("5 failure"))
    }

    @Test fun success_resetsCount_andSameFailureLogsAgain() {
        log.failure("lock", IllegalStateException("x"))
        log.failure("lock", IllegalStateException("x"))
        log.success()
        log.success() // no failures since: silent
        log.failure("lock", IllegalStateException("x"))
        assertEquals(3, lines.size)
        assertTrue(lines[1], lines[1].contains("after 2 failure"))
        assertTrue(lines[2], lines[2].contains("1 failure"))
    }

    @Test fun success_withoutFailures_isSilent() {
        repeat(10) { log.success() }
        assertEquals(0, lines.size)
    }
}
