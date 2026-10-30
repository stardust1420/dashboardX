# DashboardX
Sideloaded Android Auto OBD dashboard (Kotlin, `com.karan.dashboardx`). User guide: README.md. Spec/plans: docs/superpowers/.

## Build & test
- `bash tools/jvmcheck.sh [fq.TestClass ...]` - fast compile + JUnit, no Gradle; use this for the test loop
- `tools/gradle.sh :app:assembleDebug` / `:app:installDebug` - always use this in the sandbox, not `./gradlew` (it handles the proxy, temp dir and in-process Kotlin)
- Toolchain (JDK 17, Gradle 8.7, kotlinc) lives in gitignored `.tools/`; `source env.sh` sets JAVA_HOME etc. outside the wrapper scripts
- A new test-time dependency needs a line in `tools/jvmcheck.deps`, or jvmcheck won't see it
- jvmcheck compiles against the `android.jar` stubs, so unit tests can't run real Android framework code
- Engine sound: render WAVs with a scratch `main` compiled by `.tools/jvmcheck/kotlinc/bin/kotlinc` against `build/jvmcheck/classes` (run with `kotlin-stdlib.jar` and `.tools/jvmcheck/*.jar` on the classpath), then `afplay` them outside the sandbox for the user to judge by ear before installing

## Gotchas
- Registered as a navigation app on purpose: only navigation apps can draw a full custom screen in Android Auto
- `installDebug`/`adb install` builds don't show in Android Auto 17.7+ (Unknown sources doesn't cover Car App Library apps). For the car, install via KingInstaller (Realme switch); see README section 2. Check with `adb logcat -d | grep CAR.VALIDATOR`. Don't retry `adb install -i com.android.vending` or `pm set-installer`: ColorOS and Android block both
- Bluetooth adapter type (Classic or BLE) is unknown, so keep both paths working
- Car install: `tools/install-car.sh --skip-tests` with run_in_background (not `&`); it waits up to 5 min for the user to tap "Install as king", then checks the installer record and restarts Android Auto
- Synth triggers (pops, turbo flutter) must work with an eased throttle: OBD readings plus the dash smoothing deliver a lift over ~0.2–0.3 s, so test with eased ramps, not instant steps
- New style: `Style` enum, `rendererFor`, the cycle test in `CarStateTest`, both style lists in README, and a `ModeSelector` exposed as `modeHit`

## Device
- `adb exec-out screencap -p > "$scratchpad/screen.png"`, then Read it - see the phone screen instead of asking the user to describe it
- Phone is 2400x1080 landscape: the 800x480 logical canvas maps as x*2.25+300, y*2.25. Tap with `adb shell input tap` one at a time and screenshot between taps; the user may be tapping the phone too
- Phone and laptop speakers roll off below ~100–300 Hz, so deep engine/turbo sounds read quieter there than in the car
- `sdkmanager` can't download inside the sandbox (even with the proxy JVM flags); ask the user to run it in a normal Terminal. The DHU (`extras;google;auto`) isn't installed yet
