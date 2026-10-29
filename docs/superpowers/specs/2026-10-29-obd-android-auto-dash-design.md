# DashboardX — OBD game-style dashboard for Android Auto

Date: 2026-10-29
Owner: Karan (karan.s@leanafy.com)

## Intent

This is a personal Android app. It reads live engine data from a Bluetooth OBD-II adapter plugged into the owner's car and shows it full screen on the car's Android Auto display, styled like a video game. It is for one person, one car and one phone. It is a fun, glanceable dashboard, not a diagnostic tool.

- **Car:** Hyundai Verna 2019, petrol (Indian market). The engine is either the 1.4 Kappa (1396 cc) or the 1.6 Gamma Dual VTVT (1591 cc). The model isn't confirmed, so displacement is a setting.
- **Phone:** Realme Narzo 20 Pro (Android 10/11 with Realme UI, MediaTek Helio G95).
- **Adapter:** a cheap ELM327-style adapter bought on Amazon India (ASIN B08SMNWZSS). We could not read the listing, so whether it uses Bluetooth Classic (SPP) or BLE is unknown.
- **Development Mac:** has the Android SDK at `~/Library/Android/sdk` (platform-tools, build-tools, emulator, extras). Gradle is not installed globally; use the Gradle wrapper.

## What the owner said they want

- **Most important values:** RPM first, then temperatures.
- **Three visual styles**, all kept, switchable from inside the app. Approved mockups are in `.superpowers/mockups/styles.html`:
  - **A · Racing game:** a central arc tachometer (0–7000) with a red zone, a needle and a large digital RPM readout. It has a 15-LED shift-light row across the top (green, then yellow, then red, flashing blue/red past redline), with speed under the tach. The left column shows coolant, oil and intake air temps as number plus bar. The right column shows throttle %, instant km/l and trip average km/l.
  - **B · Fighter-jet HUD:** green vector graphics with glow on near-black. A scrolling RPM tape is on the left (red numbers at and above redline) and a scrolling speed tape on the right. The centre has a reticle with a throttle ring and a flashing "SHIFT" past redline. Boxed readouts along the bottom show CLT, OIL, IAT and ECO km/l.
  - **C · Retro arcade:** synthwave look with a gradient sky, a striped sun and a perspective grid that scrolls with speed. RPM is a 28-segment power bar (cyan, then pink, then red) with a big pixel-font number and a flashing "SHIFT UP!". Coolant, oil and air are shown as 10-block stat bars. Throttle is a stat bar, and km/l is shown as "SCORE".
- **Values to show:** RPM, coolant temp, oil temp, intake air temp, throttle %, speed, and fuel economy in km/l (instant and trip average).
- **Synthetic engine sound** that follows RPM, inspired by a BMW straight-six, playing on top of the user's music.

## Goals

1. When the app is opened in Android Auto, it shows one of the three styles full screen on the car display with live data.
2. The owner can switch style and toggle engine sound from the car screen. The last-used style is remembered.
3. After a one-time pairing and setup on the phone, it connects to the adapter automatically with no phone interaction.
4. The procedural straight-six sound follows RPM and throttle and mixes over music.
5. A demo mode with simulated data, like the mockups, works without a car or adapter.

## Non-goals

- Play Store distribution. The app is sideloaded.
- Trip history, logging, graphs or export.
- Reading or clearing diagnostic trouble codes.
- Support for other vehicles beyond what standard OBD-II gives for free.
- iOS or CarPlay.

## Anti-goals (failure even if it "works")

- **Stale data shown as live.** If the adapter link drops or a value stops updating, the gauges must visibly grey out and show "reconnecting". They must never freeze on old numbers.
- **Made-up values.** If oil temp (or any PID) isn't supported, show "N/A". Never estimate it or fill it with a placeholder.
- **Sound that starts by itself.** The engine sound is off at every app start and must be switched on deliberately, because it can hide real mechanical noises.
- **Jank or lag on the main gauge.** RPM must animate smoothly between OBD samples, and the UI must never block on Bluetooth I/O.

## Decided how (these were Claude's calls, which the owner accepted in conversation)

### Platform and Android Auto approach
- Native Kotlin app using the Android for Cars App Library (`androidx.car.app`).
- **Key constraint:** non-navigation Android Auto apps can only use Google's fixed templates, so custom full-screen drawing is not possible for them. The app therefore declares the **navigation category** (`androidx.car.app.category.NAVIGATION`) and uses `NavigationTemplate` with a `SurfaceCallback`, drawing gauges on the provided Surface with Canvas. This only works when sideloaded with Android Auto developer mode and "Unknown sources" enabled. That is accepted, because this is a personal app.
- Car-screen controls live in the NavigationTemplate's action strip: a **Style** button (cycles A → B → C) and a **Sound** on/off button. The current style is saved in preferences.
- Draw within the host-reported **stable/visible area** so the action strip and system UI don't cover gauges. Design in a logical 800×480 space and scale to fit, as the mockups do.
- The same renderer code draws on the phone screen (a custom View/SurfaceView) when the app is opened on the phone. This is useful for testing in the driveway.

