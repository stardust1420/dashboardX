#!/usr/bin/env bash
# Gradle-free verification: kotlinc compile of app/src/{main,test}/java + JUnit run.
# Usage: bash tools/jvmcheck.sh [fully.qualified.TestClass ...]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source env.sh

KOTLIN_VER=2.0.20
LIB="$ROOT/.tools/jvmcheck"
OUT="$ROOT/build/jvmcheck/classes"
KOTLINC="$LIB/kotlinc/bin/kotlinc"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
mkdir -p "$LIB"

# Download with 8 parallel resumable ranges (the proxy throttles single connections).
fetch() { # url dest
  local url="$1" dest="$2" tmp="$2.part"
  local total
  total=$(curl -sIL "$url" | tr -d '\r' | awk 'tolower($1)=="content-length:"{v=$2} END{print v}')
  if [ -z "$total" ] || [ "$total" -lt 5000000 ]; then
    curl -sSLf --retry 5 -o "$tmp" "$url"
  else
    local n=8 ch=$(( (total + 7) / 8 )) i
    rm -rf "$tmp.d"; mkdir -p "$tmp.d"
    for i in $(seq 0 $((n-1))); do
      (
        s=$((i*ch)); e=$((s+ch-1)); [ $e -ge $total ] && e=$((total-1))
        for t in $(seq 1 30); do
          have=$(stat -f%z "$tmp.d/p$i" 2>/dev/null || echo 0)
          [ $((s+have)) -gt $e ] && break
          curl -sSLf --speed-time 20 --speed-limit 1000 -r $((s+have))-$e "$url" >> "$tmp.d/p$i" && break
          sleep 2
        done
      ) &
    done
    wait
    cat $(for i in $(seq 0 $((n-1))); do echo "$tmp.d/p$i"; done) > "$tmp"
    rm -rf "$tmp.d"
    [ "$(stat -f%z "$tmp")" -eq "$total" ] || { echo "incomplete download: $url" >&2; rm -f "$tmp"; exit 1; }
  fi
  mv "$tmp" "$dest"
}

if [ ! -x "$KOTLINC" ]; then
  echo "Fetching kotlin-compiler-$KOTLIN_VER"
  fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.zip" "$LIB/kotlin-compiler.zip"
  rm -rf "$LIB/kotlinc" && unzip -q "$LIB/kotlin-compiler.zip" -d "$LIB" && rm "$LIB/kotlin-compiler.zip"
fi

CP="$ANDROID_JAR"
while IFS='|' read -r kind name url; do
  case "$kind" in ''|\#*) continue ;; esac
  jar="$LIB/$name"
  if [ ! -f "$jar" ]; then
    echo "Fetching $name"
    if [ "$kind" = aar ]; then
      fetch "$url" "$LIB/$name.aar"
      unzip -p "$LIB/$name.aar" classes.jar > "$jar"
      rm "$LIB/$name.aar"
    else
      fetch "$url" "$jar"
    fi
  fi
  CP="$CP:$jar"
done < "$ROOT/tools/jvmcheck.deps"

rm -rf "$OUT"; mkdir -p "$OUT"
SRC=()
while IFS= read -r f; do SRC+=("$f"); done < <(find app/src/main/java app/src/test/java \( -name '*.kt' -o -name '*.java' \) 2>/dev/null | sort)
echo "Compiling ${#SRC[@]} files"
"$KOTLINC" -jvm-target 17 -no-stdlib -no-reflect \
  -cp "$CP:$LIB/kotlinc/lib/kotlin-stdlib.jar" -d "$OUT" "${SRC[@]}"

if [ "$#" -gt 0 ]; then
  TESTS=("$@")
else
  TESTS=()
  while IFS= read -r f; do
    c="${f#app/src/test/java/}"; c="${c%.kt}"; c="${c%.java}"; TESTS+=("${c//\//.}")
  done < <(find app/src/test/java \( -name '*Test.kt' -o -name '*Test.java' \) 2>/dev/null | sort)
fi
[ "${#TESTS[@]}" -gt 0 ] || { echo "No tests found"; exit 1; }
java -cp "$OUT:$CP:$LIB/kotlinc/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore "${TESTS[@]}"
