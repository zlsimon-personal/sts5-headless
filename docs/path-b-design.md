# Path B Design — Real jdt.ls + STS5 extension, bridged headless

> Chosen after Path A proved bean/mapping reliability is structural (STS5's
> reconcile lifecycle won't fire without its real jdt.ls project backend —
> see `docs/sizing-probe.md`). Authoritative bridge contract below was
> reverse-engineered from the STS5 `.vsix` (`dist/extension.js`,
> `package.json`), not guessed.

## How STS5 really works (the vsix is a pure router)

`package.json`: `extensionDependencies: ["redhat.java","vscjava.vscode-maven"]`,
and `contributes.javaExtensions` loads STS5's bundles **into** Red Hat Java's
jdt.ls:

```
./jars/io.projectreactor.reactor-core.jar
./jars/org.reactivestreams.reactive-streams.jar
./jars/jdt-ls-commons.jar
./jars/jdt-ls-extension.jar
./jars/sts-gradle-tooling.jar
```

The VS Code extension forwards STS5-LS LSP requests to jdt.ls via
`vscode.commands.executeCommand("java.execute.workspaceCommand", "<delegate>", args)`.
**Authoritative request→delegate map** (from `extension.js`):

| STS5-LS LSP request          | jdt.ls delegate command          |
|------------------------------|----------------------------------|
| `sts/addClasspathListener`   | `sts.java.addClasspathListener` (arg: `callbackCommandId`) |
| `sts/removeClasspathListener`| `sts.java.removeClasspathListener` |
| `sts/javaType`               | `sts.java.type`                  |
| `sts/javadoc`                | `sts.java.javadoc`               |
| `sts/javadocHoverLink`       | `sts.java.javadocHoverLink`      |
| `sts/javaLocation`           | `sts.java.location`              |
| `sts/javaSearchTypes`        | `sts.java.search.types`          |
| `sts/javaSearchPackages`     | `sts.java.search.packages`       |
| `sts/javaSuperTypes`         | `sts.java.hierarchy.supertypes`  |
| `sts/javaSubTypes`           | `sts.java.hierarchy.subtypes`    |
| `sts/javaCodeComplete`       | `sts.java.code.completions`      |
| `sts/project/gav`            | `sts.project.gav`                |
| `sts/moveCursor`             | editor-only — harness no-ops     |

Plus: extension sends jdt.ls the command
`sts.vscode-spring-boot.enableClasspathListening` with a boolean
`serverMode === "Standard"`, and **jdt.ls must run in Standard mode** (not
LightWeight) or classpath listening is refused. The classpath flows *back*
as jdt.ls invoking the `callbackCommandId` on its client — the harness
relays that to STS5-LS's registered `onCommand` (the same callback Path A
hand-fed, now produced by jdt.ls's real m2e/reconcile pipeline).

## Target architecture

```
STS5-LS  --LSP/socket-->  HARNESS (router)  --LSP/stdio-->  jdt.ls
 (Path A launch,           - relay sts/* -> java.execute.     (+ STS5 bundles via
  standalone socket)         workspaceCommand(sts.java.*)      initializationOptions.bundles,
                           - relay callbackCommandId cmd        Standard mode, -data <ws>,
                             jdt.ls -> STS5-LS                  project import = target)
                           - drive jdt.ls project import
```

Path A's hand-built Maven classpath (`MavenClasspath`/`Classpaths`) was
**removed** (commit history) — jdt.ls owns the project model; Path A could
not drive STS5's bean reconcile (see `docs/sizing-probe.md`). The Path A
LSP-client plumbing (`HarnessMain`, socket, `initialize`) is reused for the
STS5-LS side.

## Phases (each gates the next; stop if a phase's risk realizes)

### ✅ B1 RESULT (2026-05-16) — PASS

jdt.ls **1.58.0** booted headless with the 5 STS5 bundles via
`initializationOptions.bundles`; its `.metadata/.log` shows **all** STS5
delegate commands registered: `Static Commands: [sts.java.addClasspath
Listener, sts.java.removeClasspathListener]`, plus
`sts.java.hierarchy.*`, `sts.java.code.completions`, `sts.java.javadoc*`,
`sts.java.location`, `sts.project.gav`. The jdt.core 3.44→3.46 /
jdt.ls.core version gap is a non-issue (lenient `Require-Bundle`). Kill
criterion cleared; Path B is mechanically viable.

