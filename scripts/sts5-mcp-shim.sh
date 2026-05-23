#!/usr/bin/env bash
# sts5-headless MCP stdio shim (M3: ONE singleton harness, many workspaces).
# Design: docs/multiproject-design.md.
#
# Registered as a STDIO MCP server. stdout is the MCP JSON-RPC channel and
# is owned exclusively by the bridge — ALL shim diagnostics go to stderr.
#
# Per session it: (1) ensures the single shared harness is up (starts it,
# bootstrapped with this session's project, if absent); (2) POSTs this
# session's project to the harness control endpoint so it is loaded as a
# workspace; (3) execs mcp-remote to the scoping proxy with an
# X-STS5-Workspace header so this connection is scoped to this project.
# A global idle reaper stops the whole harness when no session anywhere
# has used it for STS5_IDLE_SECS.
#
# Usage (normal):  sts5-mcp-shim.sh [--project DIR]
# Internal:        sts5-mcp-shim.sh --reaper
set -euo pipefail

log() { printf '[sts5-shim] %s\n' "$*" >&2; }
die() { log "FATAL: $*"; exit 1; }

IDLE_SECS="${STS5_IDLE_SECS:-600}"
LOCK_STALE_SECS=30
STS_PORT="${STS5_MCP_PORT:-50627}"
CONTROL_PORT=$((STS_PORT + 1))
PROXY_PORT=$((STS_PORT + 2))
# State dir is per port-set (STS5_MCP_PORT is part of the singleton's
# identity — two different ports are two different singletons, each with
# its own harness.pid/reaper/leases; same default port => one shared).
SD="${XDG_CACHE_HOME:-$HOME/.cache}/sts5-headless/$STS_PORT"
PIDFILE="$SD/harness.pid"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
JAR="$REPO_ROOT/target/sts5-headless.jar"

resolve_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"; return 0
  fi
  if [ -x "$HOME/.sdkman/candidates/java/25.0.2-amzn/bin/java" ]; then
    echo "$HOME/.sdkman/candidates/java/25.0.2-amzn/bin/java"; return 0
  fi
  command -v java || die "no java found (need JDK 25)"
}

proc_start() { ps -o lstart= -p "$1" 2>/dev/null | tr -s ' ' || true; }
is_harness_pid() {
  ps -p "$1" -o command= 2>/dev/null | grep -q 'sts5-headless\.jar'
}

# 0 iff an MCP server answers initialize on the scoping proxy. STS5/Spring
# AI requires BOTH media types in Accept; conditional-only.
proxy_alive() {
  local code
  code="$(curl -s -m 4 -o /dev/null -X POST "http://127.0.0.1:$PROXY_PORT/mcp" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"shim-health","version":"0"}}}' \
    -w '%{http_code}' 2>/dev/null || true)"
  [ "$code" = "200" ]
}

