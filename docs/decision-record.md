<!-- Session: cea764a5-7975-4740-a012-0f46699c5d6a | Created: 2026-05-16 | Status: DECISION RECORDED -->

# Decision Record — sts5-headless prototype

**Question the prototype set out to answer:** can Spring Tools 5's language
server + embedded MCP server run **headless** (no VS Code / Eclipse /
IntelliJ) and serve real Spring intelligence to Claude Code?

**Answer: Yes — proven, and deterministic, via the jdt.ls bridge (Path B).**

> **Update (2026-05-17):** built out beyond this record — a singleton
> multi-project server: one harness serves many repos at once, a control
> endpoint adds workspaces dynamically, and an in-harness fail-closed proxy
> scopes each Claude session to its own project. The decision below stands;
> the implementation is `docs/multiproject-design.md` (M1–M4 complete).
> Path A's `MavenClasspath`/`Classpaths` were removed (superseded by jdt.ls).

## TL;DR / recommendation

1. **Adopt for personal use now.** Path B works repeatably; it closes the
   "spin up IntelliJ to ask Spring questions" gap the project was created
   for. Proceed to Task 4 (wire into Claude Code).
2. **File the upstream ask** (drafted below) for the *clean* long-term
   shape — a supported standalone project backend in STS5 so it does **not
   need a second jdt.ls process**. We now have concrete evidence it is
   feasible and what's missing.
3. **Do not invest in v1 hardening beyond Path B** (multi-module / Gradle /
   crash-recovery) until either the upstream ask lands or real usage proves
   the single-module-Maven path insufficient.

## What was proven (with evidence)

| Claim | Evidence (this session) |
|---|---|
| STS5 LS boots standalone, no IDE | `Started BootLanguageServerBootApp in ~1s`; MCP on `:50627`, 13 tools register, full `/mcp` Streamable-HTTP handshake. Risk #1 ("needs IDE handshake") **disproved**. |
| MCP startup ≠ populated data | Tools return `[]` until a project classpath is indexed. |
| Path A (harness feeds Maven classpath itself) | `getProjectList`/`getSpringBootVersion`/`getJavaVersion` reliable; `getBeanDetails`/`getRequestMappings` returned real data **once** but **nondeterministic** — STS5's bean *reconcile* doesn't fire reliably without its real jdt.ls project lifecycle. |
| jdt.ls can host STS5's extension headless (B1) | jdt.ls 1.58.0 + STS5's 5 `javaExtensions` bundles via `initializationOptions.bundles` → log registers **all** `sts.java.*` delegate commands. jdt.core 3.44(ext)→3.46(jdtls) gap is a non-issue (lenient `Require-Bundle`). |
| Path B (real jdt.ls + bridge) is **deterministic** | 3/3 cold-cache runs against the bundled `sample-boot-app` fixture, identical: **projects=1, beans=6, mappings=2** (Spring Boot 4.0.6), with the `greetingService → GreetingRepository` injection point + resolved request paths. (Reported Java version tracks the local JDK — environment-dependent, not part of the deterministic tuple.) The bar Path A failed. Reproduce: `src/test/resources/sample-boot-app/`. |

## What works, what doesn't, what it costs

**Works reliably (Path B, deterministic):** project list, Spring Boot
version, Java version, bean graph + injection points (`getBeanDetails`),
request mappings (`getRequestMappings`), and the other 13 MCP tools STS5
exposes — headless, no IDE.

**Tested surface (be honest about scope):** the bundled `sample-boot-app`
fixture — a **single-module Maven** Spring Boot 4.0.6 project — on macOS
arm64, JDK 25. (Original prototype validation used a larger private
single-module app; the fixture is the public, reproducible stand-in.)
**Untested:** multi-module reactor, Gradle, non-trivial project layouts,
crash recovery, long-running stability, file-change re-index. Out of scope
for the prototype by design.

**Costs / liabilities:**
- **Two language-server JVMs** (STS5-LS + jdt.ls) plus the thin harness.
  Far lighter than IntelliJ, not free. RAM is dominated by live object
  graphs (jdt.ls bindings/DOM, STS5 bean model) — not mmap-reducible from
  outside (analysed; an upstream concern, not a harness lever).
- **Version treadmill.** The harness pins a jdt.ls version compatible with
  STS5's bundles; STS5 ships ~monthly (single maintainer, BoykoAlex). The
  jdt.ls↔extension compatibility is currently lenient (no version ranges)
  but not guaranteed across future STS5 builds.
- **jdt.ls bootstrap fragility** — the original Risk #3, now contained
  (readiness gate + index watchdog) but inherent to driving Eclipse JDT
  headless.
- **3-way LSP routing** — bespoke; STS5/jdt.ls protocol changes could break
  the bridge silently if not for the loud watchdog.

## Decision

Of the three options the plan framed — (a) abandon, (b) invest in v1,
(c) upstream:

- **Not (a).** It works and solves a real, recurring pain (Spring intel
  without IntelliJ).
- **(c) primary.** The clean fix is upstream: STS5 already *ships* the
  Eclipse JDT bundles in its own `lib/` — it is *built to* run JDT
  in-process — but its **standalone** path delegates project/classpath to
  an external jdt.ls instead of wiring its embedded JDT. A supported
  standalone project backend would collapse this to **one process, no
  jdt.ls treadmill**. We have the evidence to make that ask concretely.
- **(b) deferred.** Single-module-Maven Path B is enough for the originating
  workflow; broaden only on demand or post-upstream.

**Action:** adopt Path B personally (Task 4), file the upstream issue,
revisit v1 only if upstream stalls and usage demands more.

## Upstream ask

The clean long-term fix is upstream, not in this wrapper. The full,
paste-ready issue for `spring-projects/spring-tools` is kept canonical in
[`upstream-issue.md`](upstream-issue.md) (single source — not duplicated
here, so the two cannot drift). In brief, three asks of increasing scope:

1. **(bug)** Null-guard the standalone `initialize` path (`semanticTokens`,
   null `executeCommandProvider`) so `-Dlanguageserver.standalone=true`
   does not NPE with no host jdt.ls.
2. **(docs/contract)** Make "bring your own jdt.ls with the
   `jdt-ls-extension` bundles via `initializationOptions.bundles`" a
   supported, documented integration.
3. **(the real fix)** A supported standalone project backend driving
   `JdtLsProjectCache` against the LS's **embedded** Eclipse JDT (already
   in the exec jar's `lib/`) + a Maven/Gradle import — one process, no
   external jdt.ls, no version treadmill.

Asks 1–2 are independently mergeable; ask 3 is the architectural one the
prototype is concrete evidence for. Closest existing tracker, sts4 #1882,
is about in-IDE MCP, not headless — a distinct ask.

## How to run it

See `README.md` / `docs/path-b-design.md`. In short:
`java -jar target/sts5-headless.jar <spring-project-dir>` →
MCP at `http://localhost:50627/mcp` (Streamable HTTP). jdt.ls is
auto-launched from `vendor/jdtls/`; STS5 LS jar + bundles from the
extracted `.vsix` under `vendor/`.

## Status of the plan

The original driving plan (kept in a private planning repo, not part of
this repo) is satisfied through Task 6. The prototype has graduated from "exploratory":
if pursued as a real tool, the plan should move into this repo's
`docs/specs/` per the global plan-lifecycle rule and a v1 SDD spec written
**only if** the upstream ask stalls and broader project support is needed.
