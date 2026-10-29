package com.karan.dashboardx.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/** BLE (GATT) transport for UART-style ELM327 clones. Cancellation disconnects and closes the GATT. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(
    private val ctx: Context,
    private val address: String,
) : ObdTransport {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var chunkSize = 20
    @Volatile private var disconnected: IOException? = null

    private val rx = Channel<ByteArray>(Channel.UNLIMITED)

    // One outstanding GATT operation at a time; each is completed by its callback.
    private enum class Kind { CONNECT, MTU, DISCOVER, DESCRIPTOR_WRITE, CHAR_WRITE }

    private class Pending(val kind: Kind, val result: CompletableDeferred<Int> = CompletableDeferred())

    @Volatile private var pending: Pending? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                complete(Kind.CONNECT, status)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = IOException("BLE disconnected (status $status)")
                rx.close(disconnected)
                pending?.result?.completeExceptionally(disconnected!!)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) chunkSize = maxOf(20, mtu - 3)
            complete(Kind.MTU, status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(Kind.DISCOVER, status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(Kind.DESCRIPTOR_WRITE, status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            complete(Kind.CHAR_WRITE, status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { rx.trySend(it.copyOf()) }
        }

        /** Completes the pending op only if it is the kind this callback belongs to; stray/late callbacks are dropped. */
        private fun complete(kind: Kind, status: Int) {
            val p = pending ?: return
            if (p.kind == kind) p.result.complete(status)
        }
    }

    /** Starts [start], then waits for its callback. Throws IOException on failure/timeout; closes the GATT on cancellation. */
    private suspend fun op(kind: Kind, what: String, timeoutMs: Long, start: () -> Boolean): Int {
        val d = Pending(kind)
        pending = d
        try {
            if (!start()) throw IOException("$what: could not start")
            return withTimeout(timeoutMs) { d.result.await() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("$what: timeout")
        } catch (e: kotlinx.coroutines.CancellationException) {
            close()
            throw e
        } finally {
            if (pending === d) pending = null
        }
    }

    override suspend fun open() {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter ?: throw IOException("Bluetooth is off (no adapter)")
        val on = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            throw IOException("Bluetooth permission missing")
        }
        if (!on) throw IOException("Bluetooth is off")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad address $address")
        }
        try {
            var g: BluetoothGatt? = null
            val st = op(Kind.CONNECT, "connect", 10_000) {
                g = device.connectGatt(ctx, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                gatt = g
                g != null
            }
            if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("connect failed ($st)")
            val gg = g!!

            // MTU is best effort; if it fails we chunk to 20 bytes.
            try {
                op(Kind.MTU, "mtu", 2_000) { gg.requestMtu(247) }
            } catch (e: IOException) {
                if (disconnected != null) throw e
            }

            val sd = op(Kind.DISCOVER, "discover", 10_000) { gg.discoverServices() }
            if (sd != BluetoothGatt.GATT_SUCCESS) throw IOException("service discovery failed ($sd)")

            val (notify, write) = pick(gg) ?: throw IOException("no UART characteristics found")
            writeChar = write
            if (!gg.setCharacteristicNotification(notify, true)) throw IOException("enable notify failed")
            val cccd = notify.getDescriptor(CCCD) ?: throw IOException("no CCCD")
            val useIndicate = notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
            cccd.value = if (useIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ds = op(Kind.DESCRIPTOR_WRITE, "cccd", 5_000) { gg.writeDescriptor(cccd) }
            if (ds != BluetoothGatt.GATT_SUCCESS) throw IOException("CCCD write failed ($ds)")
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun pick(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        fun BluetoothGattCharacteristic.canNotify() =
            properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        fun BluetoothGattCharacteristic.canWrite() =
            properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        g.getService(FFE0)?.getCharacteristic(FFE1)?.let { c ->
            if (c.canNotify() && c.canWrite()) return c to c
        }
        g.getService(FFF0)?.let { s ->
            val n = s.getCharacteristic(FFF1)
            val w = s.getCharacteristic(FFF2)
            if (n != null && w != null && n.canNotify() && w.canWrite()) return n to w
        }
        for (s in g.services) {
            val n = s.characteristics.firstOrNull { it.canNotify() }
            val w = s.characteristics.firstOrNull { it.canWrite() }
            if (n != null && w != null) return n to w
        }
        return null
    }

    override suspend fun send(cmd: String, timeoutMs: Long): String {
        val g = gatt ?: throw IOException("not open")
        val w = writeChar ?: throw IOException("not open")
        disconnected?.let { throw it }
        try {
            while (rx.tryReceive().isSuccess) { /* discard stale */ }
            val bytes = (cmd + "\r").toByteArray(Charsets.US_ASCII)
            val withResponse = w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            w.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            var off = 0
            while (off < bytes.size) {
                val chunk = bytes.copyOfRange(off, minOf(bytes.size, off + chunkSize))
                off += chunk.size
                if (withResponse) {
                    val st = op(Kind.CHAR_WRITE, "write", 2_000) {
                        w.value = chunk
                        g.writeCharacteristic(w)
                    }
                    if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("write failed ($st)")
                } else {
                    w.value = chunk
                    if (!g.writeCharacteristic(w)) {
                        // stack busy with the previous chunk; give it a moment once
                        delay(20)
                        if (!g.writeCharacteristic(w)) throw IOException("write failed")
                    }
                    if (off < bytes.size) delay(20)
                }
            }
            val sb = StringBuilder()
            try {
                withTimeout(timeoutMs) {
                    while (true) {
                        val part = rx.receiveCatching().getOrNull()
                            ?: throw (disconnected ?: IOException("BLE stream ended"))
                        for (b in part) {
                            val c = (b.toInt() and 0xFF).toChar()
                            if (c == '>') return@withTimeout
                            sb.append(c)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw IOException("timeout waiting for '>' after '$cmd'")
            }
            return sb.toString()
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            close()
            throw e
        }
    }

    override fun close() {
        val g = gatt
        gatt = null
        writeChar = null
        rx.close()
        pending?.result?.completeExceptionally(IOException("closed"))
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val FFE0: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val FFE1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val FFF0: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val FFF1: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val FFF2: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    }
}
