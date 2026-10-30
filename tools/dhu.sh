#!/usr/bin/env bash
# One-command car screen on this Mac: Android Auto's Desktop Head Unit (DHU), driven by the phone
# over USB. Waits for the phone's head unit server, forwards the port and opens the DHU window.
# Usage: tools/dhu.sh [DHU options...]   e.g. tools/dhu.sh -c config/default_720p.ini
#   Options are passed to desktop-head-unit (run it with --help to list them); paths are relative to
#   its folder.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$here/env.sh"

DHU_DIR="$ANDROID_HOME/extras/google/auto"
DHU="$DHU_DIR/desktop-head-unit"
PORT=5277
WAIT_S=120

if [[ ! -x "$DHU" ]]; then
  echo "The DHU is not installed. Install it once in a normal Terminal (the sandbox blocks the download):" >&2
  echo "  source $here/env.sh && \"\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" \"extras;google;auto\"" >&2
  exit 1
fi
if [[ "$(adb get-state 2>/dev/null || true)" != device ]]; then
  echo "No phone found. Plug it in by USB, unlock it and allow USB debugging, then run this again." >&2
  exit 1
fi

# The head unit server listens on $PORT on the phone (state 0A = LISTEN in /proc/net/tcp*).
server_up() {
  local hex
  hex=$(printf '%04X' "$PORT")
  adb shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null" | tr -d '\r' | awk -v p=":$hex" '$2 ~ p"$" && $4 == "0A" { found = 1 } END { exit !found }'
}

if ! server_up; then
  cat <<EOF
The phone's head unit server is not running. On the phone:
  1. Open Android Auto settings (Settings > Connected devices > Connection preferences > Android Auto).
  2. If there is no developer menu yet: tap Version about 10 times and accept.
  3. Tap the three-dot menu > Start head unit server.

Waiting up to $((WAIT_S / 60)) minutes for it...
EOF
  for ((i = 0; i < WAIT_S; i += 2)); do
    server_up && break
    sleep 2
  done
  if ! server_up; then
    echo "Head unit server still not running; start it on the phone and run this again." >&2
    exit 1
  fi
fi

adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null
echo "Starting the car screen. Open DashboardX from its launcher; close the window to stop."
cd "$DHU_DIR"
exec ./desktop-head-unit "$@"
