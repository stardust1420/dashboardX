package com.karan.dashboardx

import org.junit.Assert.assertEquals
import org.junit.Test

class DashRuntimeTest {
    @Test
    fun chooseSource_demoWins() {
        assertEquals(SourceChoice.DEMO, chooseSource(demoMode = true, adapterAddress = null, connectPermission = false))
    }

    @Test
    fun chooseSource_needsSetupWithoutAdapter() {
        assertEquals(SourceChoice.NEEDS_SETUP, chooseSource(demoMode = false, adapterAddress = null, connectPermission = true))
    }

    @Test
    fun chooseSource_blankAddressNeedsSetup() {
        assertEquals(SourceChoice.NEEDS_SETUP, chooseSource(demoMode = false, adapterAddress = "  ", connectPermission = true))
    }

    @Test
    fun chooseSource_needsSetupWithoutConnectPermission() {
        assertEquals(SourceChoice.NEEDS_SETUP, chooseSource(demoMode = false, adapterAddress = "AA:BB", connectPermission = false))
    }

    @Test
    fun chooseSource_obd() {
        assertEquals(SourceChoice.OBD, chooseSource(demoMode = false, adapterAddress = "AA:BB", connectPermission = true))
    }
}
