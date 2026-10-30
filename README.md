# DashboardX

An OBD-II dashboard for Android Auto. It reads live engine data from a cheap ELM327 Bluetooth adapter (RPM, speed, coolant, oil and intake air temperature, throttle, fuel economy) and draws it as a full-screen, game-style dashboard on the car screen. It can also play a synthesised engine sound through the car speakers.

It is a hobby project, built for one car (a 2019 Hyundai Verna 1.6 automatic) and one phone (a Realme Narzo 20 Pro on Android 11). It uses standard OBD-II PIDs, so it should work on most petrol cars from about 2008 on, but nothing beyond that pair has been tested. It is sideloaded and not on the Play Store.

> **Safety:** set the dashboard up before you drive. Don't tap through styles or settings while moving. The readings are for fun, not a replacement for the car's own instruments.

## Features

- **Seven styles:** Racing, HUD, Arcade, Luxury, Cluster, Most Wanted and Cockpit. Tap the dashboard to cycle through them.
- **Live data** over Bluetooth Classic or BLE (Bluetooth Low Energy) ELM327 adapters, with automatic reconnect.
- **Engine sound:** a smooth synthesised V8 that follows the revs, with soft exhaust pops on lift-off. It ducks your music instead of pausing it.
- **Estimated gear and fuel economy**, with a fuel calibration setting.
- **Drive mode selector** (Comfort / Sport / Sport+) that changes the look.
- **Demo mode** with simulated driving data, so you can try everything without a car.
- **Diagnostics screen** with a shareable log of the connection and the PIDs your car supports.

## Requirements

- An Android phone on Android 10 (API 29) or newer, with Android Auto.
- A car or head unit with Android Auto (USB or wireless).
- An ELM327 OBD-II adapter, Bluetooth Classic or BLE. Cheap clones work.
- To build: JDK 17 and the Android SDK (platform 34). Android Studio provides both.

## 1. Build

Clone the repository and build the debug APK:

```
git clone https://github.com/stardust1420/dashboardX.git
cd dashboardX
./gradlew :app:assembleDebug
```

`JAVA_HOME` must point at a JDK 17, and the build needs to find the Android SDK, through `ANDROID_HOME` or a `local.properties` file with `sdk.dir=/path/to/sdk` (Android Studio writes this for you). The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

To run the unit tests: `./gradlew :app:testDebugUnitTest`.

### Turn on USB debugging
1. On the phone: Settings, About phone, tap **Build number** 7 times. (The menu path differs by brand.)
2. In Developer options, turn on **USB debugging**.
3. Plug the phone into the computer and accept the "Allow USB debugging?" prompt.

### Install on the phone only

```
./gradlew :app:installDebug
```

This is enough to try the app on the phone (Demo mode, styles, sound), but it will **not** show up in Android Auto. For the car, see section 2.

## 2. Get it into Android Auto

Android Auto 17.7 and newer hides sideloaded Car App Library apps, even with Unknown sources on. It only lists them when the phone records the Play Store as the installer. A plain `adb install` doesn't, and `adb install -i com.android.vending` is blocked on many phones. What works on the Realme test phone is [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) (v2.4, tested in October 2026 with Android Auto 17.9).

### Turn on Unknown sources in Android Auto
1. Open Android Auto settings on the phone (search Settings for "Android Auto").
2. Tap **Version** about 10 times until developer mode turns on.
3. Tap the three dots (top right), then **Developer settings**.
4. Turn **Unknown sources** on.

### Install with KingInstaller
1. Copy the APK to the phone: `adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/DashboardX.apk`
2. If DashboardX is already installed from adb, uninstall it first (`adb uninstall com.karan.dashboardx`). Installing over it keeps the old installer record. Uninstalling clears the app's settings.
3. In KingInstaller, turn on the switch for your phone's brand. On the Realme that is **Oppo/Realme/OnePlus** (Root and Shizuku off). With every switch off, the installer is recorded as KingInstaller, which doesn't work. Other brands are untested.
4. Tap **Select APK to install**, choose `Download/DashboardX.apk`, then **Install as king**. If asked, pick the default **Package installer**.
5. Check: `adb shell pm list packages -i com.karan.dashboardx` must end with `installer=com.android.vending`.
6. Force-stop Android Auto. DashboardX now shows in Android Auto settings, **Customize launcher**.

