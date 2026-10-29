package com.karan.dashboardx.phone

import android.Manifest

/** One row in the adapter picker. [name] may be null (BLE devices often advertise no name). */
data class DeviceEntry(val name: String?, val address: String, val isBle: Boolean) {
    val transport: String get() = if (isBle) "BLE" else "Classic"
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "(no name)"
    val label: String get() = "$displayName\n$address  ·  $transport"
}

/** Pure helpers for the adapter picker (no android.* calls; permission names are constants). */
object AdapterDevices {
    /**
     * Adds [e] to [list] unless an entry with the same address (case-insensitive) and transport is
     * already there. A later sighting fills in a missing name. Order is first-seen; Classic and BLE
     * entries for the same address are kept apart because they connect differently.
     */
    fun merge(list: List<DeviceEntry>, e: DeviceEntry): List<DeviceEntry> {
        val key = e.address.uppercase()
        val i = list.indexOfFirst { it.isBle == e.isBle && it.address.uppercase() == key }
        if (i < 0) return list + e
        val old = list[i]
        if (!old.name.isNullOrBlank() || e.name.isNullOrBlank()) return list
        return list.toMutableList().also { it[i] = old.copy(name = e.name) }
    }

    fun mergeAll(list: List<DeviceEntry>, more: Iterable<DeviceEntry>): List<DeviceEntry> =
        more.fold(list) { acc, e -> merge(acc, e) }

    /** Permission needed to list bonded devices and connect (null: none at runtime on API <= 30). */
    fun connectPermission(sdk: Int): String? = if (sdk >= 31) Manifest.permission.BLUETOOTH_CONNECT else null

    /** Permission needed for the BLE scan. */
    fun scanPermission(sdk: Int): String =
        if (sdk >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

    /** Every runtime permission the picker wants on [sdk], in request order. */
    fun wanted(sdk: Int): List<String> = listOfNotNull(connectPermission(sdk), scanPermission(sdk))

    /** One-line summary of the saved adapter for the settings row. */
    fun savedSummary(name: String?, address: String?, isBle: Boolean): String =
        if (address.isNullOrBlank()) "No adapter chosen"
        else DeviceEntry(name, address, isBle).let { "${it.displayName}  ·  $address  ·  ${it.transport}" }
}
