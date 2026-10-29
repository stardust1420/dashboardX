package com.karan.dashboardx

import org.junit.Assert.assertEquals
import org.junit.Test

class DashRuntimeTest {
    @Test
    fun chooseSource_demoWins() {
        assertEquals(SourceChoice.DEMO, chooseSource(true, null, false))
    }

    @Test
    fun chooseSource_needsSetupWithoutAdapter() {
        assertEquals(SourceChoice.NEEDS_SETUP, chooseSource(false, null, true))
    }

    @Test
    fun chooseSource_needsSetupWhenBluetoothUnusable() {
        assertEquals(SourceChoice.NEEDS_SETUP, chooseSource(false, "AA:BB", false))
    }

    @Test
    fun chooseSource_obd() {
        assertEquals(SourceChoice.OBD, chooseSource(false, "AA:BB", true))
    }

    @Test
    fun chooseSource_blankAddressNeedsSetup() {
        assertEquals(SourceChoice.NEEDS_SETUP, chooseSource(false, "  ", true))
    }
}
