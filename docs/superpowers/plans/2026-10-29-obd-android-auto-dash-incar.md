# DashboardX in-car and device verification

Fill this in as you test. Every result starts as "pending — owner to run". Replace it with PASS or FAIL and a note.

Build and install first: `tools/gradle.sh :app:installDebug` (see README).

## A. Phone checks (no car needed)

Fresh start: `adb shell pm clear com.karan.dashboardx`, then `adb logcat -b crash -c`, then `adb shell am start -n com.karan.dashboardx/.phone.MainActivity`.

| # | Check | Result |
|---|-------|--------|
| A1 | First launch shows "Set up DashboardX on your phone"; gear reads "⚙ Set up"; tapping away from the gear cycles the style, gear opens Settings | pending — owner to run |
| A2 | Landscape and immersive; Racing layout looks right; animation smooth in demo | pending — owner to run |
| A3 | Settings, Demo mode on, back: dashboard animates, gear shows just "⚙". Demo off with no adapter brings back the setup message | pending — owner to run |
| A4 | Tap through all 3 styles (Racing, HUD, Arcade); screenshots: `adb exec-out screencap -p > $TMPDIR/racing.png` (and hud, arcade) | pending — owner to run |
| A5 | Restart the app: the chosen style persists | pending — owner to run |
| A6 | Orbitron weights 500, 600, 800 visibly differ (variable font works) | pending — owner to run |
| A7 | Glows on circles, arcs and lines render | pending — owner to run |
| A8 | Shift lights flash blue past 6200 rpm in demo | pending — owner to run |
| A9 | Settings, Choose, deny permissions: Location on Android 10/11; picker still opens with paired devices and "BLE scan is off…" line; no crash | pending — owner to run |
| A10 | With Location services off, Choose, grant: "Location is off…" line is still there after the 8 s scan | pending — owner to run |
| A11 | Choose, grant: 8 s scan lists paired devices ("Classic") and nearby BLE devices ("BLE"). Picking the adapter updates the row and turns Demo off | pending — owner to run |
| A12 | Bluetooth off, Choose, "Turn on Bluetooth": system prompt appears | pending — owner to run |
| A13 | Calibration 2.0 rejected (red error, not saved), 1.1 saves. Redline 2999 rejected. Coolant 131 rejected | pending — owner to run |
| A14 | Settings, Diagnostics, Open: log updates each second, status and PIDs shown, Share opens the chooser | pending — owner to run |
| A15 | `adb logcat -b crash -d` is empty | pending — owner to run |

## B. Desktop Head Unit (DHU) checks

Setup: `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "extras;google;auto"`. On the phone: Android Auto settings, tap Version 10 times, enable Unknown sources, three-dot menu, **Start head unit server**. Then on the Mac:

```
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

| # | Check | Result |
|---|-------|--------|
| B1 | DashboardX appears in the DHU launcher and opens to the demo dashboard full screen (unconfigured runs demo) | pending — owner to run |
| B2 | NavigationTemplate with only an action strip is accepted; Style and Sound icons render visibly | pending — owner to run |
| B3 | Style cycles RACING, HUD, ARCADE | pending — owner to run |
| B4 | Gauges are not hidden behind the action strip | pending — owner to run |
| B5 | Sound icon flips between on and off | pending — owner to run |
| B6 | DHU `screenshot` saved for each style | pending — owner to run |
| B7 | `adb logcat -b crash -d` is empty | pending — owner to run |

## C. Audio checks (DHU or real car, with music playing on the phone)

| # | Check | Result |
|---|-------|--------|
| C1 | Sound icon starts **off** (crossed speaker), no engine sound | pending — owner to run |
| C2 | Tap Sound: icon changes to waves; engine sound rises and falls with the demo pulls; smooth, no clicks, zipper noise or harshness | pending — owner to run |
| C3 | Music **ducks without pausing** | pending — owner to run |
| C4 | Off-throttle at high rpm in the demo: occasional crackle pops | pending — owner to run |
| C5 | Two-tone chime when the demo crosses redline; plays whether Sound is on or off; does not stack | pending — owner to run |
| C6 | Sound off: engine fades out over about 0.3 s, music returns to full volume | pending — owner to run |
| C7 | Switch to Maps and back: sound is off and icon shows off | pending — owner to run |
| C8 | Kill and relaunch: Sound starts off | pending — owner to run |
| C9 | Volume slider (Settings): judge whether the default 0.7 is too quiet or too loud over ducked music | pending — owner to run |
| C10 | `adb logcat \| grep -i -E "AudioTrack\|AudioFocus\|dashboardx"` shows no errors; diag log has no `engine sound:` or `chime:` failure lines | pending — owner to run |

## D. Real car (engine running, adapter plugged in)

| # | Check | Result |
|---|-------|--------|
| D1 | Adapter type (Classic or BLE) and how it was chosen | pending — owner to run |
| D2 | Adapter connects within 10 s of opening the app with the engine running | pending — owner to run |
| D3 | RPM matches the car's tachometer | pending — owner to run |
| D4 | Coolant settles around 85–95 °C once warm | pending — owner to run |
| D5 | Oil temp shows a value or N/A (note which) | pending — owner to run |
| D6 | Unplugging the adapter shows "Reconnecting…" within 3 s | pending — owner to run |
| D7 | Plugging the adapter back in recovers | pending — owner to run |
| D8 | Speed matches the speedometer (small offset is normal) | pending — owner to run |
| D9 | Idle throttle value noted (expected 10–18 %); does ∞ km/l or crackle ever show? | pending — owner to run |
| D10 | Fuel calibration: litres added at fill-up vs DashboardX estimate, resulting factor | pending — owner to run |
| D11 | Engine sound delay feels acceptable (about 0.1–0.2 s expected) | pending — owner to run |

## E. Diagnostics log

Settings, Diagnostics, Open, Share. Paste the shared text below.

```
pending — owner to run (paste diagnostics log here)
```
