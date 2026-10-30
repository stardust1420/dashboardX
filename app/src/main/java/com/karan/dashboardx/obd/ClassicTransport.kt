package com.karan.dashboardx.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Bluetooth Classic SPP transport. Cancelling open()/send() closes the socket so blocked I/O unwinds. */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val adapter: BluetoothAdapter?,
    private val address: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ObdTransport {
    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override suspend fun open() {
        val bt = adapter ?: throw IOException("Bluetooth is off (no adapter)")
        val on = try {
            bt.isEnabled
        } catch (e: SecurityException) {
            throw IOException("Bluetooth permission missing")
        }
        if (!on) throw IOException("Bluetooth is off")
        val device = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("bad address $address")
        }
        coroutineScope {
            val worker = async(Dispatchers.IO) {
                runCatching { bt.cancelDiscovery() } // needs BLUETOOTH_SCAN on some builds; best effort
                var s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                try {
                    s.connect()
                } catch (e: IOException) {
                    runCatching { s.close() }
                    ensureActive()
                    s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    socket = s
                    s.connect()
                }
                input = s.inputStream
                output = s.outputStream
            }
            try {
                worker.await()
            } catch (e: CancellationException) {
                close()
                throw e
            } catch (e: IOException) {
                close()
                throw e
            }
        }
    }

    override suspend fun send(cmd: String, timeoutMs: Long): String {
        val inp = input ?: throw IOException("not open")
        val out = output ?: throw IOException("not open")
        try {
            // discard stale bytes
            while (inp.available() > 0) inp.skip(inp.available().toLong())
            out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()
            val sb = StringBuilder()
            val buf = ByteArray(256)
            val deadline = clock() + timeoutMs
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = inp.available()
                if (n > 0) {
                    val r = inp.read(buf, 0, minOf(n, buf.size))
                    if (r < 0) throw IOException("stream ended")
                    for (i in 0 until r) {
                        val c = (buf[i].toInt() and 0xFF).toChar()
                        if (c == '>') return sb.toString()
                        sb.append(c)
                    }
                } else {
                    if (clock() >= deadline) throw IOException("timeout waiting for '>' after '$cmd'")
                    delay(5)
                }
            }
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
