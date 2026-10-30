package com.karan.dashboardx.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsInputTest {
    private fun <T> ok(p: Parsed<T>): T = (p as? Parsed.Ok<T>)?.value ?: throw AssertionError("expected Ok, got $p")
    private fun err(p: Parsed<*>) = assertTrue("expected Err, got $p", p is Parsed.Err)

    @Test fun calibrationAcceptsRangeInclusive() {
        assertEquals(0.5, ok(SettingsInput.calibration("0.5")), 0.0)
        assertEquals(1.5, ok(SettingsInput.calibration("1.5")), 0.0)
        assertEquals(1.05, ok(SettingsInput.calibration(" 1.05 ")), 1e-12)
    }

    @Test fun calibrationAcceptsCommaDecimal() {
        assertEquals(0.95, ok(SettingsInput.calibration("0,95")), 1e-12)
    }

    @Test fun calibrationRejectsOutOfRangeAndJunk() {
        err(SettingsInput.calibration("0.49"))
        err(SettingsInput.calibration("1.51"))
        err(SettingsInput.calibration(""))
        err(SettingsInput.calibration("abc"))
        err(SettingsInput.calibration("NaN"))
        err(SettingsInput.calibration("Infinity"))
    }

    @Test fun calibrationErrorNamesTheRange() {
        val e = SettingsInput.calibration("2") as Parsed.Err
        assertTrue(e.message, e.message.contains("0.5") && e.message.contains("1.5"))
    }

    @Test fun redlineRange() {
        assertEquals(3000, ok(SettingsInput.redline("3000")))
        assertEquals(8000, ok(SettingsInput.redline("8000")))
        assertEquals(6200, ok(SettingsInput.redline(" 6200 ")))
        err(SettingsInput.redline("2999"))
        err(SettingsInput.redline("8001"))
        err(SettingsInput.redline("6200.5"))
        err(SettingsInput.redline(""))
    }

    @Test fun coolantRange() {
        assertEquals(90, ok(SettingsInput.coolant("90")))
        assertEquals(130, ok(SettingsInput.coolant("130")))
        err(SettingsInput.coolant("89"))
        err(SettingsInput.coolant("131"))
        err(SettingsInput.coolant("-5"))
    }

    @Test fun displacementChoices() {
        assertEquals(listOf(1.396, 1.591), SettingsInput.DISPLACEMENTS)
        assertEquals(0, SettingsInput.displacementIndex(1.396))
        assertEquals(1, SettingsInput.displacementIndex(1.591))
        assertEquals(1, SettingsInput.displacementIndex(1.6)) // nearest
        assertEquals(0, SettingsInput.displacementIndex(1.4))
    }

    @Test fun formatCalibration() {
        assertEquals("1.0", SettingsInput.formatCalibration(1.0))
        assertEquals("0.95", SettingsInput.formatCalibration(0.95))
        assertEquals("1.234", SettingsInput.formatCalibration(1.2344))
    }
}
