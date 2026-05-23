<!-- Session: cea764a5-7975-4740-a012-0f46699c5d6a | Created: 2026-05-17 | Status: ACTIVE -->

# Multi-project redesign — one server, many workspaces, path-scoped

Supersedes the per-project shim. Grounded in the two-project spike
(`docs/mcp-shim.md` → spike result): one STS5-LS + one jdt.ls with
multiple `workspaceFolders` at init **works** — both projects indexed,
both classpaths via the single bridge, `getProjectList`/
`getRequestMappings` correctly per-project, path↔name map clean
(`projectUri="file:<path>" ↔ name="<basename>"`). Known caveat:
`getBeanDetails` has **no `projectName` param** and returns the
whole-workspace bean set — must be post-filtered by `location.uri`.

## Architecture

```
Claude session (repo X)         Claude session (repo Y)
   │ stdio                          │ stdio
 sts5-mcp-shim.sh  X             sts5-mcp-shim.sh  Y     (thin; one singleton harness)
   │ npx mcp-remote --header X-STS5-Workspace: <pathX>   (Y likewise)
   ▼ http
 ┌───────────────── ONE long-lived harness JVM ─────────────────┐
 │  Scoping MCP proxy (JDK HttpServer)                            │
 │   - reads X-STS5-Workspace header -> session's project path    │
 │   - tools/call: inject projectName (path→name map) where the   │
 │       tool accepts it                                          │
 │   - getBeanDetails: post-filter content by location.uri        │
 │       under the session's project path                         │
 │   - passthrough Streamable HTTP (JSON + SSE) + Mcp-Session-Id  │
 │  Control endpoint (JDK HttpServer): POST /ensureWorkspace      │
 │   - add folder via didChangeWorkspaceFolders (dynamic);         │
 │     fall back to restart-with-union if dynamic add doesn't      │
 │     index within budget                                         │
 │  path↔name map: built from the classpath-relay events           │
 │       (JdtLsClient sees projectUri+name)                         │
 │            │ in-JVM                                              │
 │  STS5-LS  ◄──LSP socket──►  router  ◄──LSP stdio──►  jdt.ls      │
 │  (MCP @ :STS_PORT, internal)        (multi-root, all folders)    │
 └────────────────────────────────────────────────────────────────┘
```

The shim no longer owns a harness per project. There is **one** harness;
shims fan workspaces into it and tag each MCP connection with its path.

## Decisions

- **Identity = path** everywhere shim/proxy touch (spike confirmed
  collision-free + clean map). STS5's internal `projectName` (jdt.ls-
  derived basename) stays internal; proxy translates path→name from the
  live map.
- **Scoping is enforced in the proxy, not by trusting the caller.**
  Workspace travels as the `X-STS5-Workspace` header (mcp-remote
  `--header`). Tools with a `projectName` arg: proxy injects it. Tools
  without (`getBeanDetails`): proxy post-filters results by
  `location.uri` ⊂ workspace path. Tools that are inherently global
  (`getProjectList`): passthrough (listing all is correct).
- **Dynamic add with restart-union fallback.** `didChangeWorkspace
  Folders` is unverified (the user chose to build, not spike it first);
  so M1 attempts dynamic add and, if the new project's index doesn't
  arrive within a budget, restarts STS5-LS+jdt.ls with the union of
  active workspaces (proven init-time path). Fallback = correctness
  insurance; dynamic add = the fast path, validated at runtime.
- **One global idle reaper** stops the whole server when no shim lease
  anywhere for `STS5_IDLE_SECS`. Per-project port/reaper machinery is
  deleted.

## Phases

### ✅ M1 RESULT (2026-05-17) — dynamic add WORKS

Built: multi-folder init (promoted from spike), JDK `HttpServer` control
endpoint `POST /ensureWorkspace` on `mcpPort+1`, classpath-event-keyed
per-project readiness (`JdtLsClient.onClasspathDelivered` → latch),
`ensureWorkspace()` doing `workspace/didChangeWorkspaceFolders` to both
servers. **Verified:** start harness with ONE project; `POST
/ensureWorkspace` a second → `ready: project-B` HTTP 200; logs
show `[ws] dynamic add` → classpath relay for the new project;
`getProjectList` then lists both; the dynamically-added project's
`getRequestMappings` returns its own 51 (correctly scoped). **The
load-bearing unknown is settled: dynamic `didChangeWorkspaceFolders` on a
running standalone STS5-LS + jdt.ls imports + indexes the new project.**
Restart-with-union fallback therefore NOT needed on the happy path — keep
it as documented defensive-only (M1b), build only if a real failure is
observed. 20 unit tests green; single-project use unaffected.

### M1 — Multi-folder long-lived harness + control endpoint
- Promote the spike's `-Dsts5.extra.projects` to real config: harness
  takes ≥1 project; inits STS5-LS + jdt.ls multi-folder (proven).
- Per-project index-ready tracking. **Sub-unknown:** does
  `spring/index/updated` say *which* project? The spike logged generic
  events. Mitigation: key readiness off the classpath-relay event
  (carries `projectUri`/`name`) + a settle, or count
  index-updated == folder count. Resolve empirically in M1.
- JDK `HttpServer` control endpoint: `POST /ensureWorkspace {path}` →
  didChangeWorkspaceFolders → await that project ready → 200; on
  timeout → restart-with-union → 200; loud 5xx on hard failure.
- Maintain path↔name map from JdtLsClient classpath events.

