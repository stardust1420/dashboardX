# DashboardX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app that reads a Bluetooth ELM327 OBD adapter and draws one of three game-style dashboards full screen in Android Auto (and on the phone), with an RPM-following synthetic straight-six sound and overheat/redline warnings.

**Architecture:** A single `app` module. Pure Kotlin logic (PID decoding, fuel maths, smoothing, warnings, poll scheduling, synth maths) is unit-tested on the JVM. One process-wide `DashRuntime` owns the data source (demo or OBD), and both the car screen and the phone screen observe it. The car side registers as a navigation app so `NavigationTemplate` + `SurfaceCallback` give it a raw Surface. Three `GaugeRenderer`s draw with `android.graphics.Canvas` into a logical 800×480 space, scaled to the visible area.

**Tech Stack:** Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7 (wrapper), JDK 17, compileSdk/targetSdk 34, minSdk 29, `androidx.car.app:app:1.4.0`, kotlinx-coroutines 1.8.1, JUnit 4.13.2, plain Android Views (no Compose).

**Spec:** `docs/superpowers/specs/2026-10-29-obd-android-auto-dash-design.md`. Read it first. The look of each style is defined by `.superpowers/mockups/styles.html`; port its drawing code faithfully.

## Global Constraints

- Package / applicationId: `com.karan.dashboardx`. App label: `DashboardX`.
- The car app service declares category `androidx.car.app.category.NAVIGATION` and uses `NavigationTemplate` + `SurfaceCallback`. Permissions: `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`. Host validator: `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (personal sideload).
- Logical drawing space is 800×480, scaled uniformly and centred inside the host's visible area.
- Defaults: redline **6200** RPM, tach max **7000**, coolant warning **> 105 °C**, displacement **1.591 L**, VE **0.85**, fuel calibration **1.0**, stale after **3000 ms**.
- Fuel: `L/h = MAF(g/s) / 14.7 / 740 × 3600 × calibration`.
- Unsupported PID → display "N/A". Stale or unknown value → display "--". Never invent values.
- The engine sound is **off at every app start**. It mixes over music using `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`.
- No trip persistence, no DTCs, no background service.
- Bluetooth: Classic SPP UUID `00001101-0000-1000-8000-00805F9B34FB`, plus BLE (services FFE0/FFF0, or any service with one notify and one write characteristic).
- ELM327 init sequence, in order: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0`, then `0100`.
- Toolchain lives in `.tools/` (gitignored). Every shell step first runs `source env.sh`.

## Review Focus

1. **Adapter answers `NO DATA`, `?`, `STOPPED`, `SEARCHING...`, a partial line or garbage:** that PID stays null for the cycle, and polling continues with no crash. Test in Task 3 (`decode_ignoresErrorReplies`).
2. **Link drops mid-drive** (ignition off, phone walks away; the transport throws `IOException`): values go stale within 3 s, status goes to RECONNECTING, and reconnect retries with backoff. Test in Task 4 (`source_reconnectsAfterIoError`).
3. **Car off / ECU asleep at init** (`UNABLE TO CONNECT` after `0100`): status stays CONNECTING and retries, with no crash and no fake data. Test in Task 4 (`session_unableToConnect_throwsRetryable`).
4. **Extreme or missing values** (rpm 9000, coolant −40 °C, null): gauges clamp to range and text shows "--"/"N/A". Test in Task 7 (`fmt_*`, `clamp01`).
5. **No adapter chosen, Bluetooth off or permission denied:** the car screen shows "Set up DashboardX on your phone" in a demo-free idle view, rather than crashing or silently running demo. Test in Task 8 (`chooseSource_*`).

---

### Task 1: Toolchain and empty app that builds

**Files:**
- Create: `env.sh`, `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/karan/dashboardx/phone/MainActivity.kt`, `app/src/test/java/com/karan/dashboardx/SmokeTest.kt`, gradle wrapper files

