package com.karan.dashboardx.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningsTest {
    @Test fun warn_overheatStrictlyAbove() {
        val w = Warnings()
        val a = w.evaluate(105.0, null, 6200, 105, 0)
        assertFalse(Warning.OVERHEAT in a.active)
        val b = w.evaluate(105.1, null, 6200, 105, 100)
        assertTrue(Warning.OVERHEAT in b.active)
        assertTrue(Warning.OVERHEAT in b.chime)
    }

    @Test fun warn_redline() {
        val r = Warnings().evaluate(null, 6201.0, 6200, 105, 0)
        assertTrue(Warning.REDLINE in r.active)
        assertEquals(setOf(Warning.REDLINE), Warnings().evaluate(null, 6200.0, 6200, 105, 0).active + Warning.REDLINE)
        assertTrue(Warnings().evaluate(null, 6200.0, 6200, 105, 0).active.isEmpty())
    }

    @Test fun warn_chimeCooldown() {
        val w = Warnings()
        fun ev(rpm: Double, t: Long) = w.evaluate(null, rpm, 6200, 105, t)
        assertTrue(Warning.REDLINE in ev(7000.0, 0).chime)
        ev(3000.0, 1000)
        assertFalse(Warning.REDLINE in ev(7000.0, 2000).chime)
        ev(3000.0, 3000)
        assertTrue(Warning.REDLINE in ev(7000.0, 6000).chime)
    }

    @Test fun warn_stayingActiveDoesNotRechime() {
        val w = Warnings()
        w.evaluate(null, 7000.0, 6200, 105, 0)
        assertTrue(w.evaluate(null, 7000.0, 6200, 105, 10000).chime.isEmpty())
    }

    @Test fun warn_nullInputs() {
        val r = Warnings().evaluate(null, null, 6200, 105, 0)
        assertTrue(r.active.isEmpty())
        assertTrue(r.chime.isEmpty())
    }
}
