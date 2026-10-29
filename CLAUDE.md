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
- Bluetooth adapter type (Classic or BLE) is unknown, so keep both paths working