**Interfaces:**
- Produces: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` work after `source env.sh`.

- [ ] **Step 1:** `git init` in `/Users/karansinghasane/Developer/dashboardx`. Write `.gitignore` (`.tools/`, `.gradle/`, `build/`, `local.properties`, `.superpowers/brainstorm/`, `*.iml`, `.idea/`).
- [ ] **Step 2:** Download Temurin JDK 17 for macOS aarch64 (`https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse`) into `.tools/` and extract. Download `https://services.gradle.org/distributions/gradle-8.7-bin.zip` into `.tools/` and unzip. Write `env.sh` exporting `JAVA_HOME=<repo>/.tools/<jdk dir>/Contents/Home`, `ANDROID_HOME=$HOME/Library/Android/sdk`, and prepending `$JAVA_HOME/bin` and `$ANDROID_HOME/platform-tools` to PATH. Write `local.properties` with `sdk.dir`.
- [ ] **Step 3:** Write the Gradle files with the versions in the Tech Stack. Add `androidx.car.app:app`, coroutines-android, `androidx.core:core-ktx`, `androidx.appcompat:appcompat`, junit, and `kotlinx-coroutines-test`. Set `testOptions.unitTests.isReturnDefaultValues = true`. Run `.tools/gradle-8.7/bin/gradle wrapper --gradle-version 8.7`.
- [ ] **Step 4:** Minimal `MainActivity` (a TextView saying "DashboardX") registered as the launcher activity. `SmokeTest` with `assertEquals(4, 2 + 2)`.
- [ ] **Step 5:** Run `source env.sh && ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 6:** Commit: `chore: project skeleton and toolchain`.

### Task 2: Car state model, settings, demo data

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/data/CarState.kt`, `data/DataSource.kt`, `data/DemoSource.kt`, `settings/Settings.kt`
- Test: `app/src/test/java/com/karan/dashboardx/data/CarStateTest.kt`, `data/DemoSourceTest.kt`

**Interfaces:**
- Produces:
  - `data class Reading(val value: Double, val atMs: Long)`
  - `enum class Pid { RPM, SPEED, COOLANT, IAT, THROTTLE, MAF, MAP, LOAD, OIL }`
  - `data class CarState(val readings: Map<Pid, Reading> = emptyMap(), val unsupported: Set<Pid> = emptySet(), val instantKml: Double? = null, val tripKml: Double? = null)` with `fun value(pid: Pid, nowMs: Long, staleMs: Long = 3000): Double?` (null if missing or stale) and `fun isUnsupported(pid: Pid): Boolean`
  - `enum class ConnectionStatus { IDLE_NEEDS_SETUP, DEMO, CONNECTING, CONNECTED, RECONNECTING }`
  - `interface DataSource { val state: StateFlow<CarState>; val status: StateFlow<ConnectionStatus>; fun start(scope: CoroutineScope); fun stop() }`
  - `class DemoSource(private val clock: () -> Long = System::currentTimeMillis) : DataSource`, plus `fun stepAt(tSec: Double): CarState` (pure, used by tests)
  - `enum class Style { RACING, HUD, ARCADE; fun next(): Style }`
  - `class Settings(prefs: SharedPreferences)` with properties: `style: Style`, `adapterAddress: String?`, `adapterIsBle: Boolean`, `demoMode: Boolean`, `soundVolume: Float` (0..1, default 0.7), `fuelCalibration: Double`, `displacementL: Double`, `redlineRpm: Int`, `coolantWarnC: Int`. Defaults come from Global Constraints.

- [ ] **Step 1: Failing tests**
  - `value_returnsNullWhenStale`: reading at t=0, `value(RPM, nowMs=3001)` is null; at `nowMs=2999` it is the value.
  - `style_nextCycles`: `RACING.next()==HUD`, `HUD.next()==ARCADE`, `ARCADE.next()==RACING`.
  - `demo_rpmStaysInRange`: for t in 0..60 s step 0.05, the RPM from `stepAt` is in 700..7000, throttle in 0..100, coolant in 85..95.
  - `demo_reachesRedlineDuringPull`: some t in 0..9 s gives RPM > 6200.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests 'com.karan.dashboardx.data.*'`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement. `DemoSource.stepAt` ports `step()` from the mockup: a 16 s cycle of a 9 s pull through 3 gears from 2600 toward 6500, then cruise at 2300, then idle at 850, with the same smoothing. Fill `instantKml` with the mockup formula. `start` emits at 20 Hz on the given scope with status `DEMO`.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit: `feat: car state model, settings, demo source`.

### Task 3: OBD response parsing and PID decoding

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/obd/PidCodec.kt`
- Test: `app/src/test/java/com/karan/dashboardx/obd/PidCodecTest.kt`

**Interfaces:**
- Consumes: `Pid` (Task 2)
- Produces:
  - `object PidCodec`
    - `val Pid.code: String` ("0C","0D","05","0F","11","10","0B","04","5C")
    - `fun cleanReply(raw: String): String`: strips `\r`, `\n`, spaces, `>`, `SEARCHING...` and uppercases
    - `fun decode(pid: Pid, raw: String): Double?`: null for error replies (`NODATA`, `?`, `STOPPED`, `ERROR`, `UNABLETOCONNECT`, `CANERROR`, `BUSINIT...`) or a missing/short `41<code>` frame. Uses the first `41<code>` frame found.
    - `fun parseSupported(raw: String, base: Int): Set<Int>`: from a `41 00/20/40` reply, the PID numbers that are supported (MSB = base+1)