### ✅ M2 RESULT (2026-05-17) — fail-closed scoping, dual-reviewed

Dual review found the v1 proxy fail-OPEN in several paths; rewritten
fail-closed (v2) and re-verified:
- no/blank/unknown `X-STS5-Workspace` on a `tools/call` → in-band
  JSON-RPC error (−32001), never STS's union.
- caller-supplied `projectName` is **overridden** with the session's
  resolved name (verified: hdr=project-A + arg=project-B → project-A wins).
- `getBeanDetails` shape-drift → scoped-empty (fail-closed), not union;
  path-**boundary** match (not string prefix).
- Empirical correction: Spring AI MCP **rejects** `Accept:
  application/json` alone ("Invalid Accept headers. Expected
  TEXT_EVENT_STREAM and APPLICATION_JSON") — so the doc's "force JSON"
  fallback is impossible; the proxy is SSE-aware (forwards the dual
  Accept, buffers the terminating POST-SSE, rewrites only the JSON-RPC
  *response* event, preserves progress events + framing + Mcp-Session-Id).
- tool schemas warmed via a one-shot `tools/list`.
**Verified (2 projects, per-workspace header):** project-A 149/203,
project-B 67/51 (216 union splits exactly — no cross-project bleed).
(Counts are the original private two-project M4-acceptance run; the
public, reproducible single-project evidence is the bundled
`sample-boot-app` fixture — see `decision-record.md`.)

### v1 result (superseded by the fail-closed rewrite above)

`ScopingProxy` (JDK HttpServer + java.net.http) on `mcpPort+2` fronts STS's
MCP. Reads `X-STS5-Workspace`; learns projectName-bearing tools from any
proxied `tools/list`; injects `projectName` (path→STS-name via the harness
map) for tools whose schema has it; post-filters `getBeanDetails` by
`location.uri` under the workspace; GET is a pure stream passthrough
(server→client SSE), POST responses buffered+rewritten (single bounded
messages). **Verified, two projects, per-workspace header:**
project-A → beans 149 (from the 216 union), mappings 203;
project-B → beans 67, mappings 51. 149+67=216 exact split, both match
known single-project numbers — correct scoping, no bleed. The
whole-workspace `getBeanDetails` caveat is solved.

### M2 — Scoping MCP proxy (JDK HttpServer, in-harness)
- Front STS5's MCP port. Per request: read `X-STS5-Workspace`.
- `tools/call` request rewrite: inject `projectName` (path→name) for
  tools whose schema has it.
- `getBeanDetails` response rewrite: drop content entries whose
  `location.uri` is not under the workspace path.
- Streamable-HTTP passthrough: handle JSON and SSE response bodies and
  `Mcp-Session-Id`. **Risk:** SSE body rewrite without breaking
  streaming — may force `Accept: application/json` upstream to avoid
  SSE-rewrite complexity (verify STS honors it).

### ✅ M3 + M4 RESULT (2026-05-17) — DONE, end-to-end

Shim rewritten to the singleton model (`scripts/sts5-mcp-shim.sh`):
ensures ONE shared harness (bootstrapped with the first session's
project; reuses if up — proven lock/reaper/lease patterns from the
reviewed earlier), collapsed to one global state dir, one global idle
reaper; per-project port derivation + cross-project TOCTOU deleted),
POSTs each session's project to `/ensureWorkspace` (dynamic add), execs
`mcp-remote` to the scoping proxy with `X-STS5-Workspace: <path>`.

**M4 acceptance (two sessions, two repos):** Session A (project-A) cold-
started the singleton → beans 149, mappings 203. Session B (project-B)
**reused the same harness** (dynamic add, no 2nd JVM) → beans 67,
mappings 51. `pgrep harness == 1` — one server, both projects, each
session correctly scoped, zero cross-bleed, numbers match single-project
truth. The multi-project redesign (M1–M4) is complete.

### M3 — Rewrite the shim (much simpler)
- Ensure singleton harness (start if absent; global lock). No
  per-project port/derivation/reaper.
- `POST /ensureWorkspace` with this session's path; block until ready.
- Global lease; one global idle reaper for the whole server.
- `exec npx mcp-remote <proxy-url> --header X-STS5-Workspace:<path>`.

### M4 — Acceptance
- Two shims (repo X, repo Y) concurrently: each session's
  `getBeanDetails`/`getRequestMappings` scoped to *its* project (X's
  beans only in X's session). Dynamic-add a 3rd repo into the running
  server. Deterministic across cold/warm. Clean teardown; idle reaps
  the whole server.

## Risks

- **Dynamic add (`didChangeWorkspaceFolders`) unverified** → restart-
  union fallback (M1) makes the build correct regardless; dynamic add
  is validated at M1/M4.
- **`spring/index/updated` may not name the project** → key readiness
  off the projectUri-bearing classpath event instead.
- **SSE proxy rewrite** → prefer forcing JSON responses; fall back to
  pass-through SSE unmodified for non-`getBeanDetails` calls.
- **`getBeanDetails` path filter** drops beans whose `location.uri`
  isn't under the project (library/inferred beans). Acceptable +
  documented; the alternative (whole-workspace bleed) is worse.
- Shared fate: one server crash drops all sessions. Accepted for the
  prototype (the watchdog already fails loud).

## Acceptance gate

M4 green = the per-project shim is superseded; update
`docs/mcp-shim.md`/README/decision-record and prune per-project code.
