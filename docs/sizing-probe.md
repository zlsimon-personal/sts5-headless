# Sizing Probe — is the jdt.ls coupling "days" or "weeks"?

> Time-boxed investigation requested after Task 3 hit structural jdt.ls
> coupling. Question: can the harness satisfy STS5's project/classpath
> contract *itself*, or must it bootstrap a real jdt.ls? And how big is that?

## What the probe established (empirically)

1. **The `initialize` NPE was harness-fixable, not structural.** STS5 only
   builds its `executeCommandProvider` when the client advertises
   `workspace.executeCommand` (`SimpleLanguageServer.hasExecuteCommandSupport`).
   Adding `ExecuteCommandCapabilities` to the harness's `initialize` →
   **`initialize` now completes**, `initialized` sent, MCP reports
   *"Embedded Spring Tools MCP server started at port: 50627"*.

2. **The coupling is exactly ONE well-defined request, not pervasive.**
   Immediately after `initialized`, STS5 sends the client a single request:
   **`sts/addClasspathListener`** (`ClasspathListenerManager.addClasspath
   Listener` ← `JdtLsProjectCache.enableClasspathListener`). With it
   unhandled, `getProjectList` is still `[]`; that is now the *only* thing
   between the harness and populated MCP data.

3. **The contract shape is known** (decompiled `ClasspathListenerManager`):
   - STS5 → client request `sts/addClasspathListener` carrying a generated
     `callback` command name.
   - Client registers a `workspace/executeCommand` for that callback
     (`client/registerCapability`).
   - Client pushes classpath data by invoking the `callback` via
     `workspace/executeCommand` back to STS5. Payload fields seen in the
     bytecode: `projectUri`, `name`, `classpath`, `projectBuild`,
     `deleted`, `javaCoreOptions`.
   - Classpath data model: `org.springframework.ide.vscode.commons.protocol.
     java.Classpath` (+ `Classpath$CPE` entries) in
     `commons-lsp-extensions-2.1.1-SNAPSHOT.jar` — a list of classpath
     entries (source roots, output dir, dependency jars, JDK).

## The two paths, sized

### Path A — harness answers `sts/addClasspathListener` itself (fake the jdt.ls bridge)
The harness implements the one contract: on `sts/addClasspathListener`,
compute the target project's classpath and push it back as a `Classpath`/
`CPE` payload. **Computing the classpath is the only hard part** — and it
has a standard answer for the common case:

- Single-module **Maven**: `mvn dependency:build-classpath` (or the Maven
  invoker/embedder) + `target/classes` + `src/main/{java,resources}` +
  detected JDK → map to `CPE[]`. **Working proof: ~1–2 days.**
  - *Demonstrated:* `mvn dependency:build-classpath
    -Dmdep.outputFile=…` on the test project → **161 dependency
    jars**, exit 0, in seconds (paths into the `~/.m2` repo). Classpath
    *computation* is therefore effectively a solved sub-problem; the 1–2
    days is the `CPE` mapping + `sts/addClasspathListener` register/push/
    respond handshake, **not** the classpath math. Gaps the flat CLI list
    does not cover (acceptable for the single-module proof, matter for
    "robust"): source-jar attachments, multi-module reactor, annotation-
    processor paths, test vs main scope split.
- Robust: multi-module, Gradle (tooling API), test vs main scopes,
  annotation processors, classpath-change watching. **~1–2 weeks.**

No jdt.ls process, no Eclipse workspace, no version-matching. The model
classes are vendored already (`commons-lsp-extensions`), so the harness can
construct `Classpath` directly with the LS's own types on its classpath.