- [ ] **Step 1: Failing tests** (exact values):
  - `decode_rpm`: `decode(RPM, "41 0C 1A F8\r\r>") == 1726.0`
  - `decode_rpmNoSpaces`: `decode(RPM, "410C1AF8") == 1726.0`
  - `decode_speed`: `decode(SPEED, "410D3C") == 60.0`
  - `decode_coolant`: `decode(COOLANT, "41057B") == 83.0`, and `"410500"` gives `-40.0`
  - `decode_throttle`: `decode(THROTTLE, "4111FF") == 100.0`
  - `decode_maf`: `decode(MAF, "411003E8") == 10.0`
  - `decode_map`: `decode(MAP, "410B32") == 50.0`
  - `decode_oil`: `decode(OIL, "415C82") == 90.0`
  - `decode_ignoresErrorReplies`: `NO DATA`, `?`, `STOPPED`, `SEARCHING...\rNO DATA`, `410C1A` (short), `7F0112` and `""` all give null
  - `decode_searchingThenData`: `"SEARCHING...\r410C1AF8"` gives 1726.0
  - `parseSupported_bitmask`: `parseSupported("4100BE3EB813", 0)` contains 0x01, 0x0C, 0x0D, 0x0F, 0x11 and 0x20, and does not contain 0x02 or 0x10
- [ ] **Step 2:** Run `--tests 'com.karan.dashboardx.obd.PidCodecTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement. Formulas: RPM `(256A+B)/4`; SPEED `A`; COOLANT/IAT/OIL `A−40`; THROTTLE/LOAD `A×100/255`; MAF `(256A+B)/100`; MAP `A`.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit: `feat: OBD reply parsing and PID decoding`.

### Task 4: ELM327 session, poll schedule, OBD data source

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/obd/ObdTransport.kt`, `obd/Elm327.kt`, `obd/PollSchedule.kt`, `obd/ObdSource.kt`, `obd/DiagLog.kt`
- Test: `app/src/test/java/com/karan/dashboardx/obd/Elm327Test.kt`, `obd/PollScheduleTest.kt`, `obd/ObdSourceTest.kt`, `obd/FakeTransport.kt`

**Interfaces:**
- Consumes: `PidCodec`, `Pid`, `CarState`, `DataSource`, `ConnectionStatus`, `Settings` (Tasks 2–3). Leave `instantKml`/`tripKml` null here; Task 6 wires them in.
- Produces:
  - `interface ObdTransport { suspend fun open(); suspend fun send(cmd: String): String /* reply up to '>' prompt, timeout 2000 ms */; fun close() }`
  - `class Elm327(private val t: ObdTransport, private val log: DiagLog)` with `suspend fun init(): Set<Pid>` (runs the init sequence, then `0100`, `0120`, `0140`, and returns the supported `Pid`s) and `suspend fun read(pid: Pid): Double?`
  - `class RetryableObdException(msg: String) : Exception(msg)`
  - `object PollSchedule { fun pidsFor(cycle: Int, supported: Set<Pid>): List<Pid> }`
  - `class ObdSource(private val transportFactory: () -> ObdTransport, private val settings: Settings, private val log: DiagLog, private val clock: () -> Long = System::currentTimeMillis, private val backoffMs: (attempt: Int) -> Long = { minOf(10_000L, 1000L shl it) }) : DataSource`
  - `class DiagLog(capacity: Int = 2000)` with `fun add(line: String)`, `fun lines(): List<String>` (thread-safe ring buffer, each line timestamped)

- [ ] **Step 1: Failing tests**
  - `FakeTransport(script: Map<String, String>)` returns scripted replies and records the commands sent. It can be told to throw `IOException` after N sends.
  - `session_initSendsSequence`: after `init()`, the first 7 sent commands are exactly `ATZ, ATE0, ATL0, ATS0, ATH0, ATSP0, 0100`.
  - `session_initReturnsSupported`: with `0100 → 4100BE3EB813`, `0120 → NO DATA`, `0140 → NO DATA`, the result contains RPM, SPEED, COOLANT, IAT, THROTTLE, MAP, LOAD, and not MAF or OIL.
  - `session_unableToConnect_throwsRetryable`: `0100 → UNABLE TO CONNECT` makes `init()` throw `RetryableObdException`.
  - `schedule_everyCycleHasRpmAndThrottle`: for cycles 0..19, the list contains RPM and THROTTLE.
  - `schedule_evenCyclesAddSpeedAndAirflow`: cycle 2 with MAF supported contains SPEED and MAF; with MAF unsupported but MAP supported it contains MAP.
  - `schedule_tempsEveryTenth`: cycle 10 contains COOLANT, IAT and OIL (when supported); cycle 11 contains none of them.
  - `schedule_filtersUnsupported`: OIL is never present when unsupported.
  - `source_publishesValues` (runTest + virtual clock): with a working fake, after a few cycles `state.value.value(RPM, now)` is 1726.0, the status is CONNECTED, and OIL is in `unsupported`.
  - `source_reconnectsAfterIoError`: the transport throws `IOException` after 20 sends. The status becomes RECONNECTING, a new transport is created from the factory, and the status returns to CONNECTED.