**Design correction for B3 (important):** the vsix uses
`java.execute.workspaceCommand` because that indirection is provided by the
**Red Hat Java** VS Code extension, *not* core jdt.ls. Standalone jdt.ls
has **no** `java.execute.workspaceCommand` handler (probe returned
`-32601 No delegateCommandHandler for java.execute.workspaceCommand`).
The router must invoke the delegate **directly**:
`workspace/executeCommand { command: "sts.java.addClasspathListener",
arguments: [callbackCommandId] }` — i.e. the request→delegate table maps
`sts/<x>` → `workspace/executeCommand` with `command = sts.java.<x>`
(no wrapper).

### Phase B1 — Acquire a compatible jdt.ls  **(make-or-break)** — DONE, see result above
**Risk:** STS5's `jdt-ls-extension.jar` is built against Eclipse JDT
`org.eclipse.jdt.core-3.44.0` (Task 2 inventory). It must load into a jdt.ls
whose OSGi/JDT bundle versions are compatible, or `sts.java.*` delegate
commands never register.
- Identify the jdt.ls release matching what Red Hat Java ships for STS5
  5.1.1's era (jdt.core 3.44 ⇒ recent JDT LS milestone).
- Download standalone jdt.ls (`download.eclipse.org/jdtls/`).
- Boot it headless on the test project with the 5 STS5 bundles via
  `initializationOptions.bundles`; **success = `sts.java.addClasspathListener`
  is registered** (probe `java.execute.workspaceCommand`).
- If incompatible: this is the kill criterion → fall back to Task 6.

### Phase B2 — jdt.ls project import works headless
- Standard mode, `-data <tmp workspace>`, import the Maven project; confirm
  jdt.ls resolves the classpath (its own m2e), no LightWeight.

### Phase B3 — The router
- Second subprocess + LSP client for jdt.ls (reuse LSP4J).
- Implement the request→delegate table above; relay the
  `callbackCommandId` command jdt.ls→STS5-LS; forward project/doc lifecycle
  so jdt.ls imports + reconciles.
- Drop the Path A `sts/addClasspathListener` self-handler; STS5-LS's
  `sts/*` now route to jdt.ls.

### ✅ B4 RESULT (2026-05-16) — PASS, DETERMINISTIC

Router built (`JdtLs`, `JdtLsClient`, `HarnessLanguageClient` rewritten to
forward `sts/*`→jdt.ls, `HarnessMain` runs both subprocesses). End-to-end:
STS5-LS `sts/addClasspathListener` → jdt.ls delegate → jdt.ls imports the
Maven project (`ServiceReady`) → jdt.ls invokes the classpath callback →
relayed (`workspace/executeClientCommand`) to STS5-LS → `spring/index/
updated`. **3/3 cold-cache runs, identical** (a then-private single-module
app: `projects=1`, stable bean/mapping counts, injection points + resolved
request paths; parsed properly — the Path A `grep '"name"'` counter was
unreliable on escaped JSON). **The exact bar Path A failed — now
deterministic.** The public, reproducible re-measurement is the bundled
`sample-boot-app` fixture — see `decision-record.md`.

Path B works. Phases B1–B4 complete. Task 4 (Claude Code wiring) and
Task 6 (decision record) done; the multi-project singleton+scoped
redesign (M1–M4, `docs/multiproject-design.md`) is built on top; the
unwired Path A `MavenClasspath`/`Classpaths` were removed.

### Phase B4 — Acceptance (done — see result above)

## Risks (beyond per-phase)

- **jdt.ls bootstrap fragility** (original Risk #3, now central): data dir,
  workspace corruption, import hangs. Budget trial-and-error.
- **Version-coupling treadmill**: harness now pins a jdt.ls version matched
  to STS5's bundles; STS5 ships ~monthly. Maintenance cost is real and
  belongs in the Task 6 decision.
- **3-way LSP routing complexity**: message ordering, double doc-sync,
  lifecycle of two children + one socket. Largest code risk.
- **Scope**: sizing ≈ 1 week. B1 is the cheap make-or-break; do it first
  and checkpoint before B3.

## Kill criteria

If B1 shows no compatible jdt.ls loads `jdt-ls-extension` (OSGi/JDT version
wall), Path B is not viable without upstream changes → go straight to Task 6
decision record with that as the central finding.
