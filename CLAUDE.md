# sts5-headless — contributor / agent notes

Concise orientation for humans and AI agents working in this repo. User
docs live in [`README.md`](README.md); design history and the release
plan in [`docs/`](docs/).

## What this is

A headless supervisor that runs Spring Tools 5's language server **plus** a
bundled Eclipse `jdt.ls`, bridges them, and exposes Spring Tools' embedded
MCP server (bean graph, request mappings, classpath, Boot/Java version) to
Claude Code — no IDE. One shared harness serves many projects; each MCP
session is scoped to its own workspace by an in-harness proxy. Alpha /
unofficial (not affiliated with Broadcom or the Spring team).

## Architecture (one paragraph)

`HarnessMain` supervises two subprocesses — the STS5 LS (LSP over a
dial-back socket) and `jdt.ls` (which owns the real m2e project model) —
and routes STS5's `sts/*` requests to jdt.ls `sts.java.*` delegates.
`ScopingProxy` (in-harness HTTP, port `mcpPort+2`) is the client entry
point: it enforces per-session workspace scoping fail-closed. A control
endpoint (`mcpPort+1`, `POST /ensureWorkspace`) adds projects at runtime
via `didChangeWorkspaceFolders`. `scripts/sts5-mcp-shim.sh` is the
Claude-managed stdio lifecycle shim (macOS). Full rationale:
[`docs/decision-record.md`](docs/decision-record.md),
[`docs/multiproject-design.md`](docs/multiproject-design.md).

## Stack

- Java 25 build target (no 25-only features; 21+ is the real floor —
  see `docs/release-readiness.md`).
- Maven (`./mvnw`); the harness is plain Java (LSP4J), **not** a Spring app.
- Runtimes (STS5 `.vsix` + jdt.ls) are fetched into `vendor/`
  (git-ignored) per the README setup step — not bundled.

## Working here

- Conventional commits; keep `docs/` and `docs/release-readiness.md`
  current as you change behavior.
- Before release: the four BLOCKERs in
  [`docs/release-readiness.md`](docs/release-readiness.md) gate any use
  beyond local dev. Read it before "is this ready?".
- `vendor/`, `.claude/`, `.serena/` are git-ignored local/dev state.
