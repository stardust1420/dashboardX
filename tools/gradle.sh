#!/usr/bin/env bash
# Runs the project's local Gradle 8.7 (.tools/) inside the Claude Code sandbox.
# The sandbox proxy credentials change per command, so they are read from
# $HTTPS_PROXY on every run and handed to the JVM as system properties.
# Outside the sandbox (no proxy set) this is just the local gradle.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$here"
source ./env.sh

# In-process Kotlin compile and a sandbox-writable tmpdir: the Kotlin daemon
# writes marker files to the system temp dir, which the sandbox forbids.
tmp="${TMPDIR:-$here/build/tmp}"
args=(--no-daemon -Pkotlin.compiler.execution.strategy=in-process "-Djava.io.tmpdir=$tmp")
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$tmp"
proxy="${HTTPS_PROXY:-${https_proxy:-}}"
if [[ -n "$proxy" ]]; then
  rest="${proxy#*://}"
  creds=""
  if [[ "$rest" == *@* ]]; then creds="${rest%@*}"; rest="${rest##*@}"; fi
  host="${rest%%:*}"; port="${rest##*:}"; port="${port%%/*}"
  user="${creds%%:*}"; pass="${creds#*:}"
  for scheme in http https; do
    args+=("-D${scheme}.proxyHost=$host" "-D${scheme}.proxyPort=$port")
    if [[ -n "$creds" ]]; then
      args+=("-D${scheme}.proxyUser=$user" "-D${scheme}.proxyPassword=$pass")
    fi
  done
  args+=("-Dhttp.nonProxyHosts=localhost|127.0.0.1"
         "-Djdk.http.auth.tunneling.disabledSchemes="
         "-Djdk.http.auth.proxying.disabledSchemes=")
  # Proxy auth must reach the build JVM as well as the launcher.
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djdk.http.auth.tunneling.disabledSchemes= -Djdk.http.auth.proxying.disabledSchemes="
fi

exec "$here/.tools/gradle-8.7/bin/gradle" "${args[@]}" "$@"