- [ ] **Step 2:** Run `--tests 'com.karan.dashboardx.obd.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. `ObdSource.start` launches one coroutine on `Dispatchers.IO`: open, init, then loop over `PollSchedule.pidsFor(cycle++)`, merging each non-null reading into `state` with the current time. On `IOException`/`RetryableObdException`, close, set RECONNECTING, delay `backoffMs(attempt)`, and retry. Every command and reply goes to `DiagLog`. The OIL decision is final for the session: it is unsupported if `015C` is missing from the bitmask.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit: `feat: ELM327 session, poll schedule, OBD source`.

### Task 5: Bluetooth Classic and BLE transports

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/obd/ClassicTransport.kt`, `obd/BleTransport.kt`, `obd/Transports.kt`
- Modify: `app/src/main/AndroidManifest.xml` (Bluetooth permissions)

**Interfaces:**
- Consumes: `ObdTransport` (Task 4)
- Produces: `object Transports { fun create(ctx: Context, address: String, isBle: Boolean): ObdTransport }`

- [ ] **Step 1:** `ClassicTransport` uses `createRfcommSocketToServiceRecord(SPP UUID)`, falling back to `createInsecureRfcommSocketToServiceRecord` if the connect fails. It cancels discovery before connecting. `send` writes `cmd + "\r"` and reads bytes until `>`, or throws `IOException` after a 2000 ms timeout.
- [ ] **Step 2:** `BleTransport` connects GATT and discovers services. It picks FFE0/FFE1 (notify+write), or FFF0 with FFF1 notify and FFF2 write, or else the first service that has both a notify and a write characteristic. It enables notifications (CCCD 0x2902) and requests MTU 247. `send` writes `cmd + "\r"` and collects notification bytes until `>`, with the same 2000 ms timeout. Disconnect gives `IOException`.
- [ ] **Step 3:** Manifest: `BLUETOOTH` and `BLUETOOTH_ADMIN` with `maxSdkVersion="30"`; `ACCESS_FINE_LOCATION` with `maxSdkVersion="30"`; `BLUETOOTH_CONNECT`; `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"`. Feature `android.hardware.bluetooth_le` with `required="false"`.
- [ ] **Step 4:** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL. These transports are verified for real only in the car (Task 13).
- [ ] **Step 5:** Commit: `feat: Bluetooth Classic and BLE transports`.

### Task 6: Fuel economy

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/calc/FuelEconomy.kt`
- Modify: `obd/ObdSource.kt` (compute `instantKml`/`tripKml` after each cycle)
- Test: `app/src/test/java/com/karan/dashboardx/calc/FuelEconomyTest.kt`

**Interfaces:**
- Produces:
  - `object FuelEconomy`
    - `fun lphFromMaf(mafGs: Double, calibration: Double = 1.0): Double`
    - `fun mafFromSpeedDensity(mapKpa: Double, rpm: Double, iatC: Double, displacementL: Double, ve: Double = 0.85): Double`: g/s = `MAP·1000 · (displacementL/1000) · ve · (rpm/120) / (287.05 · (iatC+273.15)) · 1000`
    - `fun instantKml(speedKmh: Double?, throttlePct: Double?, lph: Double?): Double?`: null if speed is null or < 3, or lph is null; `Double.POSITIVE_INFINITY` if throttle < 2 and speed > 10; otherwise `min(50.0, speed/lph)`
  - `class TripAverage { fun add(speedKmh: Double, lph: Double, dtMs: Long); fun kml(): Double? /* null until fuel ≥ 0.01 L */ }`

- [ ] **Step 1: Failing tests**
  - `lphFromMaf_10gs`: `lphFromMaf(10.0)` ≈ 3.3094 (±0.001); with calibration 1.1 it is ≈ 3.6404.
  - `speedDensity_example`: `mafFromSpeedDensity(50.0, 2000.0, 30.0, 1.591)` ≈ 12.95 (±0.05).
  - `instant_cruise`: `instantKml(60.0, 20.0, 3.3094)` ≈ 18.13 (±0.01).
  - `instant_stopped`: `instantKml(0.0, 0.0, 0.8)` is null.
  - `instant_fuelCut`: `instantKml(50.0, 0.0, 0.5)` is infinity.
  - `instant_capped`: `instantKml(100.0, 10.0, 0.1)` is 50.0.
  - `trip_average`: adding (60 km/h, 4 L/h, 3_600_000 ms) gives `kml()` ≈ 15.0; an empty trip gives null.
- [ ] **Step 2:** Run `--tests 'com.karan.dashboardx.calc.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. In `ObdSource`, after each cycle: use MAF if fresh, otherwise speed-density from MAP+RPM+IAT (IAT defaults to 25 °C if stale), apply calibration, then update `instantKml` and the `TripAverage` (dt = time since the last cycle, ignored if > 2 s).
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit: `feat: fuel economy calculation`.

