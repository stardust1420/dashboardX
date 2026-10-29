package com.karan.dashboardx.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class AdapterDevicesTest {
    private val a = "AA:BB:CC:DD:EE:01"

    @Test fun mergeAddsNewEntries() {
        val l = AdapterDevices.mergeAll(emptyList(), listOf(
            DeviceEntry("OBDII", a, false),
            DeviceEntry("Other", "AA:BB:CC:DD:EE:02", true),
        ))
        assertEquals(2, l.size)
    }

    @Test fun sameAddressAndTransportIsDeduped() {
        val l = AdapterDevices.mergeAll(emptyList(), listOf(
            DeviceEntry("V-LINK", a, true),
            DeviceEntry("V-LINK", a, true),
            DeviceEntry(null, a.lowercase(), true),
        ))
        assertEquals(listOf(DeviceEntry("V-LINK", a, true)), l)
    }

    @Test fun sameAddressDifferentTransportKeptApart() {
        val l = AdapterDevices.mergeAll(emptyList(), listOf(DeviceEntry("X", a, false), DeviceEntry("X", a, true)))
        assertEquals(listOf("Classic", "BLE"), l.map { it.transport })
    }

    @Test fun laterSightingFillsMissingNameButNeverOverwrites() {
        var l = AdapterDevices.merge(emptyList(), DeviceEntry(null, a, true))
        l = AdapterDevices.merge(l, DeviceEntry("IOS-Vlink", a, true))
        assertEquals("IOS-Vlink", l.single().name)
        l = AdapterDevices.merge(l, DeviceEntry("Renamed", a, true))
        assertEquals("IOS-Vlink", l.single().name)
    }

    @Test fun orderIsFirstSeen() {
        val l = AdapterDevices.mergeAll(emptyList(), listOf(
            DeviceEntry("c1", "00:00:00:00:00:01", false),
            DeviceEntry("b1", "00:00:00:00:00:02", true),
            DeviceEntry("c1", "00:00:00:00:00:01", false),
            DeviceEntry("b2", "00:00:00:00:00:03", true),
        ))
        assertEquals(listOf("c1", "b1", "b2"), l.map { it.name })
    }

    @Test fun labels() {
        assertEquals("OBDII\n$a  ·  Classic", DeviceEntry("OBDII", a, false).label)
        assertEquals("(no name)\n$a  ·  BLE", DeviceEntry(" ", a, true).label)
    }

    @Test fun permissionsPerSdk() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), AdapterDevices.wanted(29))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), AdapterDevices.wanted(30))
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"),
            AdapterDevices.wanted(31),
        )
        assertEquals(null, AdapterDevices.connectPermission(30))
    }

    @Test fun savedSummary() {
        assertEquals("No adapter chosen", AdapterDevices.savedSummary("x", null, false))
        assertEquals("No adapter chosen", AdapterDevices.savedSummary(null, "", true))
        assertEquals("OBDII  ·  $a  ·  BLE", AdapterDevices.savedSummary("OBDII", a, true))
        assertEquals("(no name)  ·  $a  ·  Classic", AdapterDevices.savedSummary(null, a, false))
    }
}
