# sts5-headless

Run [Spring Tools](https://github.com/spring-projects/spring-tools)' Spring
intelligence as a headless **MCP server** — bean graph, request mappings,
classpath, Boot/Java version, component metadata — for Claude Code (or any
MCP client), **with no IDE running**.

> **Status: alpha / proof-of-concept.** It works and is deterministic on the
> tested surface (single-module Maven Spring Boot projects, macOS arm64,
> JDK 25), but the setup is manual, the platform support is narrow, and
> APIs/internals it depends on are reverse-engineered from Spring Tools and
> may change. **Unofficial — not affiliated with, endorsed by, or supported
> by Broadcom or the Spring team.** See [Limitations](#limitations) and
> [Relationship & licensing](#relationship--licensing).

## What & why

Spring Tools 5 already ships the right thing: an embedded MCP server
exposing exactly the Spring intelligence an AI agent wants. But it's only
reachable when Spring Tools is running inside an IDE (VS Code / Eclipse).
`sts5-headless` is a thin supervisor that boots the Spring Tools language
server **plus** a headless Eclipse `jdt.ls` (which Spring Tools needs for
the real project/classpath model) outside any IDE, bridges them, and
exposes the MCP endpoint to Claude Code. It complements general code tools
like Serena; it does not replace them — it adds the Spring-semantic layer
they lack.

## How it works

```
Claude session (repo A)        Claude session (repo B)
   │ stdio                        │ stdio
 sts5-mcp-shim.sh               sts5-mcp-shim.sh        (one shim per session)
   └────────────── npx mcp-remote, header X-STS5-Workspace:<path> ──────────┐
                                                                            ▼
 ┌──────────────────── ONE shared harness JVM ──────────────────────────────┐
 │  scoping proxy  ── per-session workspace scoping (fail-closed)            │
 │  control HTTP   ── POST /ensureWorkspace adds a project at runtime        │
 │  router: STS5-LS ⇄ jdt.ls  (jdt.ls owns the real m2e project model)       │
 └───────────────────────────────────────────────────────────────────────────┘
```

One long-lived harness serves **many projects at once**. Each Claude
session asks the harness to load its project (dynamic
`didChangeWorkspaceFolders`), then talks MCP through a proxy that scopes
every call to that session's workspace (injects/overrides the project for
tools that take one; filters whole-workspace tools by path). An idle reaper
stops the whole harness when nothing has used it for a while.

Design history and rationale live in [`docs/`](docs/) (start with
[`docs/decision-record.md`](docs/decision-record.md) and
[`docs/multiproject-design.md`](docs/multiproject-design.md)).

## Requirements

- **JDK 25** (current build target; Spring Tools LS & jdt.ls themselves
  need 21+ — see [Limitations](#limitations) re: lowering this).
- **macOS** for the `sts5-mcp-shim.sh` lifecycle shim (uses BSD `stat -f`;
  Linux is untested). The Java harness itself is OS-agnostic — the CI
  smoke matrix covers `ubuntu-latest` + `macos-latest` (that run is
  pending repo publish; local validation so far is macOS only).
- `node`/`npx` (the shim uses [`mcp-remote`](https://www.npmjs.com/package/mcp-remote)
  as the stdio↔HTTP bridge), `curl`, `bash`.
- Claude Code CLI (for the `claude mcp` registration).
- A Maven Spring Boot project to point it at.

## Setup (one-time): fetch the vendored runtimes

`sts5-headless` does **not** bundle Spring Tools or jdt.ls — they are
fetched locally into `vendor/` (git-ignored). One command, idempotent,
fail-loud, SHA-256-verified against the checked-in
[`third_party.lock`](third_party.lock):

```bash
scripts/bootstrap.sh
```

It downloads + verifies + extracts the exact artifacts this build was
developed and tested against (Spring Tools `.vsix` 2.1.1 / STS
5.1.1.RELEASE, Eclipse jdt.ls 1.58.0). A checksum mismatch, an unsafe
archive member, or a download/extract failure aborts loudly rather than
leaving a half-populated `vendor/`. Re-run any time — already-verified
artifacts are skipped. Requires `bash`, `curl`, `unzip`, `tar`, and
`sha256sum` **or** `shasum` (macOS/Linux).

> It contacts only the hosts in the pinned URLs (`cdn.spring.io`,
> `download.eclipse.org`). Review [`third_party.lock`](third_party.lock)
> and [`THIRD_PARTY.md`](THIRD_PARTY.md) — exact pins, SHA-256s, and
> licenses — before running.

> The harness resolves `vendor/` relative to the jar (relocated install)
> or, when run from a build tree, the repo root — see
> [`docs/release-readiness.md`](docs/release-readiness.md) for the
> resolution precedence.

## Build

```bash
./mvnw -DskipTests package    # no system Maven needed (wrapper vendored)
./mvnw test                   # 17 unit tests
```

Produces `target/sts5-headless.jar`.

## Use with Claude Code (recommended)

Register the stdio shim once — it manages everything (one shared harness,
on-demand project loading, per-session scoping, idle shutdown):

```bash
claude mcp add sts5-spring -s user -- /ABS/PATH/TO/scripts/sts5-mcp-shim.sh
claude mcp list      # -> sts5-spring ... - ✓ Connected   (harness up)
```

Then open a **new** Claude session inside any Maven Spring Boot repo and
ask Spring questions — "what beans does this project have?", "what handles
`/api/foo`?", "what Spring Boot version?". The shim:

- ensures **one shared harness** for all projects (first session cold-starts
  it, ~15–20s; later sessions reuse it instantly);
- adds the current project to the running harness on demand, so **many
  repos run simultaneously in one JVM set**;
- scopes each session to its own project, fail-closed (no cross-project
  bleed);
- idle-reaps the whole harness after `STS5_IDLE_SECS` (default 600s) with
  no active session.

Project = the directory Claude launched the server in (`$PWD`); override
with `--project /abs/dir` or `$STS5_PROJECT`. Remove with
`claude mcp remove "sts5-spring" -s user`.

### Run the harness directly (without the shim)

```bash
java -jar target/sts5-headless.jar /path/to/spring-project
#  scoped MCP:  http://localhost:50629/mcp   (Streamable HTTP)
#  add more projects at runtime:
curl -X POST http://localhost:50628/ensureWorkspace --data-binary /path/to/other-project
```

### Smoke test (reproducible)

A minimal Spring project is bundled at
`src/test/resources/sample-boot-app/` for a deterministic end-to-end
check (and for CI). It uses only `spring-boot-starter-web` +
`spring-data-commons` (no database), so the bean graph cannot vary.

> It is a **static-analysis fixture for the language server's project
> model — not a runnable application.** `spring-data-commons` supplies
> the `CrudRepository` *type* (which Spring Tools models statically) but
> no module that would instantiate the repository bean at runtime; the
> point is the indexed model, not `SpringApplication.run`.

One command — boots the harness, queries the MCP tools, asserts the
expected output, exits non-zero on any mismatch (needs `jq`; this is
exactly what CI runs):

```bash
./mvnw -DskipTests package      # if target/sts5-headless.jar is stale
scripts/smoke.sh
```

Or drive it by hand: `java -jar target/sts5-headless.jar
src/test/resources/sample-boot-app`, then call the MCP tools on the
scoped endpoint. Either way the project-model tools return, stable
across cold-cache runs (verified 3/3 identical). The bean/mapping graph
and Boot version are **fixture-deterministic**; the reported Java
version is whatever JDK the harness/jdt.ls runs (environment-dependent,
not asserted):

| tool | expected |
|---|---|
| `getProjectList` | 1 — `sample-boot-app`, Spring Boot |
| `getSpringBootVersion` | 4.0.6 |
| `getBeanDetails` | 6 beans: app, `@RestController`, `@Service`, Spring Data `CrudRepository`, `@Configuration`, `@Bean` clock |
| `getBeanDetails` (injection) | `greetingService` → `GreetingRepository` |
| `getRequestMappings` | 2 — `/api/greetings`, `/api/greetings/{name}` |

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `STS5_MCP_PORT` | `50627` | STS MCP port. Control = `+1`, scoping proxy = `+2` (the port clients use). |
| `STS5_IDLE_SECS` | `600` | Idle seconds before the shared harness is reaped. |
| `STS5_PROJECT` | `$PWD` | Project the shim loads/scopes this session to. |

## Limitations

- **Tested surface is narrow:** single-module **Maven** Spring Boot
  projects, macOS arm64, JDK 25. Linux is in the CI smoke matrix
  (`ubuntu-latest`) but that run is pending repo publish; multi-module
  and Gradle are unverified; the shim is macOS-only today.
- **JDK 25** is the current build target. The source uses no 25-only
  features and the underlying servers need only 21+; lowering the target to
  21 for broader adoption is a known, not-yet-done item.
- **`getBeanDetails`** is filtered to the session's project by source
  `location.uri`; beans without an in-project location (some
  library/inferred beans) are dropped rather than risk cross-project leak.
- Depends on reverse-engineered Spring Tools internals and a version-matched
  jdt.ls; Spring Tools ships frequently, so this can chase a moving target.
- Prototype-grade operational maturity: manual setup, no CI, no release
  artifacts yet.

## Troubleshooting

- **`claude mcp list` shows not-connected / tools error:** the harness
  isn't up or still indexing. First use cold-starts it (~15–20s); check
  `~/.cache/sts5-headless/<port>/harness.log`.
- **Stuck/zombie harness:** `pkill -f target/sts5-headless.jar` (tears down
  jdt.ls + STS-LS too).
- **Tools return empty for a project:** confirm the session's
  `X-STS5-Workspace` resolves — the shim logs `workspace ready: …` on
  stderr; a workspace must be loaded via `/ensureWorkspace` (the shim does
  this automatically).
- **Build can't find the LS jar / jdt.ls:** the `vendor/` setup step
  wasn't run, or you're not running from the repo root.

## Project layout

```
src/                      harness, scoping proxy, jdt.ls client, shim params
scripts/sts5-mcp-shim.sh  Claude-managed lifecycle shim (macOS)
docs/                     design history & decision record
vendor/                   fetched STS5 + jdt.ls runtimes (git-ignored)
```

## Relationship & licensing

This project is an **independent, unofficial** wrapper. It is not produced
by, affiliated with, or endorsed by Broadcom or the Spring team.
"Spring", "Spring Tools", and "Spring Boot" are trademarks of their
respective owners and are used here only to describe interoperability.

It does **not** redistribute Spring Tools or Eclipse JDT LS — you download
those yourself during setup. As of the tested versions, the Spring Tools
VS Code extension is published by VMware under **EPL‑1.0**, and Eclipse
JDT LS under **EPL‑2.0**; consult each project for authoritative,
current terms before redistributing anything. (Not legal advice.) A `NOTICE`/`THIRD_PARTY` manifest is a tracked release item — see
[`docs/release-readiness.md`](docs/release-readiness.md).

This wrapper's own source is licensed **EPL‑2.0** — see [LICENSE](LICENSE).