On macOS, `tools/install-car.sh` does steps 1 to 6 in one go: it runs the tests, builds, copies the APK, opens KingInstaller, waits for you to tap **Install as king**, checks the installer record and restarts Android Auto. Add `--fresh` to uninstall the old copy first, or `--skip-tests` to skip the tests. It expects a JDK 17 in `.tools/` (see [For developers](#for-developers)).

Debug builds are signed with your `~/.android/debug.keystore`. Keep it. A different keystore means uninstalling before every install.

DashboardX appears in the car as a **navigation app**. That is on purpose: Android Auto only lets navigation apps draw a full custom screen, which the dashboard needs. It does not do navigation.

## 3. First run

Open DashboardX on the phone. Until you choose an adapter or turn on Demo mode, the dashboard shows **"Set up DashboardX on your phone"**. Tap the gear in the top-right corner to open Settings.

- **Demo mode:** plays simulated driving data. A small **DEMO** tag sits in the top-left corner so it is never mistaken for real data. Good for a first look.
- **Adapter:** the real thing, see section 4. Choosing an adapter turns Demo mode off.

On the phone, a tap on the dashboard (anywhere except the gear, the speaker and the drive mode selector) cycles the style. A tap on the **COMFORT / SPORT / SPORT+** selector steps the drive mode. The speaker button turns the engine sound on and off; it starts **off** every time the app opens and switches off when you leave the app.

## 4. Pair and choose the adapter

ELM327 adapters come as Bluetooth Classic or BLE. The app handles both.

**Classic adapter**
1. Plug the adapter into the car's OBD port and turn the ignition on (engine running is best).
2. Pair it in the phone's Bluetooth settings (it is often called OBDII, ELM327 or V-LINK). The PIN is usually **1234** or **0000**.
3. In DashboardX, gear, **Choose**. Paired devices are labelled "Classic". Pick yours.

**BLE adapter**
1. Don't pair BLE adapters in Bluetooth settings.
2. In DashboardX, gear, **Choose**. It scans for 8 seconds and lists nearby devices labelled "BLE". Pick yours.
3. On Android 10 and 11, **Location must be on** for Bluetooth scanning, and the app asks for the Location permission. On Android 12 and newer it asks for the Bluetooth permission instead.

If you don't know which type you have, try pairing first. If the adapter doesn't pair or doesn't appear in the paired list, use the scan.

Other settings on the same screen: engine sound volume (0 to 100 %), fuel calibration (0.5 to 1.5), engine size, redline (3000 to 8000 rpm) and coolant warning level (90 to 130 °C).

## 5. In the car

1. Plug the adapter in and start the engine.
2. Connect the phone to the car with Android Auto.
3. On the car screen, open the app launcher and tap **DashboardX**.
4. It connects to the adapter within about 10 seconds.

The dashboard fills the whole app area, edge to edge (on a wide screen the side columns spread out):
- **Tap anywhere on the dashboard** to cycle the style. The choice is remembered.
- **Tap the COMFORT / SPORT / SPORT+ selector** to step the drive mode. It is remembered and shared by all styles.
- **Tap the speaker** to turn the engine sound on and off: a speaker with a cross is off, a glowing speaker with sound waves is on. It starts off every time. Racing, Arcade and Most Wanted draw it bottom right; HUD under the speed tape; Luxury at the end of the header; Cluster and Cockpit top left.

The engine sound is a smooth V8 note that rises evenly in pitch and brightness with the revs, with no hiss. Soft exhaust pops play when you lift off sharply, shift gear or coast at high revs. It plays in stereo on the car's media channel and lowers (ducks) your music instead of pausing it. When RPM stops arriving for about a second (engine off, adapter unplugged) it lets go and your music returns to full volume. If the sound ever fails, the speaker switches back to off by itself.

## 6. Diagnostics log

If something is wrong:

1. On the phone, open DashboardX, gear, **Diagnostics**, **Open**.
2. Let it run for a minute or two while connected.
3. Tap **Share**.

The log has the connection status, which PIDs the car supports and a time-stamped event log. Attach it to an issue if you report a problem.

## 7. Known limits

- **Tuned for one car.** The gear estimate uses the 2019 Verna 1.6 automatic's gear ratios (`calc/GearEstimator.kt`). It slowly corrects for tyre size and final drive, but a different gearbox needs different ratios. Fuel economy assumes a petrol engine.
- **Oil temperature may show N/A.** Many cars don't report it through the standard OBD PID.
- **Fuel economy is an estimate**, calculated from airflow (MAF), not measured. To calibrate it against a fill-up:
  1. Fill the tank to the brim.
  2. Switch to the **Racing** style (TRIP AVG is only drawn there).
  3. Drive the whole tank in one continuous drive with the dashboard on screen. Don't switch Demo mode or pick an adapter on the way.
  4. Note the TRIP AVG km/l and the km on the car's trip meter.
  5. Fill to the brim again and note the litres added.
  6. Real km/l = km / litres.
  7. New calibration = current calibration × (TRIP AVG / real km/l).

  Example: the app says 16.0, the real figure is 14.5 and the current factor is 1.0, so the new factor is 1.0 × (16.0 / 14.5) = 1.10. A higher factor means more fuel and a lower km/l.

  The trip average resets when DashboardX starts its data source: when the app is opened from fully closed, when you pick an adapter, or when you switch Demo mode. It does not reset when the adapter drops out and reconnects.
- **The drive mode is for show.** It changes the look (the Luxury floor glow, the highlighted row), not the car or the engine sound.
- **The gear is an estimate** worked out from RPM ÷ speed, since most cars don't report it. It shows "-" below 8 km/h and holds the old gear for up to 1.5 s during a shift.
- **The engine sound lags** the engine by about 0.1 to 0.2 seconds (OBD polling is slow).
- **Idle throttle:** some cars read 10 to 18 % throttle at idle, so the infinity (∞) fuel economy reading, which needs near-zero throttle while moving, may rarely show.
- **Android Auto may close the sideload gap.** It only shows DashboardX when installed through KingInstaller (section 2), and a future Android Auto update may stop that working.

## 8. Troubleshooting

- **Can't connect, or stuck on "Reconnecting…":** check Bluetooth is on, the adapter is firmly in the port and the ignition is on. Unplug and replug the adapter. The app retries on its own. If it still fails, pick the adapter again in Settings.
- **"No data from car — is the engine on?":** the adapter is connected but the car has sent no RPM for more than 3 s. Start the engine. If it stays with the engine running, check the Diagnostics log.
- **DEMO tag in the corner:** Demo mode is on and the numbers are simulated. Switch it off in Settings or pick an adapter.
- **DashboardX missing from the car or Customize launcher:** run `adb logcat -d | grep CAR.VALIDATOR`. If it shows `Package DENIED; failed all other checks [com.karan.dashboardx]`, the install wasn't recorded as from the Play Store. Reinstall through KingInstaller (section 2).
- **"has encountered an unexpected error" on the car screen:** run `adb logcat -d | grep -E "AndroidRuntime|CarApp.H"` for the reason, and include it in an issue.
- **Black car screen:** open Diagnostics on the phone and check the status and log. Check Unknown sources is still on, then reconnect the phone.
- **No engine sound:** the speaker must show waves, not a cross (it resets to off each drive), the volume slider in Settings must be above 0, and the car volume must be up.
- **"Set up DashboardX" still showing:** pick an adapter or switch Demo mode on. On Android 12 or newer, check the Bluetooth permission is granted.

## For developers

The code is Kotlin with no UI framework: every style is drawn on a `Canvas`, on the phone (`phone/DashView`) and on the car surface (`car/SurfaceRenderer`).

| Package | What it does |
| --- | --- |
| `obd` | ELM327 protocol, PID decoding, poll schedule, Classic and BLE transports |
| `data` | `CarState`, the OBD and demo data sources |
| `calc` | Fuel economy and gear estimate |
| `render` | One renderer per style, shared widgets (mode selector, sound button) |
| `audio` | Engine sound synth and warning chime |
| `car` | Android Auto (Car App Library) session, screen and surface |
| `phone` | Phone activities: dashboard, settings, adapter picker, diagnostics |

Design notes and plans are in `docs/superpowers/`.

The scripts in `tools/` were written for macOS and the author's setup. They expect a local toolchain in the gitignored `.tools/` folder: a JDK 17 in `.tools/jdk-17*` and Gradle in `.tools/gradle-8.7`. `source env.sh` sets `JAVA_HOME`, `ANDROID_HOME` (`~/Library/Android/sdk`) and the Gradle home from there. With that in place:

- `bash tools/jvmcheck.sh [fully.qualified.TestClass ...]` compiles and runs the unit tests without Gradle, which is much faster. It downloads its own Kotlin compiler and JUnit into `.tools/jvmcheck` on first run.
- `tools/gradle.sh <task>` runs Gradle with settings that also work inside a sandboxed proxy.
- `tools/install-car.sh` is the one-command car install (section 2).
- `tools/dhu.sh` opens the car screen on the Mac (below).

Without `.tools/`, use `./gradlew` as in section 1.

### Car screen on the Mac

Google's Desktop Head Unit (DHU) shows the Android Auto car screen in a window, driven by the phone over USB. DashboardX looks and behaves there as in the car (click to tap), so you can work on styles without the car.

One-time setup:
1. Install the DHU: `"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "extras;google;auto"` (or SDK Manager in Android Studio, **Android Auto Desktop Head Unit Emulator**).
2. Turn on Android Auto developer settings on the phone (section 2).

Each time, with the phone on USB:
1. In Android Auto settings, tap the **three-dot menu, Start head unit server**. It stops whenever Android Auto restarts, so start it again then.
2. Run `tools/dhu.sh`. It waits for the head unit server (up to 2 minutes), forwards the port and opens the car screen. Open **DashboardX** from its launcher.

Options after `tools/dhu.sh` go to the DHU, for example `tools/dhu.sh -c config/default_720p.ini` for a 720p screen. As in the car, DashboardX only shows in the DHU when installed through KingInstaller.

## Disclaimer

DashboardX is an independent hobby project. It is not affiliated with or endorsed by Google, Hyundai or any adapter maker. Android Auto is a trademark of Google LLC. Use it at your own risk.

## License

MIT, see [LICENSE](LICENSE).