### OBD connection
- Support **both** Bluetooth Classic (SPP, UUID `00001101-0000-1000-8000-00805F9B34FB`) and BLE (common ELM327 BLE service/characteristics such as FFE0/FFE1 and FFF0/FFF1). The user picks the adapter once on the phone, and the app remembers its address and transport.
- ELM327 init: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0` (auto protocol; the Verna is expected to be ISO 15765-4 CAN 11-bit 500 kbps). Use `0100`/`0120`/`0140` to find supported PIDs, and cache the result per adapter.
- Standard Mode 01 PIDs: `0C` RPM, `0D` speed, `05` coolant, `0F` intake air temp, `11` throttle, `10` MAF (if supported), `0B` MAP, `04` load, and `5C` oil temp (if supported).
- **Polling priority:** RPM and throttle every cycle; speed every other cycle; temps about every 2 s. The aim is about 5–10 RPM updates per second on a typical clone.
- **Oil temp:** try standard `015C` first. If it isn't supported, attempt Hyundai manufacturer-specific queries to the engine ECU. This is experimental. If no valid response comes back, the tile shows "N/A" permanently for that session.
- Auto-reconnect with backoff. Bluetooth I/O runs off the main thread (coroutines). Values carry timestamps, and anything older than about 3 s is rendered as stale.

### Fuel economy
- Fuel flow comes from MAF if supported: `L/h = MAF(g/s) / 14.7 / 740 g/L × 3600`.
- Otherwise use speed-density from MAP, IAT, RPM, displacement and an assumed volumetric efficiency (default 0.85).
- Instant km/l = speed ÷ fuel flow. When fuel is cut on overrun (throttle ≈ 0 and moving), show ∞. When stationary, show "--".
- Trip average is accumulated from app start (or connection) and is not persisted.
- A **calibration factor** on the phone settings screen lets the owner correct against a real fill-up. Displacement is also a setting.

### Engine sound
- Generated in real time (no recorded samples) with `AudioTrack` streaming PCM. It models a straight-six: firing frequency = RPM/60 × 3, plus engine-order harmonics, a little exhaust rasp and noise, and throttle-dependent loudness and brightness. Lift-off gives a softer, crackly overrun.
- RPM is interpolated between OBD samples so pitch glides rather than steps. A latency of about 0.1–0.2 s behind real RPM is expected and accepted.
- It mixes **on top of music**: request transient-may-duck focus (or equivalent) so music ducks slightly rather than stopping. There is a volume setting on the phone.
- It is off at each app start and toggled from the car action strip.

### Warnings
- Above 105 °C coolant, or RPM above redline (default 6200, configurable), the screen flashes and a short chime plays in every style, whether the engine sound is on or off. Thresholds are configurable on the phone.

### Phone setup screen
- Adapter picker, demo-mode toggle, engine-sound volume, fuel calibration factor, displacement, redline and coolant warning threshold, and the live dashboard preview.

### Settled after builder review (Claude's calls)
- **When it connects:** the adapter connection starts when DashboardX opens on the car screen, or when the phone app is opened. There is no background auto-connect at plug-in. Android Auto cannot launch a third-party app on the car screen by itself, so the owner taps the DashboardX icon once per drive.
- **Oil temp scope:** the first version uses only standard `015C` and shows "N/A" if it's unsupported. Hyundai-specific oil-temp queries are a separate, optional follow-up once everything else works in the car.
- **How it's used (owner's answer):** the owner normally drives with an iPhone (CarPlay) and will connect the Android phone only occasionally, for fun. So no other navigation app runs alongside it. OBD, the sound and the warnings live inside the car-screen session plus the phone activity, with no background service. Engine sound mixes over whatever audio the Android phone plays through Android Auto. If the head unit is on its own radio or USB source, it may cut that source while the sound plays, and that is accepted.
- **Testing in the car:** the owner tests with the real car and adapter. The app includes a hidden diagnostics screen on the phone showing raw adapter traffic and supported PIDs, which can be shared as a text file. This is a debug tool, not the excluded "trip logging".

## Left to the builder

- Exact Car App Library version, min/target SDK and permission handling. On Android ≤11, classic Bluetooth plus location is needed for BLE scanning; on 12+ use `BLUETOOTH_CONNECT`/`SCAN`.
- Code structure, synth design details, colours and fonts. Match the mockups; bundle the fonts (Orbitron, Share Tech Mono, Press Start 2P are OFL) instead of loading from the web.
- How to verify Android Auto rendering on the Mac (the Desktop Head Unit in SDK extras is the expected route) and on the real car.
- Whether to `git init` the repo (the directory is not a git repo yet).
- Short README steps for the owner: enabling Android Auto developer mode, Unknown sources, and installing over USB.