### Path B — run real jdt.ls + `jdt-ls-extension.jar`
Download Eclipse JDT LS, load STS5's `jdt-ls-extension.jar` +
`jdt-ls-commons.jar` (13 + ~10 classes — `ReusableClasspathListenerHandler`)
as a jdt.ls bundle, and make the harness a **3-way LSP multiplexer**
(STS5 LS ↔ harness ↔ jdt.ls), forwarding `sts/addClasspathListener` to
jdt.ls and callbacks back. Reuses STS5's tested classpath code (jdt.ls'
m2e/Buildship import handles arbitrary projects) **but** adds jdt.ls
lifecycle + version-coupling fragility (plan Risk #3) and a non-trivial
multiplexer. **~1 week to a working bridge, with ongoing fragility.**

## Path A build outcome (2026-05-16)

Path A was built and run end-to-end against the test project:

- ✅ `sts/addClasspathListener` handler wired (LSP4J `@JsonRequest` on the
  client); STS5 sends `ClasspathListenerParams{callbackCommandId,batched}`.
- ✅ Maven resolves the classpath (`dependency:build-classpath`, 244 jars);
  harness pushes `Classpath`/`CPE` + `ProjectBuild` + `javaCoreOptions` via
  the callback `workspace/executeCommand`; **STS5 accepts it (`result=done`)**.
- ✅ Adding a `isSystem=true` CPE for `<jdk>/lib/jrt-fs.jar` fixed JDT
  `ASTParser: "Missing system library"`; the indexer creates **1032
  symbols** (was 0).
- ✅ `getProjectList` → the test project (Spring Boot, Java 25) and
  `getSpringBootVersion` → 4.0.4 return real data **every run**.
- ⚠️ **`getBeanDetails`/`getRequestMappings` are nondeterministic.** One
  run (cold cache) returned full real data — a `@RestController` bean
  with injection points and a resolved `[GET]` mapping. Re-runs return `[]`
  even with a cleared `~/.sts4/.symbolCache` and 1032 fresh symbols. The
  symbol scan completes but the bean/dependency reconcile that these tools
  read (`reconciling stats - counter: 0`, `cached dependencies: {}`) does
  not reliably populate. Root cause is an ordering/trigger race between
  classpath acceptance, the symbol scan, and the bean reconcile — the
  predicted "robust tail", not yet solved.

### Hardening attempt (2026-05-16) — timing hypothesis refuted

Tested the hypothesis that beans are empty because STS5's symbol scan races
ahead of the async classpath push: made `sts/addClasspathListener` push the
classpath **synchronously before acking** (STS waits on the response via
`JdtLsProjectCache`'s `Mono.timeout`). Result over **3 cold-cache runs:
1032 symbols every time, 0 beans / 0 mappings every time.** Ack timing is
**not** the cause; the change was reverted (no benefit, adds latency).

Refined diagnosis: classpath delivery, symbol scan (1032), and project
metadata are all reliable. Beans/mappings come from STS5's **reconcile
pass** (`JdtReconciler` → `SpringMetamodelIndex.updateBeans`), and that pass
does not fire in the headless single-shot flow (`reconciling counter: 0`,
`cached dependencies: {}`). `getBeanDetails` reads `SpringMetamodelIndex.
getBeansOfProject`, which `updateBeans` never populates here. This is
**structural — Path A's classpath fidelity is insufficient for STS5's bean
reconcile**, the exact condition the verdict named as the Path B trigger.
Driving it reliably needs either replicating the reconcile/project-event
lifecycle STS5 expects from jdt.ls, or Path B (real jdt.ls). Out of the
bounded debug budget; escalated as a decision, not ground further.

Operational notes (from dual review): first `mvn dependency:build-classpath`
on a cold `~/.m2` for a Spring Boot app can exceed the 180s timeout — the
mvn output is preserved at `<project>/.sts5-mvn-cp.log` on failure (deleted
on success). A classpath-push failure is now terminal-loud (stderr + `fatal`
log + non-zero exit + LS teardown) rather than a swallowed log line, so a
Maven/handshake failure is no longer indistinguishable from the bean
reconcile race.

**Net:** the concept is **proven** (real Spring intelligence flowed through
headless MCP with no IDE), and the always-on tools (project list, Boot
version, Java version) are reliable. Bean/mapping reliability is the open
hardening item — bounded, but real engineering, consistent with the
original "1–2 weeks for robust" half of the estimate.

## Verdict

**The jdt.ls coupling is "days", not "indefinite".** It collapsed from
"pervasive IDE coupling" to **a single characterized request
(`sts/addClasspathListener`) with a known payload model**. The harness can
satisfy it without jdt.ls (**Path A**). Recommended next step if work
continues: **Path A, single-module-Maven proof first (~1–2 days)** —
implement the `sts/addClasspathListener` handler, shell out to Maven for the
classpath, push a `Classpath`/`CPE` payload, and confirm `getBeanDetails`/
`getProjectList` return real data against the test project. That closes
Task 3's acceptance and proves the whole concept end-to-end. Path B is the
fallback only if Path A's classpath fidelity proves insufficient for STS5's
indexer.

Risk that remains: the exact `callback`/registration handshake ordering and
the `Classpath`/`CPE` field semantics are known by name but not yet
exercised — the 1–2 day estimate is for discovering those by running, the
same empirical method that cracked `initialize` and `addClasspathListener`.
