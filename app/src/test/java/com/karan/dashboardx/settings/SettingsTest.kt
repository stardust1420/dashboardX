package com.karan.dashboardx.settings

import com.karan.dashboardx.data.DriveMode
import com.karan.dashboardx.obd.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun driveMode_defaultsToComfortAndPersists() {
        val prefs = FakePrefs()
        assertEquals(DriveMode.COMFORT, Settings(prefs).driveMode)
        Settings(prefs).driveMode = DriveMode.SPORT_PLUS
        assertEquals(DriveMode.SPORT_PLUS, Settings(prefs).driveMode)
    }
}
