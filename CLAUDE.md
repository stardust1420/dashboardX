# DashboardX
Sideloaded Android Auto OBD dashboard (Kotlin, `com.karan.dashboardx`). User guide: README.md. Spec/plans: docs/superpowers/.

## Build & test
- `bash tools/jvmcheck.sh [fq.TestClass ...]` - fast compile + JUnit, no Gradle; use this for the test loop
- `tools/gradle.sh :app:assembleDebug` / `:app:installDebug` - always use this in the sandbox, not `./gradlew` (it handles the proxy, temp dir and in-process Kotlin)
- Toolchain (JDK 17, Gradle 8.7, kotlinc) lives in gitignored `.tools/`; `source env.sh` sets JAVA_HOME etc. outside the wrapper scripts
- A new test-time dependency needs a line in `tools/jvmcheck.deps`, or jvmcheck won't see it
- jvmcheck compiles against the `android.jar` stubs, so unit tests can't run real Android framework code

## Gotchas
- Registered as a navigation app on purpose: only navigation apps can draw a full custom screen in Android Auto
- `installDebug`/`adb install` builds don't show in Android Auto 17.7+ (Unknown sources doesn't cover Car App Library apps). For the car, install via KingInstaller (Realme switch); see README section 2. Check with `adb logcat -d | grep CAR.VALIDATOR`. Don't retry `adb install -i com.android.vending` or `pm set-installer`: ColorOS and Android block both
- Bluetooth adapter type (Classic or BLE) is unknown, so keep both paths working

## Device
- `adb exec-out screencap -p > "$scratchpad/screen.png"`, then Read it - see the phone screen instead of asking the user to describe it
- `sdkmanager` can't download inside the sandbox (even with the proxy JVM flags); ask the user to run it in a normal Terminal. The DHU (`extras;google;auto`) isn't installed yet
