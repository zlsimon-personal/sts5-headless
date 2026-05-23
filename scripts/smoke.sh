#!/usr/bin/env bash
# End-to-end smoke test: boot the headless harness against the bundled
# sample-boot-app fixture and assert the deterministic project model
# Spring Tools' MCP server returns. Used by CI and runnable by hand.
#
# Asserts only the fixture-deterministic tuple (project, Boot version,
# bean set, mapping set, injection point). The reported *Java* version
# tracks whatever JDK jdt.ls resolves and is intentionally NOT asserted.
#
# Fail-loud: a transport/parse error is reported as such (never silently
# degraded into a value mismatch); any tuple mismatch or readiness
# timeout exits non-zero; the harness subprocess is always torn down.
# Requires: java, curl, jq. macOS/Linux.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SAMPLE="$ROOT/src/test/resources/sample-boot-app"
JAR="$ROOT/target/sts5-headless.jar"
BASE_PORT="${STS5_MCP_PORT:-50627}"
PROXY="http://127.0.0.1:$((BASE_PORT + 2))/mcp"
READY_BUDGET_SECS="${STS5_SMOKE_BUDGET:-240}"

say()  { printf '[smoke] %s\n' "$*" >&2; }
fail() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

for t in java curl jq; do
  command -v "$t" >/dev/null 2>&1 || fail "required tool not found: $t"
done
[ -d "$SAMPLE" ] || fail "missing fixture: $SAMPLE"
[ -f "$JAR" ]    || fail "missing $JAR — run: ./mvnw -DskipTests package"

WORKLOG="$(mktemp -t sts5-smoke-XXXXXX.log)"
HPID=""
cleanup() {
  if [ -n "$HPID" ]; then
    kill "$HPID" 2>/dev/null
    for _ in 1 2 3 4 5; do kill -0 "$HPID" 2>/dev/null || break; sleep 1; done
    kill -9 "$HPID" 2>/dev/null
    wait "$HPID" 2>/dev/null
  fi
  rm -f "$WORKLOG"
  # The harness writes .sts5-ls.log and jdt.ls writes m2e metadata into
  # the fixture (all git-ignored). Tidy them so a local run leaves the
  # tree clean. Guarded: only ever the known fixture dir, never a bare
  # or empty path.
  if [ -n "${SAMPLE:-}" ] && [ "$(basename "$SAMPLE")" = "sample-boot-app" ] && [ -d "$SAMPLE" ]; then
    rm -rf "$SAMPLE/target" "$SAMPLE/.settings" "$SAMPLE/.project" \
           "$SAMPLE/.classpath" "$SAMPLE/.sts5-ls.log" 2>/dev/null
  fi
}
trap cleanup EXIT

A='Accept: application/json, text/event-stream'
CT='Content-Type: application/json'
WS="X-STS5-Workspace: $SAMPLE"
SID=""

# POST a JSON-RPC body; return the response body as plain JSON, handling
# BOTH a plain-JSON response and SSE `data:` frames. A curl/HTTP failure
# is fatal (never an empty string that masquerades as a value miss).
mcp_raw() {
  local body resp rc
  body="$1"
  resp="$(curl -fsS -m 30 -H "$A" -H "$CT" ${SID:+-H "Mcp-Session-Id: $SID"} \
              -H "$WS" -d "$body" "$PROXY")"; rc=$?
  [ $rc -eq 0 ] || return 1
  if printf '%s\n' "$resp" | grep -q '^data:'; then
    printf '%s\n' "$resp" | sed -n 's/^data://p'
  else
    printf '%s\n' "$resp"
  fi
}

# tools/call -> the result text payload. Fatal on transport/parse error
# so a broken pipe can never be misread as a wrong value.
tool() {
  local name="$1" args="${2:-{\}}" raw txt
  raw="$(mcp_raw "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args}}")" \
    || fail "MCP transport error calling tool '$name'"
  txt="$(printf '%s\n' "$raw" | jq -er 'select(.id==9)|.result.content[0].text')" \
    || fail "tool '$name': no parseable result (got: $(printf '%s' "$raw" | head -c 240))"
  printf '%s' "$txt"
}

say "starting harness vs $SAMPLE (proxy $PROXY)"
java -jar "$JAR" "$SAMPLE" > "$WORKLOG" 2>&1 &
HPID=$!

