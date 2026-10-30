# DashboardX

A personal OBD dashboard for Android Auto. It reads live data from a cheap ELM327 Bluetooth adapter in your 2019 Hyundai Verna (RPM, speed, coolant, oil, intake air, throttle, fuel economy) and shows it as a racing-style dashboard on the car screen. It can also play a synthesised engine sound.

It is a sideloaded hobby app. It is not for the Play Store.

You will need: a Mac with this folder, the Realme Narzo 20 Pro, a USB cable, and the ELM327 adapter.

## 1. Build and install on the phone

### Turn on USB debugging (Realme)
1. Settings, About phone, Version, then tap **Build number** 7 times.
2. Settings, Additional settings, Developer options, turn on **USB debugging**.
3. Plug the phone into the Mac. Accept the "Allow USB debugging?" prompt on the phone.

### Build and install
From this folder, in Terminal:

```
tools/gradle.sh :app:installDebug
```

Or install the built APK directly:

```
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is built by `tools/gradle.sh :app:assembleDebug`.

Either way runs the app on the phone, but it will **not** appear in Android Auto. For the car, install through KingInstaller instead (section 2).

Notes on build commands:
- Use `tools/gradle.sh` inside Claude Code's sandbox. It handles the sandbox's proxy and temp folder.
- Outside the sandbox (a normal Terminal), plain `./gradlew` also works once `JAVA_HOME` points at a JDK 17. Running `source env.sh` sets it from the JDK in `.tools/`.

## 2. Allow DashboardX in Android Auto

Android Auto 17.7 and newer hides sideloaded Car App Library apps such as DashboardX, even with Unknown sources on. It only lists them when the phone records the Play Store as the installer. A plain `adb install` records `pc` on the Realme, and `adb install -i com.android.vending` doesn't change that. What works is [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) (v2.4, tested 2026-10-30 with Android Auto 17.9).

### Turn on Unknown sources
1. On the phone, open Android Auto settings (Settings, search "Android Auto").
2. Tap **Version** about 10 times until it says developer mode is on.
3. Tap the three dots (top right), then **Developer settings**.
4. Turn **Unknown sources** ON.

### Install with KingInstaller
1. Copy the APK to the phone: `adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/DashboardX.apk`
2. Uninstall DashboardX if it is already installed (`adb uninstall com.karan.dashboardx`). Installing over a copy put there by plain adb keeps its `pc` installer record. Uninstalling clears the app's settings, so pick the adapter again afterwards.
3. In KingInstaller, turn **Oppo/Realme/OnePlus** ON (leave Root and Shizuku off). With all switches off, the installer is recorded as KingInstaller, which does not work.
4. Tap **Select APK to install**, choose `Download/DashboardX.apk`, then **Install as king**. If asked, choose the default **Package installer**.
5. Check: `adb shell pm list packages -i com.karan.dashboardx` must end with `installer=com.android.vending`.
6. Force-stop Android Auto. DashboardX now shows in Android Auto settings, **Customize launcher**.

### After a code change
The quick way is one command, which runs the tests, builds, copies the APK to the phone and opens KingInstaller:

```
tools/install-car.sh
```

Then do steps 3 and 4 above on the phone. The script waits for the install, checks the installer is `com.android.vending` and restarts Android Auto. If the check fails, run `tools/install-car.sh --fresh`, which uninstalls the old copy first (this clears the app's settings). Add `--skip-tests` to skip the tests.

The same steps by hand:
1. Run the tests and build: `bash tools/jvmcheck.sh`, then `tools/gradle.sh :app:assembleDebug`.
2. Copy the new APK to the phone (step 1 above).
3. Install it with KingInstaller (steps 3 to 6 above). Try this without uninstalling first, which may keep your settings (untested). If the step 5 check shows any installer other than `com.android.vending`, uninstall and install again.
4. If you uninstalled, pick the adapter again in Settings.

Don't use `installDebug` or a plain `adb install` for a build you want in the car. The app still runs on the phone, but Android Auto drops it again.

Builds are signed with `~/.android/debug.keystore` on this Mac. Back it up. A different keystore (another Mac, or a lost file) means you have to uninstall before every install.

DashboardX appears in the car as a **navigation app**. That is on purpose: Android Auto only lets navigation apps draw a full custom screen, which the dashboard needs.

## 3. First run on the phone

Open DashboardX on the phone. Until you choose an adapter or turn on Demo mode, the dashboard shows **"Set up DashboardX on your phone"**. Tap the gear in the top-right corner to open Settings.

- **Demo mode:** a switch in Settings. It plays fake driving data so you can try the styles and the sound without a car. Good for a first look.
- **Adapter:** the real thing. See the next section. Choosing an adapter turns Demo mode off.

"Set up DashboardX on your phone" appears only when no adapter is chosen (and Demo mode is off), or, on Android 12 and newer, when the Bluetooth permission is missing. If an adapter is chosen but Bluetooth is off, the dashboard shows **"Reconnecting…"** instead and connects by itself within about 10 s of Bluetooth coming on.

In Demo mode a small **DEMO** tag sits in the top-left corner, so fake data is never mistaken for the car.

On the phone, a tap on the dashboard (anywhere except the buttons) cycles the style: Racing, HUD, Arcade, Luxury, Cluster.

The speaker button next to the gear turns the engine sound on and off on the phone, the same way as the Sound icon in the car (see section 5). It starts **off** every time the app opens. It also switches off when you leave the app. Demo mode is a good way to try it.

## 4. Pair and choose the adapter

I do not know whether your Amazon adapter is Classic Bluetooth or BLE (Bluetooth Low Energy). The app handles both.

**Classic adapter**
1. Plug the adapter into the car's OBD port and turn the ignition on (engine running is best).
2. On the phone, Settings, Bluetooth. Pair with the adapter (often named OBDII, ELM327 or V-LINK). PIN is **1234** or **0000**.
3. In DashboardX, gear, **Choose**. Paired devices are labelled "Classic". Pick yours.

**BLE adapter**
1. BLE adapters are **not** paired in Bluetooth settings. Do not bother.
2. In DashboardX, gear, **Choose**. It scans for 8 seconds and lists nearby devices labelled "BLE". Pick yours.
3. On Android 10 and 11 (your phone), **Location must be ON** for Bluetooth scanning to work. Turn it on in the quick settings, and grant the Location permission when asked.

If you are not sure which type you have, try the Classic route first. If the adapter is not in the paired list after pairing, or does not pair at all, use the scan.

Other settings on the same screen: engine sound volume (0 to 100 %), fuel calibration (0.5 to 1.5), engine size, redline (3000 to 8000 rpm) and coolant warning level (90 to 130 C).

## 5. In the car

1. Plug the adapter in and start the engine.
2. Connect the phone to the car with Android Auto (USB cable or wireless).
3. On the car screen, open the app launcher and tap **DashboardX**.
4. It should connect to the adapter within about 10 seconds.

On the car screen the dashboard fills the whole app area:
- **Tap anywhere on the dashboard** to cycle the style: Racing, HUD, Arcade, Luxury, Cluster. The choice is remembered.
- **Sound** (the one button, a speaker icon in the top-right corner): a speaker with a cross means the engine sound is off, a speaker with sound waves means it is on. It starts **off** every time.

The engine sound is a smooth V8 note in the style of a Lexus: it rises evenly in pitch and brightness with the revs, with no hiss. Soft exhaust pops play when you lift off sharply, shift gear (the revs drop fast) or coast at high revs; steady driving stays clean. It plays in stereo on the car's media channel, so it uses all the speakers, and it lowers (ducks) your music instead of pausing it. When there is no RPM for about a second (engine off, adapter unplugged) it lets go and your music returns to full volume; it ducks again when RPM comes back. If the sound ever fails, the Sound icon switches back to off by itself.

## 6. Share a diagnostics log

If something is wrong, or to help add oil temperature support later:

1. On the phone, open DashboardX, gear, **Diagnostics**, **Open**.
2. Let it run for a minute or two while connected.
3. Tap **Share** and send it to yourself or paste it to Claude.

The log includes the connection status, which sensors the car supports and a time-stamped event log.

## 7. Known limits

- **Oil temperature may show N/A.** Many cars, probably including the Verna, do not report it through the standard OBD channel.
- **Fuel economy is an estimate.** It is calculated from airflow, not measured. To calibrate it against a fill-up, do one long, continuous drive:
  1. Fill the tank to the brim.
  2. Switch to the **Racing** style first, because TRIP AVG is only drawn there (on the phone, tap the dashboard until it shows Racing; in the car, tap the dashboard until it shows Racing).
  3. Start the app and drive the whole tank in one continuous drive. Keep the dashboard on screen (the car screen, or the phone app in front) the whole time, and do not switch Demo mode or pick an adapter.
  4. At the end, note the TRIP AVG km/l and the km on the car's trip meter.
  5. Fill to the brim again and note the litres added.
  6. Real km/l = km / litres.
  7. New calibration = current calibration x (TRIP AVG / real km/l).

  Example: the app says 16.0, the real figure is 14.5, and the current factor is 1.0. The new factor is 1.0 x (16.0 / 14.5) = 1.10. Enter it in Settings (range 0.5 to 1.5). A higher factor means more fuel and a lower km/l.

  The trip average resets only when DashboardX starts its data source: when the phone app or the car screen is opened while DashboardX was fully closed (neither showing), when you pick an adapter, or when you switch Demo mode on or off. It does **not** reset when the adapter drops out and reconnects, or when you change other settings (volume, calibration, engine size, redline, coolant warning).
- **The drive mode is inferred.** The Luxury and Cluster styles show SPORT, COMFORT or ECO. The Verna has no mode switch to read, so it is worked out from recent throttle and RPM: a hard pull shows SPORT within a second or two, and it settles back to ECO or COMFORT several seconds after you ease off.
- **The gear is an estimate.** The car does not report it (Diagnostics shows "gear PID A4: supported" if yours does), so it is worked out from RPM ÷ speed. It shows "-" below 8 km/h, and during a shift it holds the old gear for up to 1.5 s before changing. It slowly corrects itself if the tyres or final drive differ from the stock Verna 1.6 automatic.
- **The engine sound is delayed** by about 0.1 to 0.2 seconds, and it starts off on every drive.
- **The Verna throttle may idle at 10 to 18 %.** The infinity (∞) fuel economy reading only appears when the throttle reads near zero while moving, so it may show rarely or never on this car. The exhaust pops do not depend on that: they also play when you lift off sharply or the revs drop fast.
- **Not for the Play Store.** It is a personal sideloaded app. Android Auto only shows it when it was installed through KingInstaller (section 2), and a future Android Auto update may close that gap.

## 8. Troubleshooting

- **Cannot connect or stuck on Reconnecting:** check Bluetooth is on (with Bluetooth off the app waits on "Reconnecting…" and connects within about 10 s of turning it on), the adapter is firmly in the port and the ignition is on. Unplug and replug it. The app retries automatically. If it still fails, re-pick the adapter in Settings.
- **"No data from car — is the engine on?":** the adapter is connected but the car has sent no RPM for more than 3 s. Usually the ignition is off or the engine has stopped. Start the engine; the hint disappears as soon as data arrives. If it stays with the engine running, share the Diagnostics log.
- **DEMO tag in the corner:** Demo mode is on and the numbers are simulated. Switch it off in Settings (or pick an adapter) to see the car.
- **DashboardX missing from the car or Customize launcher:** run `adb logcat -d | grep CAR.VALIDATOR`. If it shows `Package DENIED; failed all other checks [com.karan.dashboardx]`, the install wasn't recorded as from the Play Store. Reinstall through KingInstaller (section 2).
- **Black car screen:** open Diagnostics on the phone and look at the status and log. Check Unknown sources is still on, then unplug and replug the phone.
- **No engine sound:** the Sound icon must show waves, not a cross (it resets to off each drive), the engine sound volume slider in Settings must not be at 0, and the car volume must be up.
- **"Set up DashboardX" still showing:** pick an adapter, or switch Demo mode on. On Android 12 or newer, also check the Bluetooth permission is granted. (Bluetooth being off shows "Reconnecting…", not this message.)

## For developers

- `bash tools/jvmcheck.sh` is a quick compile and unit test loop that does not need Gradle.
- Design: `docs/superpowers/specs/2026-10-29-obd-android-auto-dash-design.md`.
- In-car checklist: `docs/superpowers/plans/2026-10-29-obd-android-auto-dash-incar.md`.