### Task 7: Smoothing, viewport, formatting, warnings

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/render/Smoother.kt`, `render/Viewport.kt`, `render/Fmt.kt`, `alerts/Warnings.kt`
- Test: `app/src/test/java/com/karan/dashboardx/render/SmootherTest.kt`, `render/ViewportTest.kt`, `render/FmtTest.kt`, `alerts/WarningsTest.kt`

**Interfaces:**
- Produces:
  - `class Smoother(private val tauMs: Double = 80.0) { fun target(v: Double?); fun advance(dtMs: Long): Double? }`: exponential approach; the first non-null target snaps; a null target keeps the last value and is reported as null
  - `data class Viewport(val scale: Float, val dx: Float, val dy: Float) { companion object { fun fit(left: Int, top: Int, width: Int, height: Int): Viewport } }`: fits 800×480
  - `object Fmt { fun temp(v: Double?, unsupported: Boolean): String; fun int(v: Double?): String; fun kml(v: Double?): String; fun clamp01(v: Double, lo: Double, hi: Double): Double }`
  - `enum class Warning { OVERHEAT, REDLINE }`
  - `class Warnings(private val cooldownMs: Long = 5000) { fun evaluate(coolantC: Double?, rpm: Double?, redline: Int, coolantWarn: Int, nowMs: Long): WarningResult }` with `data class WarningResult(val active: Set<Warning>, val chime: Set<Warning>)`. The chime fires on the rising edge, at most once per cooldown per type.

- [ ] **Step 1: Failing tests**
  - `smoother_snapsFirstThenEases`: target 1000 then `advance(16)` gives 1000; then target 3000 and `advance(80)` gives ≈ 2264 (±5).
  - `viewport_wide`: `fit(0,0,1280,720)` gives scale 1.5, dx 40, dy 0. `viewport_offsetArea`: `fit(100,50,800,480)` gives scale 1, dx 100, dy 50.
  - `fmt_temp`: `temp(92.4,false)=="92°"`, `temp(null,false)=="--"`, `temp(null,true)=="N/A"`, `temp(-40.0,false)=="-40°"`.
  - `fmt_kml`: `kml(null)=="--"`, `kml(Double.POSITIVE_INFINITY)=="∞"`, `kml(14.23)=="14.2"`.
  - `clamp01`: `clamp01(9000.0, 0.0, 7000.0)==1.0` and `clamp01(-40.0, 40.0, 120.0)==0.0`.
  - `warn_overheatStrictlyAbove`: coolant 105 gives no OVERHEAT; 105.1 gives OVERHEAT active and chimed.
  - `warn_redline`: rpm 6201 with redline 6200 gives REDLINE.
  - `warn_chimeCooldown`: REDLINE at t=0 chimes; it drops and comes back at t=2000 and does not chime; it drops and comes back at t=6000 and chimes.
  - `warn_nullInputs`: null coolant and null rpm give an empty result.
- [ ] **Step 2:** Run `--tests 'com.karan.dashboardx.render.*' --tests 'com.karan.dashboardx.alerts.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit: `feat: smoothing, viewport, formatting, warnings`.

