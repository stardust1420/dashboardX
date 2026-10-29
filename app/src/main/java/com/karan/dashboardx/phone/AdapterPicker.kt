package com.karan.dashboardx.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.karan.dashboardx.DashRuntime
import com.karan.dashboardx.data.ConnectionStatus

/**
 * Chooses the OBD adapter: requests the runtime Bluetooth permissions, then shows one dialog with
 * bonded devices ("Classic") plus an 8 s BLE scan ("BLE"). Picking one saves it, turns demo mode
 * off and restarts the runtime.
 *
 * The host activity must forward [onRequestPermissionsResult] and [onActivityResult], and call
 * [dispose] from onDestroy. Main thread only.
 */
@SuppressLint("MissingPermission") // every Bluetooth call is behind a permission check or a catch
class AdapterPicker(private val activity: Activity, private val onPicked: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val sdk = Build.VERSION.SDK_INT
    private var dialog: AlertDialog? = null

    private var entries: List<DeviceEntry> = emptyList()
    private var listAdapter: ArrayAdapter<DeviceEntry>? = null
    private var statusView: TextView? = null

    private var scanning = false
    private var scanCallback: ScanCallback? = null
    private var scanAdapter: BluetoothAdapter? = null
    /** Worked out once per scan and appended to every scan status message ("" when none). */
    private var scanNote = ""
    private val stopRunnable = Runnable { finishScan() }

    private val btAdapter: BluetoothAdapter?
        get() = activity.getSystemService(BluetoothManager::class.java)?.adapter

    /** Entry point (the Choose button). */
    fun start() {
        if (btAdapter == null) {
            info("No Bluetooth", "This phone has no Bluetooth adapter, so no OBD adapter can be used.")
            return
        }
        val missing = AdapterDevices.wanted(sdk).filterNot(::granted)
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        proceed()
    }

    fun onRequestPermissionsResult(requestCode: Int, results: IntArray): Boolean {
        if (requestCode != REQ_PERMS) return false
        if (results.any { it == PackageManager.PERMISSION_GRANTED }) {
            DashRuntime.diag.add("settings: Bluetooth permission granted")
            // Only a source waiting in setup (permission missing) needs a restart; a running
            // source keeps its link and trip average.
            if (DashRuntime.status.value == ConnectionStatus.IDLE_NEEDS_SETUP) DashRuntime.restart(activity)
        }
        proceed()
        return true
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_ENABLE) return false
        // No restart: a running ObdSource retries on its own and connects once Bluetooth is on.
        if (resultCode == Activity.RESULT_OK) proceed()
        return true
    }

    /** Host paused: stop scanning but keep the dialog (and its results) open. */
    fun pause() {
        if (!scanning) return
        val n = entries.count { it.isBle }
        stopScan()
        statusView?.text = withNote("Scan stopped: $n BLE device(s) found. Tap Choose again to rescan.")
    }

    fun dispose() {
        stopScan()
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    private fun proceed() {
        if (activity.isFinishing || activity.isDestroyed) return
        val adapter = btAdapter ?: return
        val connect = AdapterDevices.connectPermission(sdk)
        if (connect != null && !granted(connect)) {
            explainConnectDenied()
            return
        }
        val enabled = try { adapter.isEnabled } catch (e: SecurityException) { false }
        if (!enabled) {
            askEnable()
            return
        }
        showPicker(adapter, granted(AdapterDevices.scanPermission(sdk)))
    }

    private fun explainConnectDenied() {
        show(
            builder()
                .setTitle("Nearby devices permission needed")
                .setMessage(
                    "DashboardX needs the Nearby devices permission to list your paired OBD adapter " +
                        "and connect to it. Without it the dashboard can only run in demo mode.\n\n" +
                        "Tap Choose again to be asked, or allow it in App settings > Permissions.",
                )
                .setPositiveButton("App settings") { _, _ -> openAppSettings() }
                .setNegativeButton("Close", null),
        )
    }

    private fun askEnable() {
        show(
            builder()
                .setTitle("Turn on Bluetooth")
                .setMessage("Bluetooth is off. Turn it on to choose your OBD adapter.")
                .setPositiveButton("Turn on Bluetooth") { _, _ ->
                    try {
                        activity.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)
                    } catch (e: SecurityException) {
                        explainConnectDenied()
                    } catch (e: ActivityNotFoundException) {
                        info("Turn on Bluetooth", "Turn Bluetooth on from the quick settings, then tap Choose again.")
                    }
                }
                .setNegativeButton("Cancel", null),
        )
    }

    private fun showPicker(adapter: BluetoothAdapter, canScan: Boolean) {
        val bonded = try {
            adapter.bondedDevices.orEmpty().map { d ->
                // A bonded LE-only device can only be reached over BLE; everything else is Classic.
                DeviceEntry(safeName(d), d.address, isBle = d.type == BluetoothDevice.DEVICE_TYPE_LE)
            }.sortedBy { it.displayName.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        entries = AdapterDevices.mergeAll(emptyList(), bonded)

        val ctx = activity
        val pad = dp(20)
        val status = TextView(ctx).apply {
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(pad, dp(8), pad, dp(8))
        }
        statusView = status
        val la = object : ArrayAdapter<DeviceEntry>(ctx, android.R.layout.simple_list_item_1, ArrayList(entries)) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(pad, dp(12), pad, dp(12))
                }
                tv.text = getItem(position)?.label
                return tv
            }
        }
        listAdapter = la
        val empty = TextView(ctx).apply {
            text = "No paired adapters yet. Pair a Classic adapter in Android's Bluetooth settings" +
                if (canScan) ", or wait for the BLE scan." else "."
            setTextColor(0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(pad, dp(16), pad, dp(16))
        }
        val list = ListView(ctx).apply {
            this.adapter = la
            setOnItemClickListener { _, _, pos, _ ->
                la.getItem(pos)?.let { pick(it) }
                dialog?.dismiss()
            }
        }
        val listBox = FrameLayout(ctx).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -2))
        }
        list.emptyView = empty
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(listBox, LinearLayout.LayoutParams(-1, dp(260)))
        }

        val d = builder()
            .setTitle("Choose OBD adapter")
            .setView(content)
            .setNeutralButton("Bluetooth settings") { _, _ -> openBluetoothSettings() }
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnDismissListener {
            stopScan()
            if (dialog === d) dialog = null
            statusView = null
            listAdapter = null
        }
        show(d)

        if (canScan) startScan(adapter) else status.text = scanDeniedText()
    }

    private fun scanDeniedText(): String =
        if (sdk >= 31) "BLE scan is off: the Nearby devices permission was denied. Showing paired devices only."
        else "BLE scan is off: the Location permission was denied (Android needs it to scan for BLE). " +
            "Showing paired devices only."

    private fun startScan(adapter: BluetoothAdapter) {
        scanNote = if (sdk <= 30 && !locationOn()) {
            "\nLocation is off, so Android may report no BLE devices. Turn it on and tap Choose again."
        } else {
            ""
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusView?.text = withNote("BLE scan unavailable right now. Showing paired devices only.")
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = found(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::found)
            override fun onScanFailed(errorCode: Int) {
                main.post {
                    scanning = false
                    main.removeCallbacks(stopRunnable)
                    statusView?.text = withNote("BLE scan failed (error $errorCode). Showing paired devices.")
                }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (e: Exception) { // SecurityException, or IllegalStateException if Bluetooth just went off
            statusView?.text = withNote("BLE scan could not start (${e.javaClass.simpleName}). Showing paired devices.")
            return
        }
        scanCallback = cb
        scanAdapter = adapter
        scanning = true
        main.postDelayed(stopRunnable, SCAN_MS)
        statusView?.text = withNote("Scanning for BLE adapters (8 s)…")
    }

    private fun found(r: ScanResult) {
        val dev = r.device ?: return
        val name = r.scanRecord?.deviceName ?: safeName(dev)
        val e = DeviceEntry(name, dev.address, isBle = true)
        main.post {
            if (!scanning) return@post
            val next = AdapterDevices.merge(entries, e)
            if (next != entries) {
                entries = next
                listAdapter?.let { it.clear(); it.addAll(next) }
            }
        }
    }

    private fun finishScan() {
        val n = entries.count { it.isBle }
        stopScan()
        statusView?.text = withNote("Scan finished: $n BLE device(s) found. Tap one to use it.")
    }

    private fun withNote(msg: String) = msg + scanNote

    private fun stopScan() {
        main.removeCallbacks(stopRunnable)
        val cb = scanCallback
        val a = scanAdapter
        scanning = false
        scanCallback = null
        scanAdapter = null
        if (cb != null && a != null) {
            try { a.bluetoothLeScanner?.stopScan(cb) } catch (e: Exception) { /* BT off or permission revoked */ }
        }
    }

    private fun pick(e: DeviceEntry) {
        val s = DashRuntime.settings
        s.adapterAddress = e.address
        s.adapterIsBle = e.isBle
        s.adapterName = e.name
        s.demoMode = false
        DashRuntime.diag.add("settings: adapter ${e.displayName} ${e.address} (${e.transport})")
        DashRuntime.restart(activity)
        onPicked()
    }

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (e: SecurityException) { null }

    private fun granted(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun locationOn(): Boolean =
        activity.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    private fun openAppSettings() = launch(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
    )

    private fun openBluetoothSettings() = launch(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))

    private fun launch(i: Intent) {
        try { activity.startActivity(i) } catch (e: ActivityNotFoundException) { /* nothing to open */ }
    }

    private fun info(title: String, msg: String) =
        show(builder().setTitle(title).setMessage(msg).setPositiveButton("OK", null))

    private fun builder() = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)

    private fun show(b: AlertDialog.Builder) = show(b.create())

    private fun show(d: AlertDialog) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog !== d) {
            dialog?.setOnDismissListener(null)
            dialog?.dismiss()
            stopScan()
        }
        dialog = d
        d.show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val REQ_PERMS = 4201
        const val REQ_ENABLE = 4202
        const val SCAN_MS = 8_000L
    }
}
