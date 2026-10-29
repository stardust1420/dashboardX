#!/usr/bin/env bash
# Source from anywhere: source env.sh
_DX_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
_DX_JDK_DIR="$(ls -d "$_DX_ROOT"/.tools/jdk-17*/ 2>/dev/null | head -n1)"
export JAVA_HOME="${_DX_JDK_DIR%/}/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export GRADLE_USER_HOME="$_DX_ROOT/.tools/gradle-home"
export ANDROID_USER_HOME="$_DX_ROOT/.tools/android-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
unset _DX_ROOT _DX_JDK_DIR
