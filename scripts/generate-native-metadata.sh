#!/usr/bin/env bash
#
# Regenerate the GraalVM native-image reachability metadata committed under
#   server/app/src/main/resources/META-INF/native-image/org.opengb/opengb-server/
#
# Why a script (and not just `-Pagent test`): the test suite uses Ktor's in-memory test engine,
# which never touches the CIO network selector, Bootable's boot()/log4j2 init, or Hoplite's
# config load. So we capture metadata from TWO sources, each into its OWN subdirectory under
# META-INF/native-image/ (native-image unions all subdirs at build time — this avoids the agent's
# lossy "merge into an existing bare entry" behaviour that drops log4j2 factory methods):
#
#   from-tests/ ← `-Pagent test` + metadataCopy: crypto, kotlinx.serialization, the OAuth flow,
#                 route handlers. (No real sockets — tracing socket teardown is non-deterministic,
#                 which would make the CI metadata-drift guard flaky.)
#   from-app/   ← a real boot() traced with the agent: the CIO network selector, log4j2 JSON config
#                 (plugin factory reflection), Hoplite config decoding, Bootable startup. This is
#                 what fixes the `NoSuchFieldException: readHandlerReference`, `No factory method
#                 ... AppenderRef`, and `Only instances of KClass are supported` failures.
#
# Requirements: a GraalVM for JDK 24 (so `native-image-agent` is present). mise provides it via
# GRAALVM_HOME (see mise.toml) — so inside this repo, with mise active, just run the script. To
# override, point GRAALVM_HOME or JAVA_HOME at a GraalVM 24 install yourself.
#
# Commit the regenerated META-INF/native-image files afterwards.
set -euo pipefail

cd "$(dirname "$0")/../server"

GVM="${GRAALVM_HOME:-${JAVA_HOME:-}}"
if [[ -z "$GVM" || ! -x "$GVM/bin/java" ]]; then
  echo "ERROR: no GraalVM found. GRAALVM_HOME should be set by mise (run from the repo with mise" >&2
  echo "  active, after 'mise install'), or point GRAALVM_HOME/JAVA_HOME at a GraalVM for JDK 24." >&2
  exit 1
fi
if [[ ! -f "$GVM/lib/libnative-image-agent.so" ]]; then
  echo "ERROR: $GVM is not a GraalVM (no native-image-agent). Use a GraalVM for JDK 24." >&2
  exit 1
fi

META_ROOT="$PWD/app/src/main/resources/META-INF/native-image/org.opengb"
META_APP="$META_ROOT/from-app"
PORT="${OPENGB_METADATA_PORT:-18099}"

echo "==> Using GraalVM:  $GVM"
echo "==> Metadata root:  $META_ROOT"

# A clean PATH/HOME so Gradle and the JVM still work, but stripped of the environment-module
# (Lmod) BASH_FUNC_* exports whose values contain '${...}' — Hoplite's config load chokes on
# those. (They won't exist on the Fly machine; this only matters when generating metadata in a
# polluted dev shell.)
run_clean() {
  env -i HOME="$HOME" LANG="${LANG:-C.UTF-8}" \
    JAVA_HOME="$GVM" GRAALVM_HOME="$GVM" PATH="$GVM/bin:/usr/bin:/bin" \
    "$@"
}

echo "==> [1/3] Tracing the test suite under the native-image agent"
run_clean ./gradlew --no-daemon -Pagent --rerun-tasks :app:test
run_clean ./gradlew --no-daemon :app:metadataCopy
# GraalVM 23+ emits the unified reachability-metadata.json, but metadataCopy leaves any pre-existing
# legacy split *-config.json (+ agent-extracted-predefined-classes/) in place. Prune them so
# from-tests stays a clean single-format snapshot and doesn't perpetually trip the CI drift guard.
find "$META_ROOT/from-tests" -maxdepth 1 -name '*-config.json' -delete
rm -rf "$META_ROOT/from-tests/agent-extracted-predefined-classes"

echo "==> [2/3] Building the install distribution (runtime classpath)"
run_clean ./gradlew --no-daemon :app:installDist
LIBDIR="$(echo "$PWD"/app/build/install/*/lib)"

echo "==> [3/3] Tracing the real application boot under the agent (-> from-app/)"
# Fresh output dir (not a merge) so log4j2's factory-method metadata is written in full.
rm -rf "$META_APP"
mkdir -p "$META_APP"
# The agent writes its complete config only on a CLEAN JVM exit. Bootable's stop-signal handler
# turns SIGTERM into a normal exit (code 0), which runs the agent's shutdown flush. We launch java
# DIRECTLY (not via the run_clean function) so $APP_PID is the JVM and our SIGTERM reaches it —
# backgrounding a function would leave $APP_PID pointing at a subshell while java runs on as an
# orphan holding the port. Don't use config-write-period-secs: it dumps into temp dirs that are
# only consolidated on clean exit anyway. (The signal handler's own reflection isn't captured here
# — it only runs when a signal is handled — which is why StopSignalHandler is registered by hand
# in manual/reflect-config.json.)
if curl -fsS -o /dev/null -m1 "http://127.0.0.1:$PORT/health" 2>/dev/null; then
  echo "ERROR: port $PORT is already in use — free it (or set OPENGB_METADATA_PORT) and retry." >&2
  exit 1