### Task 8: Runtime hub, renderer base, Racing style on the phone

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/DashRuntime.kt`, `render/GaugeRenderer.kt`, `render/FrameModel.kt`, `render/RacingRenderer.kt`, `render/Fonts.kt`, `phone/DashView.kt`; `app/src/main/res/font/orbitron.ttf`, `share_tech_mono.ttf`, `press_start_2p.ttf` (from github.com/google/fonts, OFL; add `OFL.txt` to `app/src/main/assets/licenses/`)
- Modify: `phone/MainActivity.kt` (full-screen `DashView`, landscape)
- Test: `app/src/test/java/com/karan/dashboardx/DashRuntimeTest.kt`

**Interfaces:**
- Consumes: Tasks 2–7.
- Produces:
  - `enum class SourceChoice { DEMO, OBD, NEEDS_SETUP }` and `fun chooseSource(demoMode: Boolean, adapterAddress: String?, bluetoothUsable: Boolean): SourceChoice` (pure, top level in `DashRuntime.kt`)
  - `object DashRuntime { fun acquire(ctx: Context); fun release(); val state: StateFlow<CarState>; val status: StateFlow<ConnectionStatus>; val settings: Settings; fun restart(ctx: Context); val diag: DiagLog }`. Ref-counted: the source starts on the first acquire and stops on the last release. `restart` is used after settings change. It also exposes `fun frame(nowMs: Long): FrameModel`.
  - `data class FrameModel(val rpm: Double?, val speed: Double?, val throttle: Double?, val coolant: Double?, val oil: Double?, val iat: Double?, val instantKml: Double?, val tripKml: Double?, val oilUnsupported: Boolean, val status: ConnectionStatus, val warnings: Set<Warning>, val redline: Int, val tSec: Double)`. RPM, speed and throttle are already smoothed (the `Smoother` lives in DashRuntime, advanced per frame).
  - `interface GaugeRenderer { fun draw(c: Canvas, f: FrameModel) /* draws in 800×480 logical space; caller applies Viewport */ }`, plus `fun rendererFor(style: Style, fonts: Fonts): GaugeRenderer`
  - Shared overlay in `GaugeRenderer.kt`: `fun drawStatusOverlay(c: Canvas, f: FrameModel, fonts: Fonts)`. For RECONNECTING/CONNECTING it dims the frame and shows "Connecting to OBD…" or "Reconnecting…". For NEEDS_SETUP it shows "Set up DashboardX on your phone". When OVERHEAT is active it draws a red edge flash at 3 Hz.
  - `class DashView(ctx: Context) : View`: redraws every frame via `postInvalidateOnAnimation` while attached, and applies `Viewport.fit` to its own size.

- [ ] **Step 1: Failing tests**
  - `chooseSource_demoWins`: `chooseSource(true, null, false) == DEMO`.
  - `chooseSource_needsSetupWithoutAdapter`: `chooseSource(false, null, true) == NEEDS_SETUP`.
  - `chooseSource_needsSetupWhenBluetoothUnusable`: `chooseSource(false, "AA:BB", false) == NEEDS_SETUP`.
  - `chooseSource_obd`: `chooseSource(false, "AA:BB", true) == OBD`.
- [ ] **Step 2:** Run `--tests 'com.karan.dashboardx.DashRuntimeTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement `chooseSource` and `DashRuntime`. `bluetoothUsable` means the adapter is enabled and `BLUETOOTH_CONNECT` is granted (on API ≥ 31).
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Implement `RacingRenderer` by porting `drawA` from the mockup: shift lights, arc tach with red zone, needle, digital RPM, speed, and left/right bars. Use `Fmt` for every number and `Fmt.clamp01` for every bar and angle. The redline comes from `FrameModel.redline`. Allocate `Paint`s once, never per frame. The oil tile uses `Fmt.temp(oil, oilUnsupported)`.
- [ ] **Step 6:** Make `MainActivity` show `DashView` with Racing, landscape and immersive. Temporarily force `demoMode=true` if no adapter is set (the settings screen comes in Task 12).
- [ ] **Step 7:** Verify. Run `./gradlew :app:installDebug` on a connected phone (or an emulator with `system-images;android-34`), launch the app, and run `adb exec-out screencap -p > $TMPDIR/racing.png`. Expected: the image looks like mockup A, the animation runs smoothly, and nothing appears in the logcat crash buffer (`adb logcat -b crash -d` is empty).
- [ ] **Step 8:** Commit: `feat: runtime hub and racing renderer on phone`.

