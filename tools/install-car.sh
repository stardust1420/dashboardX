#!/usr/bin/env bash
# One-command install for the car: test, build, copy the APK to the phone, open KingInstaller,
# then wait for you to tap "Install as king" and check Android Auto will list the app.
# Usage: tools/install-car.sh [--skip-tests] [--fresh]
#   --skip-tests  skip the JVM unit tests
#   --fresh       uninstall DashboardX first (clears its settings; needed if the installer
#                 check fails, see README section 2)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$here"
source ./env.sh

PKG=com.karan.dashboardx
KING=com.example.kinginstaller
AUTO=com.google.android.projection.gearhead
APK=app/build/outputs/apk/debug/app-debug.apk
DEST=/sdcard/Download/DashboardX.apk
WAIT_S=300

tests=1
fresh=0
for a in "$@"; do
  case "$a" in
    --skip-tests) tests=0 ;;
    --fresh) fresh=1 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
installed_at() { adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | awk -F= '/lastUpdateTime=/{print $2; exit}'; }
installer() { adb shell pm list packages -i "$PKG" 2>/dev/null | tr -d '\r' | awk -F'installer=' '/package:'"$PKG"' /{print $2; exit}'; }

step "Checking the phone"
if [[ "$(adb get-state 2>/dev/null || true)" != device ]]; then
  echo "No phone found. Plug it in, unlock it and allow USB debugging, then run this again." >&2
  exit 1
fi
if ! adb shell pm list packages "$KING" | grep -q "$KING"; then
  echo "KingInstaller ($KING) is not installed on the phone; see README section 2." >&2
  exit 1
fi

if [[ $tests == 1 ]]; then
  step "Running unit tests"
  bash tools/jvmcheck.sh
fi

step "Building the APK"
tools/gradle.sh :app:assembleDebug

step "Copying it to the phone as Download/DashboardX.apk"
adb push "$APK" "$DEST"

if [[ $fresh == 1 ]]; then
  step "Uninstalling the old DashboardX (settings are cleared; pick the adapter again afterwards)"
  adb uninstall "$PKG" || true
fi

before="$(installed_at)"
step "Opening KingInstaller on the phone"
adb shell monkey -p "$KING" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
cat <<EOF

On the phone:
  1. Make sure Oppo/Realme/OnePlus is ON (Root and Shizuku OFF).
  2. Tap "Select APK to install" and choose Download/DashboardX.apk.
  3. Tap "Install as king" (choose the default Package installer if asked).

Waiting up to $((WAIT_S / 60)) minutes for the install...
EOF

after="$before"
for ((i = 0; i < WAIT_S; i += 2)); do
  after="$(installed_at)"
  [[ -n "$after" && "$after" != "$before" ]] && break
  sleep 2
done
if [[ -z "$after" || "$after" == "$before" ]]; then
  echo "No new install seen. Finish it in KingInstaller, then check with:" >&2
  echo "  adb shell pm list packages -i $PKG" >&2
  exit 1
fi

step "Checking the installer record"
who="$(installer)"
echo "installer=$who"
if [[ "$who" != com.android.vending ]]; then
  echo "Android Auto will not list DashboardX with this installer." >&2
  echo "Run again with --fresh (uninstalls first), and check Oppo/Realme/OnePlus is ON." >&2
  exit 1
fi

step "Restarting Android Auto so it picks up the new version"
adb shell am force-stop "$AUTO"
echo
echo "Done: DashboardX is installed for the car."