# ---------- reaper mode (global singleton) ---------------------------------
if [ "${1:-}" = "--reaper" ]; then
  last_active=$(date +%s)
  while true; do
    sleep 20
    live=0
    if [ -d "$SD/leases" ]; then
      for f in "$SD/leases"/*; do
        [ -e "$f" ] || continue
        pid="$(basename "$f")"
        if kill -0 "$pid" 2>/dev/null \
           && [ "$(proc_start "$pid")" = "$(cat "$f" 2>/dev/null || true)" ]; then
          live=$((live+1))
        else
          rm -f "$f"
        fi
      done
    fi
    if [ "$live" -gt 0 ]; then last_active=$(date +%s); continue; fi
    if [ $(( $(date +%s) - last_active )) -ge "$IDLE_SECS" ]; then
      hp="$(cat "$PIDFILE" 2>/dev/null || true)"
      if [ -n "$hp" ] && kill -0 "$hp" 2>/dev/null; then
        if is_harness_pid "$hp"; then
          log "idle ${IDLE_SECS}s, no sessions — stopping shared harness $hp"
          kill -TERM "$hp" 2>/dev/null || true
          for _ in $(seq 1 10); do kill -0 "$hp" 2>/dev/null || break; sleep 1; done
          kill -9 "$hp" 2>/dev/null || true
        else
          log "pid $hp not our harness (reused) — not killing"
        fi
      fi
      rm -f "$PIDFILE" "$SD/reaper.pid"
      exit 0
    fi
  done
fi

# ---------- normal (shim) mode ---------------------------------------------
PROJECT="${STS5_PROJECT:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT="${2:-}"; shift 2 ;;
    *) shift ;;
  esac
done
[ -n "$PROJECT" ] || PROJECT="$PWD"
[ -d "$PROJECT" ] || die "project dir does not exist: $PROJECT"
PROJECT="$(cd "$PROJECT" && pwd -P)"
[ -f "$JAR" ] || die "harness jar missing: $JAR (run ./mvnw -DskipTests package)"
mkdir -p "$SD/leases"
JAVA="$(resolve_java)"
log "project=$PROJECT  proxy=:$PROXY_PORT control=:$CONTROL_PORT state=$SD"

# Publish our lease NOW — before the lock + the blocking /ensureWorkspace —
# so the global reaper counts this attaching session as activity and can't
# tear the shared harness (all sessions!) down underneath us during a slow
# cold start / dynamic add. Lease = PID + start stamp (reuse-safe); exec
# preserves $$, the reaper prunes it when we die. (CR-004)
proc_start "$$" > "$SD/leases/$$"

# --- single-flight start of the ONE shared harness (atomic mkdir lock) -----
LOCK="$SD/lock"
release_lock() { rm -f "$LOCK/pid"; rmdir "$LOCK" 2>/dev/null || true; }
acquire_lock() {
  local waited=0 owner age
  while ! mkdir "$LOCK" 2>/dev/null; do
    owner="$(cat "$LOCK/pid" 2>/dev/null || true)"
    if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then
      :
    elif [ -z "$owner" ]; then
      age=$(( $(date +%s) - $(stat -f %m "$LOCK" 2>/dev/null || date +%s) ))
      [ "$age" -ge "$LOCK_STALE_SECS" ] || { sleep 1; waited=$((waited+1)); \
        [ "$waited" -lt 300 ] || die "lock wait timeout ($LOCK)"; continue; }
    else
      sleep 1; waited=$((waited+1))
      [ "$waited" -lt 300 ] || die "lock wait timeout ($LOCK)"; continue
    fi
    if mv "$LOCK" "$LOCK.stale.$$" 2>/dev/null; then
      rm -f "$LOCK.stale.$$/pid"; rmdir "$LOCK.stale.$$" 2>/dev/null || true
    fi
  done
  echo "$$" > "$LOCK/pid"
}

acquire_lock
trap 'release_lock' EXIT INT TERM
if proxy_alive; then
  log "shared harness already up on :$PROXY_PORT — reusing"
else
  log "cold start: shared harness (bootstrap project $PROJECT; ~15-20s)"
  # intentionally unquoted: empty => no arg at all; the value is always
  # numeric (STS_PORT passed $(()) arithmetic above), so no split hazard.
  PORT_ARG=""
  [ "$STS_PORT" = "50627" ] || PORT_ARG="-Dsts5.mcp.port=$STS_PORT"
  nohup "$JAVA" $PORT_ARG -jar "$JAR" "$PROJECT" \
    > "$SD/harness.log" 2>&1 &
  echo "$!" > "$PIDFILE"
  disown 2>/dev/null || true
  ok=""
  for _ in $(seq 1 240); do
    hp="$(cat "$PIDFILE" 2>/dev/null || true)"
    if [ -z "$hp" ] || ! kill -0 "$hp" 2>/dev/null; then
      release_lock; die "harness exited during start — see $SD/harness.log"
    fi
    if grep -q '\[ready\]' "$SD/harness.log" 2>/dev/null && proxy_alive; then
      ok=1; break; fi
    sleep 1
  done
  [ -n "$ok" ] || { release_lock; die "harness not ready in 240s — see $SD/harness.log"; }
  log "shared harness ready on :$PROXY_PORT"
fi

# Reaper singleton — inside the lock so check-and-spawn is serialised.
if ! { [ -f "$SD/reaper.pid" ] && kill -0 "$(cat "$SD/reaper.pid" 2>/dev/null || echo 0)" 2>/dev/null; }; then
  nohup "${BASH_SOURCE[0]}" --reaper > "$SD/reaper.log" 2>&1 &
  echo "$!" > "$SD/reaper.pid"
  disown 2>/dev/null || true
  log "started global idle reaper (idle=${IDLE_SECS}s)"
fi
release_lock
trap - EXIT INT TERM

# --- ensure THIS session's project is a loaded workspace -------------------
# curl -m must exceed HarnessMain.ENSURE_BUDGET_SECONDS (240) + response
# slack, so a genuine slow-index returns the harness's loud 504 TIMEOUT
# body rather than curl timing out into an empty, causeless die.
if ENS="$(curl -s -m 280 -X POST "http://127.0.0.1:$CONTROL_PORT/ensureWorkspace" \
          --data-binary "$PROJECT" -w '\n%{http_code}' 2>/dev/null)"; then
  ENS_CODE="$(printf '%s' "$ENS" | tail -1)"
  ENS_MSG="$(printf '%s' "$ENS" | sed '$d')"
  [ "$ENS_CODE" = "200" ] \
    || die "ensureWorkspace failed (HTTP $ENS_CODE): $ENS_MSG"
else
  die "ensureWorkspace: no response from control :$CONTROL_PORT (curl exit $?)\
 — harness up but control endpoint unreachable/timed out?"
fi
log "workspace ready: $ENS_MSG"

# --- scoped bridge (lease already published above) -------------------------
log "bridging stdio <-> proxy :$PROXY_PORT  (X-STS5-Workspace: $PROJECT)"
exec npx -y mcp-remote "http://127.0.0.1:$PROXY_PORT/mcp" --allow-http \
  --header "X-STS5-Workspace: $PROJECT"