### Task 9: HUD and Arcade styles

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/render/HudRenderer.kt`, `render/ArcadeRenderer.kt`
- Modify: `render/GaugeRenderer.kt` (`rendererFor`), `phone/DashView.kt` (tapping cycles the style and saves it to `Settings.style`)

**Interfaces:**
- Consumes: `GaugeRenderer`, `FrameModel`, `Fonts`, `Fmt` (Task 8)

- [ ] **Step 1:** Port `drawB` (tapes clipped to the band, value box, throttle reticle, SHIFT flash, four readout boxes) to `HudRenderer`. Use glow via `Paint.setShadowLayer` (the view needs a software or hardware layer that supports it; check on the device).
- [ ] **Step 2:** Port `drawC` (sky, striped sun, a grid scrolling at a rate proportional to speed, a 28-segment power bar, the pixel RPM number, SHIFT UP!, stat bars, SCORE) to `ArcadeRenderer`. Keep the grid offset as renderer state advanced by `FrameModel.tSec` deltas.
- [ ] **Step 3:** Verify. Install, tap to cycle through the three styles, and screencap each one to `$TMPDIR/{racing,hud,arcade}.png`. Expected: each matches its mockup, the style survives an app restart, and `adb logcat -b crash -d` is empty.
- [ ] **Step 4:** Commit: `feat: HUD and arcade renderers, tap to switch style`.

### Task 10: Android Auto car screen

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/car/DashCarAppService.kt`, `car/DashSession.kt`, `car/DashScreen.kt`, `car/SurfaceRenderer.kt`, `app/src/main/res/xml/automotive_app_desc.xml`, `app/src/main/res/drawable/ic_style.xml`, `ic_sound_on.xml`, `ic_sound_off.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `DashRuntime`, `rendererFor`, `Viewport`, `Settings.style` (Tasks 8–9); `EngineSoundController` (Task 11). Until Task 11 exists, the Sound action only toggles a boolean in `DashScreen`.
- Produces: `class SurfaceRenderer(carContext: CarContext) : SurfaceCallback, DefaultLifecycleObserver`, with `var style: Style`, a 30 fps draw loop on a `HandlerThread` using `surface.lockHardwareCanvas()`, and `Viewport.fit(visibleArea)`. It falls back to the full surface size if `visibleArea` is null.

- [ ] **Step 1:** Manifest: the service with an intent filter of action `androidx.car.app.CarAppService` and category `androidx.car.app.category.NAVIGATION`; meta-data `com.google.android.gms.car.application` → `@xml/automotive_app_desc` (`<uses name="template"/>`); `androidx.car.app.minCarApiLevel` = 1; and the two car permissions from Global Constraints.
- [ ] **Step 2:** `DashSession.onCreateScreen` returns `DashScreen`. `DashScreen` registers `SurfaceRenderer` through `carContext.getCarService(AppManager::class.java).setSurfaceCallback(...)` and calls `DashRuntime.acquire` on create and `release` on destroy. `onGetTemplate` returns `NavigationTemplate` with an `ActionStrip` of two actions: **Style** (icon `ic_style`; calls `style.next()`, saves it, `invalidate()`) and **Sound** (icon shows the on/off state; toggles, `invalidate()`).
- [ ] **Step 3:** Install the Desktop Head Unit: `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "extras;google;auto"`.
- [ ] **Step 4:** Verify with the DHU. On the phone, open Android Auto settings, tap the version 10 times to enable developer mode, enable **Unknown sources**, and choose **Start head unit server**. Then run `adb forward tcp:5277 tcp:5277 && $ANDROID_HOME/extras/google/auto/desktop-head-unit`. Expected: DashboardX appears in the DHU launcher and opens to the demo dashboard full screen. The Style button cycles all three styles, the gauges aren't hidden behind the action strip, and `adb logcat -b crash -d` is empty. Save a DHU screenshot (the `screenshot` command in the DHU console) for each style.
- [ ] **Step 5:** Commit: `feat: Android Auto navigation-template car screen`.

### Task 11: Engine sound and warning chime

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/audio/EngineSynth.kt`, `audio/EngineSoundController.kt`, `audio/Chime.kt`
- Modify: `car/DashScreen.kt` (Sound action), `DashRuntime.kt` (play chimes from `WarningResult.chime` whatever the sound setting)
- Test: `app/src/test/java/com/karan/dashboardx/audio/EngineSynthTest.kt`

**Interfaces:**
- Produces:
  - `class EngineSynth(private val sampleRate: Int = 44100) { fun firingHz(rpm: Double): Double /* rpm/60*3 */; fun render(out: FloatArray, rpm: Double, throttlePct: Double, volume: Float) }`: phase-continuous across calls, with rpm and throttle ramped linearly across the block
  - `class EngineSoundController(ctx: Context) { var enabled: Boolean /* false at construction */; fun update(rpm: Double?, throttle: Double?); fun release() }`. Uses a streaming `AudioTrack` (PCM float, 44.1 kHz, mono, `USAGE_GAME`/`CONTENT_TYPE_SONIFICATION`) on its own thread, and requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` while enabled. A null rpm fades to silence over 300 ms.
  - `object Chime { fun play(ctx: Context, w: Warning) }`: a short two-tone sound with `USAGE_ASSISTANCE_SONIFICATION`

- [ ] **Step 1: Failing tests**
  - `firingHz_inlineSix`: `firingHz(3000.0) == 150.0` and `firingHz(800.0) == 40.0`.
  - `render_silentAtZeroVolume`: every sample is 0 when volume is 0.
  - `render_louderWithThrottle`: the RMS over 4410 samples at throttle 90 is greater than 1.5× the RMS at throttle 5 (same rpm, volume 1).
  - `render_noClipping`: every sample is in −1..1 at rpm 7000, throttle 100, volume 1.
  - `render_pitchTracksRpm`: the zero-crossing rate of the low-passed output at 6000 rpm is greater than 1.6× the rate at 3000 rpm.
- [ ] **Step 2:** Run `--tests 'com.karan.dashboardx.audio.*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the synth. Use a sum of engine-order harmonics (orders 1.5, 3, 4.5, 6, 9, 12 relative to rpm/60) with an inline-6 weighting (order 3 strongest). Add a soft-clipped "rasp" that rises with throttle and rpm, filtered noise for intake and exhaust, and, when throttle is below 2 and rpm is above 1500, sparse random crackle pops for overrun. Finish with `tanh` soft limiting.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Wire it in: `DashScreen` owns an `EngineSoundController` and feeds it from `DashRuntime.frame()` in the render loop. The Sound action toggles `enabled`. Chimes play from `DashRuntime` when `WarningResult.chime` is non-empty.
- [ ] **Step 6:** Verify on the DHU in demo mode with music playing on the phone. Expected: the sound rises and falls with the demo pull, the music ducks without pausing, the chime plays at the redline in demo, and after the app is relaunched the sound starts off.
- [ ] **Step 7:** Commit: `feat: procedural straight-six engine sound and warning chime`.