fi
# The default clean-env boot builds a plain HTTP client and never loads a keystore, so the mTLS
# client-auth path (KeyStore.getInstance("PKCS12").load + Ktor's addKeyStore reflecting into the
# PKCS12 keystore SPI) would go untraced and then fail at *runtime* on the Fly machine once the
# real OPENGB_CLIENTAUTH_KEYSTOREBASE64 secret is set. Boot WITH a throwaway keystore so that path
# is exercised and captured. The cert contents are irrelevant to reachability — only that the
# PKCS12 keystore is instantiated and read. See config/ClientAuthConfig.kt + http/UtilityHttpClients.kt.
KS_DIR="$(mktemp -d)"
KS_PW="metadata"
openssl req -x509 -newkey rsa:2048 -sha256 -days 1 -nodes \
  -keyout "$KS_DIR/k.key" -out "$KS_DIR/k.crt" -subj "/CN=metadata" \
  -addext "extendedKeyUsage=clientAuth" >/dev/null 2>&1
openssl pkcs12 -export -inkey "$KS_DIR/k.key" -in "$KS_DIR/k.crt" \
  -out "$KS_DIR/k.p12" -passout pass:"$KS_PW" >/dev/null 2>&1
KS_B64="$(base64 -w0 "$KS_DIR/k.p12")"
# Every live utility in utilities.conf takes its clientId/clientSecret from an optional
# ${?OPENGB_UTILITY_*} substitution (a Fly secret in production). In this clean env they'd all be
# unset, the required fields would be missing, and Hoplite would abort boot() before the server
# ever listens — leaving from-app/ with only the config-load slice of the trace. Give each one a
# placeholder; the values are irrelevant to reachability, they only have to be present.
UTILITY_ENV=()
while IFS= read -r var; do
  UTILITY_ENV+=("$var=metadata")
done < <(grep -vE '^[[:space:]]*(#|//)' app/src/main/resources/utilities.conf \
  | grep -oE '\$\{\??OPENGB_UTILITY_[A-Z0-9_]+\}' | tr -d '${?}' | sort -u)
env -i HOME="$HOME" LANG="${LANG:-C.UTF-8}" \
  JAVA_HOME="$GVM" GRAALVM_HOME="$GVM" PATH="$GVM/bin:/usr/bin:/bin" \
  OPENGB_HOST_PORT="127.0.0.1:$PORT" OPENGB_PUBLIC_BASE_URL="http://127.0.0.1:$PORT" \
  OPENGB_CLIENTAUTH_KEYSTOREBASE64="$KS_B64" OPENGB_CLIENTAUTH_KEYSTOREPASSWORD="$KS_PW" \
  "${UTILITY_ENV[@]}" \
  "$GVM/bin/java" "-agentlib:native-image-agent=config-output-dir=$META_APP" \
  -cp "$LIBDIR/*" org.opengb.AppKt >/tmp/opengb-metadata-run.log 2>&1 &
APP_PID=$!

# Wait for the listener, then exercise the routes so their handlers + logging are traced.
BOOTED=0
for _ in $(seq 1 400); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$PORT/health" 2>/dev/null; then BOOTED=1; break; fi
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 0.05
done
# A boot that dies early (e.g. a config error) still flushes a partial trace on exit, which would
# pass the emptiness check below and silently gut from-app/. Fail loudly instead.
if [[ "$BOOTED" != 1 ]]; then
  kill -9 "$APP_PID" 2>/dev/null || true
  rm -rf "$KS_DIR"
  echo "ERROR: the app never answered /health on port $PORT — last lines of its log:" >&2
  tail -n 20 /tmp/opengb-metadata-run.log | cut -c1-2000 >&2
  exit 1
fi
for path in /health / /utilities /connect/unknown/callback; do
  curl -fsS -o /dev/null "http://127.0.0.1:$PORT$path" 2>/dev/null || true
done
sleep 1  # let the agent observe everything before shutdown

kill -TERM "$APP_PID" 2>/dev/null || true
# Give the JVM up to ~15s to shut down cleanly — that clean exit is what flushes the agent config.
for _ in $(seq 1 150); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 0.1; done
# Backstop: never leave a process holding the port for the next run.
kill -9 "$APP_PID" 2>/dev/null || true
rm -rf "$KS_DIR"
if [[ ! -s "$META_APP/reachability-metadata.json" ]] || ! grep -q '"type"' "$META_APP/reachability-metadata.json"; then
  echo "ERROR: agent produced no metadata in $META_APP — did the app boot? See /tmp/opengb-metadata-run.log" >&2
  exit 1
fi

echo "==> Done. Review & commit the changes under:"
echo "    server/app/src/main/resources/META-INF/native-image/"
