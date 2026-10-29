package com.karan.dashboardx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.karan.dashboardx.alerts.Warning
import com.karan.dashboardx.data.CarState
import com.karan.dashboardx.data.ConnectionStatus
import com.karan.dashboardx.data.DataSource
import com.karan.dashboardx.data.DemoSource
import com.karan.dashboardx.obd.DiagLog
import com.karan.dashboardx.obd.ObdSource
import com.karan.dashboardx.obd.Transports
import com.karan.dashboardx.render.FrameComposer
import com.karan.dashboardx.render.FrameModel
import com.karan.dashboardx.settings.DEFAULT_COOLANT_WARN_C
import com.karan.dashboardx.settings.DEFAULT_REDLINE_RPM
import com.karan.dashboardx.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SourceChoice { DEMO, OBD, NEEDS_SETUP }

/**
 * Demo mode always wins. Otherwise OBD needs a saved adapter and (API 31+) BLUETOOTH_CONNECT
 * ([connectPermission] is true below API 31). Bluetooth being off is deliberately not an input:
 * the ObdSource starts anyway, shows RECONNECTING and connects once Bluetooth comes on.
 */
fun chooseSource(demoMode: Boolean, adapterAddress: String?, connectPermission: Boolean): SourceChoice = when {
    demoMode -> SourceChoice.DEMO
    adapterAddress.isNullOrBlank() || !connectPermission -> SourceChoice.NEEDS_SETUP
    else -> SourceChoice.OBD
}

/**
 * Process-wide hub: owns the data source (demo or OBD) that the phone and car screens observe.
 * Ref-counted: the source starts on the first [acquire] and stops on the last [release].
 * [acquire], [release], [restart], [reloadSettings] and [init] are main-thread only; [frame] may be called from any
 * render thread.
 */
object DashRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var appCtx: Context? = null
    private var _settings: Settings? = null
    private var refs = 0
    private var source: DataSource? = null
    private var forwarders: List<Job> = emptyList()
    private var generation = 0L // guarded by lock; bumped on every start/stop

    private val _state = MutableStateFlow(CarState())
    private val _status = MutableStateFlow(ConnectionStatus.IDLE_NEEDS_SETUP)
    val state: StateFlow<CarState> = _state.asStateFlow()
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    val diag = DiagLog()

    val settings: Settings
        get() = _settings ?: error("DashRuntime.init/acquire(ctx) must be called first")

    /** Called from [frame] for each warning that should chime (Task 11 plugs the sound in). */
    @Volatile var onChime: ((Warning) -> Unit)? = null

    @Volatile private var redline = DEFAULT_REDLINE_RPM
    @Volatile private var coolantWarn = DEFAULT_COOLANT_WARN_C
    private val composer = FrameComposer() // guarded by itself

    /** Creates [settings] without starting anything. Safe to call repeatedly. */
    fun init(ctx: Context) {
        if (_settings != null) return
        val app = ctx.applicationContext
        appCtx = app
        _settings = Settings(app.getSharedPreferences("dashboardx", Context.MODE_PRIVATE))
        loadThresholds()
    }

    fun acquire(ctx: Context) {
        init(ctx)
        refs++
        if (refs == 1) startSource()
    }

    fun release() {
        if (refs == 0) {
            diag.add("runtime: release without acquire ignored")
            return
        }
        refs--
        if (refs == 0) stopSource()
    }

    /**
     * Re-reads the settings the frame builder caches (redline, coolant warning) without touching
     * the data source, so the OBD link and the trip average survive. Calibration and displacement
     * are read live by ObdSource and volume by the sound controller, so they need nothing here.
     */
    fun reloadSettings(ctx: Context) {
        init(ctx)
        loadThresholds()
    }

    /**
     * Stops and restarts the data source (new link, trip average reset). Only for changes that
     * pick a different source: the adapter choice and the Demo switch.
     */
    fun restart(ctx: Context) {
        init(ctx)
        if (refs > 0) {
            stopSource()
            startSource()
        } else {
            loadThresholds()
        }
    }

    /** Builds the next frame; advances the smoothers by the time since the previous call. */
    fun frame(nowMs: Long): FrameModel {
        val f: FrameModel
        val chime: Set<Warning>
        synchronized(composer) {
            f = composer.compose(_state.value, _status.value, redline, coolantWarn, nowMs)
            chime = composer.chime
        }
        if (chime.isNotEmpty()) {
            val cb = onChime
            if (cb != null) for (w in chime) cb(w)
        }
        return f
    }

    private fun loadThresholds() {
        val s = settings
        redline = s.redlineRpm
        coolantWarn = s.coolantWarnC
    }

    private fun startSource() {
        val ctx = appCtx ?: return
        val s = settings
        loadThresholds()
        val addr = s.adapterAddress
        val choice = chooseSource(s.demoMode, addr, connectPermission(ctx))
        diag.add("runtime: source $choice")
        val src: DataSource? = when (choice) {
            SourceChoice.DEMO -> DemoSource()
            SourceChoice.OBD -> addr?.let { a ->
                val ble = s.adapterIsBle
                ObdSource({ Transports.create(ctx, a, ble) }, s, diag)
            }
            SourceChoice.NEEDS_SETUP -> null
        }
        src?.start(scope) // sets DEMO / CONNECTING synchronously, published below without a gap
        val gen: Long
        synchronized(lock) {
            gen = ++generation
            source = src
            _state.value = src?.state?.value ?: CarState()
            _status.value = src?.status?.value ?: ConnectionStatus.IDLE_NEEDS_SETUP
        }
        if (src == null) return
        forwarders = listOf(
            scope.launch { src.state.collect { v -> synchronized(lock) { if (generation == gen) _state.value = v } } },
            scope.launch { src.status.collect { v -> synchronized(lock) { if (generation == gen) _status.value = v } } },
        )
    }

    private fun stopSource() {
        val src: DataSource?
        synchronized(lock) {
            generation++ // late forwarder writes from the old source are dropped from here on
            src = source
            source = null
            _state.value = CarState()
            _status.value = ConnectionStatus.IDLE_NEEDS_SETUP
        }
        forwarders.forEach { it.cancel() }
        forwarders = emptyList()
        src?.stop()
    }

    /** BLUETOOTH_CONNECT granted (always true below API 31, where it does not exist). */
    private fun connectPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