### Task 12: Phone settings, adapter picker, diagnostics

**Files:**
- Create: `app/src/main/java/com/karan/dashboardx/phone/SettingsActivity.kt`, `phone/AdapterPicker.kt`, `phone/DiagnosticsActivity.kt`, layouts in `app/src/main/res/layout/`
- Modify: `phone/MainActivity.kt` (a gear button over `DashView` opens settings; remove the temporary forced demo), `AndroidManifest.xml`

**Interfaces:**
- Consumes: `Settings`, `DashRuntime.restart`, `DashRuntime.diag`, `Transports` (Tasks 2, 5, 8)

- [ ] **Step 1:** `SettingsActivity` has these rows:
  - **Adapter:** the current name/address and a **Choose** button.
  - **Demo mode:** a switch.
  - **Engine sound volume:** a slider.
  - **Fuel calibration:** a number field, 0.5–1.5.
  - **Displacement (L):** 1.396 or 1.591.
  - **Redline RPM** and **Coolant warning °C:** number fields.
  - **Diagnostics:** opens the diagnostics screen.

  Every change saves to `Settings` and calls `DashRuntime.restart`.
- [ ] **Step 2:** `AdapterPicker` requests runtime permissions as needed (`BLUETOOTH_CONNECT`/`SCAN` on 31+; `ACCESS_FINE_LOCATION` on ≤ 30). It lists bonded devices (Classic) plus an 8 s BLE scan (BLE) in one dialog, each labelled with its transport. Picking one stores the address and `adapterIsBle`. If Bluetooth is off, it shows "Turn on Bluetooth" with a button that fires `ACTION_REQUEST_ENABLE`. If permission is denied, it explains and stays usable.
- [ ] **Step 3:** `DiagnosticsActivity` shows `DiagLog.lines()` (auto-refresh every 1 s) and the supported PID list. A **Share** button sends the log as text via `ACTION_SEND`.
- [ ] **Step 4:** Verify on the phone. Toggle demo off with no adapter: the dashboard shows "Set up DashboardX on your phone". Deny the Bluetooth permission: you get the explanation and no crash. Toggle demo on: the dashboard animates. `adb logcat -b crash -d` is empty.
- [ ] **Step 5:** Commit: `feat: phone settings, adapter picker, diagnostics`.

### Task 13: README, release build, in-car check

**Files:**
- Create: `README.md`

- [ ] **Step 1:** README for the owner, covering:
  1. Installing (`source env.sh && ./gradlew :app:installDebug` with the phone on USB and USB debugging on).
  2. Android Auto developer mode and **Unknown sources**.
  3. Pairing the adapter (PIN 1234 or 0000) and choosing it in DashboardX settings.
  4. In the car: plug in, tap DashboardX, and use the Style and Sound buttons.
  5. How to share a diagnostics log.
  6. Known limits: oil temp may show N/A, fuel economy is an estimate (calibrate against a fill-up), the sound lags about 0.1–0.2 s, and it's not for the Play Store.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Expected: all tests pass, BUILD SUCCESSFUL.
- [ ] **Step 3:** In-car check (the owner, with Claude reading the shared log):
  - The adapter connects within 10 s of opening the app with the engine running.
  - RPM matches the car's tachometer.
  - Coolant settles around 85–95 °C once warm.
  - Oil shows a value or N/A.
  - Unplugging the adapter shows "Reconnecting…" within 3 s, and plugging it back in recovers.

  Record the results and the diagnostics log in `docs/superpowers/plans/2026-10-29-obd-android-auto-dash-incar.md`.
- [ ] **Step 4:** Commit: `docs: README and in-car verification notes`.

### Later (optional, not in this plan)

- Hyundai-specific oil temperature queries (spec: "separate, optional follow-up"). This starts from the diagnostics log captured in Task 13.
