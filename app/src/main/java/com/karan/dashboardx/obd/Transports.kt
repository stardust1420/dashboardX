package com.karan.dashboardx.obd

import android.bluetooth.BluetoothManager
import android.content.Context

object Transports {
    fun create(ctx: Context, address: String, isBle: Boolean): ObdTransport {
        val app = ctx.applicationContext
        return if (isBle) {
            BleTransport(app, address)
        } else {
            val mgr = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ClassicTransport(mgr?.adapter, address)
        }
    }
}
