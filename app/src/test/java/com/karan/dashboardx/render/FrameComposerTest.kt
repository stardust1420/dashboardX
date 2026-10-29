package com.karan.dashboardx.render

import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.data.CarState
import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.Pid
import com.karan.dashboardx.data.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class FrameComposerTest {
    private fun state(atMs: Long, vararg v: Pair<Pid, Double>, unsupported: Set<Pid> = emptySet()) =
        CarState(readings = v.associate { (p, x) -> p to Reading(x, atMs) }, unsupported = unsupported)

    private fun FrameComposer.at(st: CarState, nowMs: Long) =
        compose(st, ConnectionStatus.CONNECTED, 6200, 105, nowMs)

    @Test
    fun composer_staleValuesAreNull() {
        val c = FrameComposer()
        val st = state(0, Pid.COOLANT to 90.0, Pid.OIL to 95.0, Pid.RPM to 2000.0)
        assertEquals(90.0, c.at(st, 2999).coolant!!, 1e-9)
        val f = c.at(st, 3001)
        assertNull(f.rpm)
        assertEquals(90.0, f.coolant!!, 1e-9) // slow PIDs: 10 s window
        assertEquals(95.0, f.oil!!, 1e-9)
        val g = c.at(st, 10_001)
        assertNull(g.coolant)
        assertNull(g.oil)
    }

    @Test
    fun composer_dtClampedTo100ms() {
        val c = FrameComposer()
        c.at(state(0, Pid.RPM to 1000.0), 0) // first target snaps
        val f = c.at(state(10_000, Pid.RPM to 2000.0), 10_000)
        val expected = 1000.0 + 1000.0 * (1 - exp(-100.0 / 80.0))
        assertEquals(expected, f.rpm!!, 1e-6)
    }

    @Test
    fun composer_negativeDtDoesNotMove() {
        val c = FrameComposer()
        c.at(state(1000, Pid.SPEED to 50.0), 1000)
        val f = c.at(state(1000, Pid.SPEED to 100.0), 900)
        assertEquals(50.0, f.speed!!, 1e-9)
    }

    @Test
    fun composer_chimesOnceOnRisingEdge() {
        val c = FrameComposer()
        val hot = state(0, Pid.COOLANT to 110.0)
        val f = c.at(hot, 0)
        assertTrue(Warning.OVERHEAT in f.warnings)
        assertEquals(setOf(Warning.OVERHEAT), c.chime)
        c.at(hot, 16)
        assertTrue(c.chime.isEmpty())
    }

    @Test
    fun composer_passesThroughEconomyAndUnsupported() {
        val c = FrameComposer()
        val st = state(0, unsupported = setOf(Pid.OIL)).copy(instantKml = 14.2, tripKml = 15.8)
        val f = c.at(st, 0)
        assertTrue(f.oilUnsupported)
        assertEquals(14.2, f.instantKml!!, 1e-9)
        assertEquals(15.8, f.tripKml!!, 1e-9)
        assertEquals(6200, f.redline)
        assertEquals(0.0, f.tSec, 1e-9)
        assertEquals(1.5, c.at(st, 1500).tSec, 1e-9)
    }

    @Test
    fun noCarData_afterThreeSecondsConnectedWithoutRpm() {
        val c = FrameComposer()
        val empty = CarState()
        assertFalse(c.at(empty, 0).noCarData)
        assertFalse(c.at(empty, 3000).noCarData)
        assertTrue(c.at(empty, 3001).noCarData)
    }

    @Test
    fun noCarData_clearsAsSoonAsRpmReturns() {
        val c = FrameComposer()
        c.at(CarState(), 0)
        assertTrue(c.at(CarState(), 5000).noCarData)
        assertFalse(c.at(state(5000, Pid.RPM to 800.0), 5000).noCarData)
    }

    @Test
    fun noCarData_countsFromWhenRpmWentStale() {
        val c = FrameComposer()
        val st = state(0, Pid.RPM to 800.0)
        assertFalse(c.at(st, 3000).noCarData) // still fresh
        assertFalse(c.at(st, 3001).noCarData) // stale from here
        assertFalse(c.at(st, 6001).noCarData)
        assertTrue(c.at(st, 6002).noCarData)
    }

    @Test
    fun noCarData_onlyWhenConnected() {
        val c = FrameComposer()
        for (s in ConnectionStatus.entries.filter { it != ConnectionStatus.CONNECTED }) {
            c.compose(CarState(), s, 6200, 105, 0)
            assertFalse("$s", c.compose(CarState(), s, 6200, 105, 10_000).noCarData)
        }
        // Timer starts when CONNECTED is first seen, not before.
        assertFalse(c.at(CarState(), 10_000).noCarData)
        assertFalse(c.at(CarState(), 13_000).noCarData)
        assertTrue(c.at(CarState(), 13_001).noCarData)
    }
}
