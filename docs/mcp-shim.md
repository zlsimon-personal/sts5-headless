# MCP stdio shim — Claude-managed lifecycle, warm reuse, multi-project

## Problem

The harness exposes MCP over **HTTP** (`:PORT/mcp`); Claude Code does not
lifecycle-manage HTTP servers. Claude *does* manage **stdio** servers
(spawn on session start, kill on end). The harness is also heavy
(~15–20s jdt.ls import) and **one project per process on a fixed port**.

> **M3 update (2026-05-17): superseded by the singleton model.** The
> per-project-harness design below is history. The shim now ensures ONE
> shared harness (bootstrapped with the first session's project), POSTs
> each session's project to `/ensureWorkspace` (dynamic add), and execs
> `mcp-remote` to the **scoping proxy** (`mcpPort+2`) with an
> `X-STS5-Workspace: <path>` header so the connection is scoped. One
> global state dir, one global idle reaper (stops the whole harness when
> no session anywhere is active). The per-project port derivation /
> per-project reaper / cross-project port TOCTOU are all gone. See
> `docs/multiproject-design.md`.

## Design (historical — per-project model)

`scripts/sts5-mcp-shim.sh` is registered as a **stdio** MCP server. Per
invocation it:

1. **Resolves the project** — `--project DIR` arg, else `$STS5_PROJECT`,
   else `$PWD` (canonicalized). *Everything below is keyed by project*, so
   N projects ⇒ N independent warm harnesses.
2. **Derives a stable port** per project: `50627 + (cksum(path) % 2000)`,
   recorded in the project's state dir (reused across sessions; probed
   upward if taken). Passed to the harness via `-Dsts5.mcp.port` (already
   supported by `HarnessConfig`).
3. **Ensures the harness is up** for this project (atomic `mkdir` lock,
   stale-lock broken by `kill -0`): if `:port/mcp` answers `initialize`,
   reuse (warm, instant); else start it **detached** (`nohup`, survives the
   shim) and wait for `[index] spring/index/updated` (bounded; fail loud to
   stderr + non-zero exit so Claude reports the server failed).
4. **Leases**: writes `leases/$$`; an `EXIT/INT/TERM` trap removes it and
   kills the bridge child. So a lease ≈ "a live Claude session using this
   project".
5. **Idle reaper** (per-project singleton): prunes dead lease pids
   (`kill -0`); while ≥1 live lease the harness stays warm; when the last
   lease is gone for `IDLE_SECS` (default 600s) it SIGTERMs the harness
   (graceful teardown of jdt.ls+STS5-LS) and exits. → back-to-back sessions
   reuse a warm harness; only the first pays the cold start.
6. **Bridges** stdio↔HTTP via `npx -y mcp-remote http://127.0.0.1:port/mcp`
   as a child, then `wait`s (thin supervisor; Claude killing the shim fires
   the trap → lease gone → reaper eventually reaps if idle).

State dir: `${XDG_CACHE_HOME:-$HOME/.cache}/sts5-headless/<project-slug>/`
(`lock/`, `leases/`, `port`, `harness.pid`, `harness.log`, `reaper.pid`).

## Multi-project model

One harness **per project**, each its own derived port + state dir +
lease/reaper. Register **once at user scope**; the default project = the
directory Claude launches the server in (`$PWD`), so working in repo X
transparently gets X's harness; repo Y gets Y's — concurrently, each
idle-reaped on its own clock. Or pin explicitly per-repo with a
project-scoped `.mcp.json` (`-- …/sts5-mcp-shim.sh --project /abs/X`).

**Cost (honest):** K projects warm simultaneously = K × (STS5-LS + jdt.ls)
JVM pairs. The idle reaper bounds this to *recently used* projects, not
all-ever. Heavy but bounded; acceptable for personal/prototype use. A
single-process multi-project server is an upstream STS5 change
(see `docs/decision-record.md`), not a shim concern.

## Concurrency model (after dual review)

- **Lock**: atomic `mkdir`; released via `trap … EXIT INT TERM` so a
  `set -e` abort or signal can't leak it. Stale break is single-winner
  (atomic `mv` aside) and also clears a *crashed-mid-claim* empty lock dir
  via an mtime age check (`LOCK_STALE_SECS`).
- **Reaper singleton**: spawned *inside* the lock, so the check-and-write
  of `reaper.pid` is serialised — no duplicate reapers.
- **PID-reuse safe**: leases store the owner's process *start stamp*
  (`ps -o lstart=`); the reaper treats a lease live only if the PID exists
  **and** its start stamp matches. The reaper also refuses to `kill` the
  recorded harness PID unless its command line is the `sts5-headless.jar`
  (so a reused PID is never mis-killed).

## Caveats

- `mcp-remote` (npm, via npx) is an external dependency; the shim fails
  loud if the bridge can't reach the harness.
- **Cross-project port TOCTOU (known, mitigated-not-eliminated):** the
  start lock is *per project*; two different projects whose derived ports
  collide can race the `lsof`-probe → `bind`. The shim now asserts, after
  readiness, that the PID listening on the port is *its* harness and
  `die`s loudly otherwise — so the failure is loud, not a silently
  wrong-wired bridge. A fully deterministic fix needs a global port
  allocator or harness `bind(0)` + report-back (a harness change); out of
  scope for the prototype. Port space widened to 10k buckets to make
  collisions rare.
- macOS has no `flock`/`setsid`; the shim uses `mkdir`-atomic locking and
  `nohup` detachment instead.
