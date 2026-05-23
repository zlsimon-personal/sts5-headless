# Harness Design (Task 3)

> Written after the Task 3 standalone-boot spike. Records what the spike
> proved and the architectural decision it forces. Authoritative task list
> stays in a private planning repo (not part of this repo); this is the
> local design rationale.

## What the spike proved (empirical, 2026-05-15)

Ran the bundled exec jar directly, no IDE:

```
java -Dspring.config.location=classpath:/application.properties \
     -Dserver.port=50627 \
     -jar .../spring-boot-language-server-2.1.1-SNAPSHOT-exec.jar
```

| Observation | Result |
|-------------|--------|
| LS boots standalone | ✅ `Started BootLanguageServerBootApp in 1.1s`, no IDE |
| MCP HTTP server binds | ✅ Tomcat on `127.0.0.1:50627` |
| MCP tools register | ✅ `Registered tools: 13` |
| MCP handshake (`initialize` → `tools/list`) | ✅ works; `Mcp-Session-Id` header, `text/event-stream` framed responses, endpoint is **`POST /mcp`** (`/sse` 404 — confirms STREAMABLE) |
| 13 tools | `getBeanDetails getRequestMappings getSpringBootVersion getStereotypesList getJavaVersion findRequestMappingsByMethod findBeansByType getProjectList getLatestReleaseInformation findComponentsByStereotype getListOfComponentsAndTheirStereotypes getResolvedProjectClasspath getBeanUsageInfo` |
| Tool data **without a project loaded** | ❌ empty — `getProjectList` → `[]`, `getBeanDetails` → `[]`. Not an error; just nothing indexed. |
| LSP transport when no `-Dspring.lsp.client-port` | LS logs `Starting LS as client` → `Connected to parent using stdio` (falls back to stdio, assumes a parent that isn't there) |

**Conclusion — Risk #1 fully characterised.** The MCP server needs **no IDE
handshake to start or expose tools**. It needs a **project indexed** to return
real data. Startup ≠ populated index.

## Spike outcome — the blocker is jdt.ls, not the IDE (⛔)

After building the real harness (LSP4J client, standalone-socket transport)
and driving `initialize` against the test project, the LSP handshake
peels two server-side NPEs:

1. **`SimpleLanguageServer.getServerCapabilities:506`** — dereferenced
   `clientCapabilities.getSemanticTokens()` with no null guard. **Fixed
   harness-side** by sending a full `TextDocumentClientCapabilities` (a real
   editor always does). Surgical, legitimate.
2. **`JdtLsProjectCache.initialize:338`** — dereferenced
   `ServerCapabilities.getExecuteCommandProvider()`, which is **null**.
   **Not harness-fixable.** `JdtLsProjectCache` is the *only* project cache
   in the exec jar; STS5 delegates **all** project & classpath discovery to a
   **running jdt.ls** that has `jars/jdt-ls-extension.jar` loaded, via
   `workspace/executeCommand` (`sts.vscode-spring-boot.enableClasspath
   Listening`, `addClasspathListener`). No jdt.ls host ⇒ no command provider
   ⇒ NPE ⇒ no project model ⇒ every project-data MCP tool stays empty.

**The architecture is: STS5 LS = Spring *parser*; jdt.ls + `jdt-ls-extension`
= the project/classpath *backend*.** They are two cooperating language
servers, not one. A headless harness that wants real Spring intel must also
bootstrap jdt.ls — precisely the "jdtls bootstrapping is fragile" plan risk,
now confirmed **central, not reduced** (the Task 2 "no separate jdtls ⇒ risk
reduced" note was wrong and has been corrected in `sts5-anatomy.md`).

This is a scope decision point, not a bug to keep grinding — see the plan's
Risks + Task 6. The standalone-MCP half of the prototype's question is
answered (✅ it works); the project-intel half hits structural jdt.ls
coupling.

## Path A built — concept proven, bean retrieval is the open tail

The jdt.ls coupling was satisfied **without** jdt.ls (Path A): the harness
implements `sts/addClasspathListener`, computes the classpath via Maven, and
pushes `Classpath`/`CPE` back over `workspace/executeCommand`. Result:
`getProjectList` + `getSpringBootVersion` return real data reliably; one run
returned full real `getBeanDetails`/`getRequestMappings`; the JDT
"Missing system library" wall fell to a single `isSystem=true` jrt-fs CPE
(1032 symbols indexed). **Bean/mapping retrieval is currently
nondeterministic** — a race between classpath acceptance, the symbol scan,
and the bean reconcile. Full detail and status: `docs/sizing-probe.md`
("Path A build outcome"). The prototype's core question is answered
(headless Spring intelligence is achievable); hardening bean/mapping
reliability is the remaining, bounded engineering item.

## Architectural decision: thin launcher, NOT a Spring app

**Decision.** The harness is a **plain-Java LSP client + subprocess
supervisor**. It does *not* host an MCP server, does *not* import Spring AI,
and does *not* run the LS in-JVM.

**Why (resolves the conflict flagged in Task 2, deliberately — not blended):**
The current `pom.xml` scaffolds Spring Boot 3.4 + `spring-ai-starter-mcp-
server-webmvc`. That was a pre-investigation guess. Evidence now contradicts
it: the LS exec jar is fully self-contained (175-jar `lib/`, owns Boot 4.0.1 /
Spring 7.0.2 / Spring AI 2.0.0-M2 / embedded Tomcat / the MCP server itself).
A harness that also booted Spring AI would (a) duplicate a server the jar
already runs and (b) create an unwinnable classpath collision (harness Boot 3.4
vs LS Boot 4.0.1 in one JVM). So:

- **LS runs out-of-process** (subprocess), never embedded in-JVM. Trade-off:
  IPC + child lifecycle management. Accepted — the only sane option for a
  self-contained fat-jar; in-JVM buys nothing and breaks the classpath.
- **Harness has no Spring dependency.** Strip Boot/Spring-AI from `pom.xml`.
- **Only real dependency: LSP4J**, version-matched to the bundled
  `org.eclipse.lsp4j` / `.jsonrpc` **1.0.0**.

## Task 3 implementation steps

1. **Rewrite `pom.xml`**: drop Spring Boot parent + `spring-ai-starter-mcp-
   server-webmvc`; plain Java 25 jar; add LSP4J + `maven-shade` for a runnable
   jar.
   - **LSP4J version assumption (fail-loud):** STS5 bundles
     `org.eclipse.lsp4j 1.0.0`, which is **not on Maven Central** (Central
     tops out at 0.24.0 — 1.0.0 looks like an unpublished Eclipse build).
     Decision: depend on Central **0.24.0**. LSP4J maintains wire
     compatibility over the standard LSP JSON-RPC protocol and the harness
     only uses `initialize`/`initialized`/notifications, so client/server
     skew is a known non-issue for these methods. **Revisit only if** the
     Task 3 acceptance LSP handshake misbehaves — failure would be loud
     (init never completes), and the fallback is vendoring the bundled
     1.0.0 jars from `vendor/.../lib/` via `maven-install-plugin`.
2. **`vendor/` bootstrap**: script/Maven step to extract the exec jar + `lib/`
   from the `.vsix` into `vendor/` (gitignored) so the jar path is reproducible.
   Manual for now; automate here.
3. **`HarnessMain`** (transport corrected after the spike — the LS *listens*
   in standalone mode; it is the server, the harness is the client):
   a. pick a free port `P`;
   b. spawn the LS: `java -Dspring.config.location=classpath:/application.properties
      -Dserver.port=50627 -Dlanguageserver.standalone=true
      -Dlanguageserver.standalone-port=P -jar <exec.jar>`, redirect its
      stdout/stderr to a log file (stdio is NOT usable for LSP — logback
      shares stdout and would corrupt the JSON-RPC stream);
   c. connect a client `Socket` to `127.0.0.1:P` with retry while the LS
      boots; build an LSP4J client `Launcher` over it;
   d. send `initialize` (workspace folder = project path from argv) + `initialized`;
   e. log server capabilities and all notifications (esp. indexing progress);
   f. supervise the child; one idempotent teardown (socket + LSP4J
      listener/executor + child kill) owns cleanup, called from both the
      shutdown hook and every failure path; non-zero LS exit → non-zero
      harness exit (fail-loud). **Signal caveat:** foreground Ctrl-C
      (SIGINT) and SIGTERM both run teardown; a *backgrounded* JVM
      (`nohup`/`&`) inherits `SIG_IGN` for SIGINT (HotSpot preserves it)
      so Ctrl-C is ignored when backgrounded — stop it with SIGTERM.
      Verified: 14 unit tests green; `initialize` reaches
      `sts/addClasspathListener`; SIGTERM kills harness + child with no
      orphan.
4. **Verify**: with a real Spring project loaded, re-run the MCP `getProjectList`
   / `getBeanDetails` calls — expect non-empty. That closes Task 3 and feeds
   Task 4 (wire Claude Code to `:50627`).

TDD note: `HarnessMain` is mostly process/socket orchestration (hard to unit
test in isolation); the meaningful test is the end-to-end "project loaded →
MCP returns non-empty" check in step 4, which is the Task 3 acceptance gate.