deadline=$(( $(date +%s) + READY_BUDGET_SECS ))
time_left() { [ "$(date +%s)" -lt "$deadline" ]; }

# Phase A — wait for the Spring index to be populated (or fail loud if
# the harness dies / the budget runs out).
say "waiting for Spring index (budget ${READY_BUDGET_SECS}s)"
until grep -q 'spring/index/updated' "$WORKLOG" 2>/dev/null; do
  kill -0 "$HPID" 2>/dev/null || fail "harness exited early — log:
$(tail -20 "$WORKLOG")"
  time_left || fail "no 'spring/index/updated' within ${READY_BUDGET_SECS}s — log:
$(tail -20 "$WORKLOG")"
  sleep 3
done

# Phase B — MCP handshake once (not per poll).
for _ in 1 2 3 4 5; do
  SID="$(curl -fsS -m 8 -D - -o /dev/null -H "$A" -H "$CT" -H "$WS" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}' \
    "$PROXY" 2>/dev/null | grep -i '^mcp-session-id:' | awk '{print $2}' | tr -d '\r')"
  [ -n "$SID" ] && break
  time_left || break
  sleep 2
done
[ -n "$SID" ] || fail "MCP initialize returned no session id (proxy down?)"
curl -fsS -m 6 -H "$A" -H "$CT" -H "Mcp-Session-Id: $SID" -H "$WS" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' "$PROXY" >/dev/null 2>&1 || true

# Phase C — wait until the project is actually indexed (data available).
say "waiting for project model"
until [ "$(tool getProjectList '{}' 2>/dev/null | jq -r 'length' 2>/dev/null)" = "1" ]; do
  kill -0 "$HPID" 2>/dev/null || fail "harness exited during indexing — log:
$(tail -20 "$WORKLOG")"
  time_left || fail "project not indexed within ${READY_BUDGET_SECS}s — log:
$(tail -20 "$WORKLOG")"
  sleep 3
done

# ---- collect (fatal on any transport/parse failure) ----
proj=$(tool getProjectList '{}'                                       | jq -er '.[0].projectName')   || fail "getProjectList parse"
boot=$(tool getSpringBootVersion '{"projectName":"sample-boot-app"}'   | jq -er '"\(.major).\(.minor).\(.patch)"') || fail "getSpringBootVersion parse"
bd=$(tool getBeanDetails '{}')
beans=$(printf '%s' "$bd"  | jq -er 'length')                         || fail "getBeanDetails parse"
bnames=$(printf '%s' "$bd" | jq -er '[.[].name]|sort|join(",")')      || fail "getBeanDetails names parse"
inj=$(printf '%s' "$bd"    | jq -e 'any(.[]; .name=="greetingService" and (.injectionPoints//[]|any(.type=="com.example.sampleapp.repo.GreetingRepository")))' >/dev/null 2>&1 && echo yes || echo no)
maps=$(tool getRequestMappings '{"projectName":"sample-boot-app"}')
mcount=$(printf '%s' "$maps" | jq -er 'length')                       || fail "getRequestMappings parse"
mpaths=$(printf '%s' "$maps" | jq -er '[.[].path]|sort|join(",")')    || fail "getRequestMappings paths parse"

# ---- assert (accumulate; report every mismatch) ----
EXP_BEANS="greetingController,greetingRepository,greetingService,sampleApplication,sampleConfig,systemClock"
EXP_PATHS="/api/greetings,/api/greetings/{name}"
errs=0
check() { # label expected actual
  if [ "$2" = "$3" ]; then printf '[smoke]  ok  %-14s = %s\n' "$1" "$3" >&2
  else printf '[smoke] MISS %-14s expected=%s actual=%s\n' "$1" "$2" "$3" >&2; errs=$((errs+1)); fi
}
check project      "sample-boot-app" "$proj"
check springBoot   "4.0.6"           "$boot"
check beanCount    "6"               "$beans"
check beanNames    "$EXP_BEANS"      "$bnames"
check mappingCount "2"               "$mcount"
check mappingPaths "$EXP_PATHS"      "$mpaths"
check injection    "yes"             "$inj"

[ "$errs" -eq 0 ] || fail "$errs assertion(s) failed"
say "PASS — sample-boot-app: project, Boot 4.0.6, 6 beans, 2 mappings, injection point all match"
