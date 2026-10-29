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

Notes on build commands:
- Use `tools/gradle.sh` inside Claude Code's sandbox. It handles the sandbox's proxy and temp folder.
- Outside the sandbox (a normal Terminal), plain `./gradlew` also works once `JAVA_HOME` points at a JDK 17. Running `source env.sh` sets it from the JDK in `.tools/`.

## 2. Allow DashboardX in Android Auto

Android Auto only shows apps from the Play Store unless you turn on developer mode.

1. On the phone, open Android Auto settings (Settings, search "Android Auto").
2. Tap **Version** about 10 times until it says developer mode is on.
3. Tap the three dots (top right), then **Developer settings**.
4. Turn **Unknown sources** ON.

DashboardX appears in the car as a **navigation app**. That is on purpose: Android Auto only lets navigation apps draw a full custom screen, which the dashboard needs.

## 3. First run on the phone

Open DashboardX on the phone. Until you choose an adapter or turn on Demo mode, the dashboard shows **"Set up DashboardX on your phone"**. Tap the gear in the top-right corner to open Settings.

- **Demo mode:** a switch in Settings. It plays fake driving data so you can try the styles and the sound without a car. Good for a first look.
- **Adapter:** the real thing. See the next section. Choosing an adapter turns Demo mode off.

"Set up DashboardX on your phone" also appears if an adapter is saved but Bluetooth is turned off, or (Android 12 and newer) if the Bluetooth permission was denied. Turn Bluetooth on or grant the permission, and it connects.

On the phone, a tap on the dashboard (anywhere except the gear) cycles the style: Racing, HUD, Arcade.

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

Buttons on the car screen:
The two buttons on the car screen are icons, with no text:
- **Style** (a gauge icon: an arc with a needle and a centre dot): cycles Racing, HUD and Arcade layouts. The choice is remembered.
- **Sound** (a speaker icon): a speaker with a cross means the engine sound is off, a speaker with sound waves means it is on. It starts **off** every time.

The engine sound plays through the car speakers and lowers (ducks) your music instead of pausing it.

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
  2. Switch to the **Racing** style first, because TRIP AVG is only drawn there (tap the phone screen, or use the gauge icon in the car).
  3. Start the app and drive the whole tank in a single connected session. Do not open Settings or change anything, and do not unplug the adapter or phone.
  4. At the end, note the TRIP AVG km/l and the km on the car's trip meter.
  5. Fill to the brim again and note the litres added.
  6. Real km/l = km / litres.
  7. New calibration = current calibration x (TRIP AVG / real km/l).

  Example: the app says 16.0, the real figure is 14.5, and the current factor is 1.0. The new factor is 1.0 x (16.0 / 14.5) = 1.10. Enter it in Settings (range 0.5 to 1.5). A higher factor means more fuel and a lower km/l.

  The trip average covers only the current connection. It restarts whenever the app reconnects (car screen reopened, adapter or phone unplugged and replugged) and whenever you change a setting.
- **The engine sound is delayed** by about 0.1 to 0.2 seconds, and it starts off on every drive.
- **The Verna throttle may idle at 10 to 18 %.** The infinity (∞) fuel economy reading and the overrun crackle sound only appear when the throttle reads near zero while moving, so they may show rarely or never on this car.
- **Not for the Play Store.** It is a personal sideloaded app and Android Auto will only show it with Unknown sources on.

## 8. Troubleshooting

- **Cannot connect or stuck on Reconnecting:** check the adapter is firmly in the port and the ignition is on. Unplug and replug it. The app retries automatically. If it still fails, re-pick the adapter in Settings.
- **Black car screen:** open Diagnostics on the phone and look at the status and log. Check Unknown sources is still on, then unplug and replug the phone.
- **No engine sound:** the Sound icon must show waves, not a cross (it resets to off each drive), the engine sound volume slider in Settings must not be at 0, and the car volume must be up.
- **"Set up DashboardX" still showing:** pick an adapter, or switch Demo mode on. Also check Bluetooth is on and, on Android 12 or newer, that the Bluetooth permission is granted.

## For developers

- `bash tools/jvmcheck.sh` is a quick compile and unit test loop that does not need Gradle.
- Design: `docs/superpowers/specs/2026-10-29-obd-android-auto-dash-design.md`.
- In-car checklist: `docs/superpowers/plans/2026-10-29-obd-android-auto-dash-incar.md`.
