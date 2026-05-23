# STS5 Distribution Anatomy

> Task 2 of the harness plan. Source artifact: `vscode-spring-boot-2.1.1-RC1.vsix`
> (STS **5.1.1.RELEASE**, built 2026-03-17), downloaded from
> `https://cdn.spring.io/spring-tools/release/vscode-extensions/vscode-spring-boot/2.1.1/`.
> The `.vsix` is a plain zip; extracted to `vendor/vsix-extracted/` (gitignored).

## Where the source lives

Open source under EPL 2.0: **`github.com/spring-projects/spring-tools`**
(formerly `spring-projects/sts4` — the old name still redirects; the plan's
"sts4 issue #1882" reference is in the same tracker). The language server
module is `headless-services/spring-boot-language-server`.

We still reverse-engineer the `.vsix` rather than build from source because
(a) Maven Central does not publish the LS jar — distribution is the `.vsix`
bundle only (plan Risk #5), and (b) the `.vsix` carries the **exact built
artifacts plus the launch contract** (how the IDE actually spawns the JVM),
which source alone does not reveal without reproducing the build. Source is
still the reference for verifying the entry class and reading MCP tool
implementations.

## VSIX layout

```
extension/
  package.json                       VS Code manifest + boot-java.ai.* settings
  dist/extension.js                  webpacked extension host (launch logic)
  language-server/
    spring-boot-language-server-2.1.1-SNAPSHOT-exec.jar   ← entry-point jar (2 MB app)
    lib/                             175 dependency jars (the real classpath)
  jars/                              jdt.ls / xml.ls extension jars (IDE-side)
  properties-support/ yaml-support/  CompletionItem grammars
```

The exec jar is **not** a shaded fat jar — it is a thin Spring Boot app jar
whose `MANIFEST.MF` `Class-Path:` enumerates all 175 `lib/*.jar`. The harness
must reproduce that classpath (run the jar with its `lib/` sibling intact, or
build the equivalent classpath ourselves).

## Entry point

`MANIFEST.MF` of `spring-boot-language-server-2.1.1-SNAPSHOT-exec.jar`:

| Key | Value |
|-----|-------|
| `Main-Class` | `org.springframework.ide.vscode.boot.app.BootLanguageServerBootApp` |
| `Spring-Boot-Version` | `4.0.1` |
| `Implementation-Version` | `2.1.1-SNAPSHOT` |
| `Build-Jdk-Spec` | `21` |

`BootLanguageServerBootApp` is confirmed (matches the name the plan's research
predicted). It is a standard Spring Boot `main`.

## Stack actually shipped (⚠ differs from this repo's CLAUDE.md)

| Component | STS5 5.1.1 ships | This repo's `CLAUDE.md` / `pom.xml` says |
|-----------|------------------|------------------------------------------|
| Spring Boot | **4.0.1** | 3.4 |
| Spring Framework | **7.0.2** | (transitive) |
| Spring AI | **2.0.0-M2** | 1.0 |
| MCP SDK | `mcp-core` **0.17.1**, `mcp-spring-webmvc` 0.17.1 | — |
| MCP transport | Spring AI `spring-ai-starter-mcp-server-webmvc` | `spring-ai-starter-mcp-server-webmvc` ✓ |
| Web server | embedded Tomcat 11.0.15 | — |
| LSP4J | `org.eclipse.lsp4j` **1.0.0** (+ `.jsonrpc`) bundled in `lib/` | to be added Task 3 |
| Java | requires **21+** (launcher hard-checks) | 25 ✓ |

**Conflict to resolve in Task 3:** the harness pom scaffolds Boot 3.4 / Spring
AI 1.0, but STS5's LS jar is self-contained on Boot 4.0.1 / Spring AI 2.0.0-M2.
Since the harness runs the LS jar **with its own bundled classpath**, the
harness's own Boot version only matters if we co-host code in the same JVM.
Decision deferred to Task 3 (likely: harness is a thin launcher, does not
import Spring AI itself — let the LS jar own its stack).

## jdtls

There is no separate jdtls *bundle* in the `.vsix`, and the LS embeds
`org.eclipse.jdt.core-3.44.0` + `ecj-3.44.0` for **parsing**. ⚠ **But the
Task 3 spike disproved the original conclusion that JDT being in-process
means jdt.ls isn't needed.** STS5 delegates **all project & classpath
discovery** to a *running jdt.ls* (Eclipse JDT Language Server) that has
STS5's `jars/jdt-ls-extension.jar` loaded, over `workspace/executeCommand`
(`sts.vscode-spring-boot.enableClasspathListening`, `addClasspathListener`,
…). `JdtLsProjectCache` is the **only** project cache in the exec jar — no
standalone Maven/Gradle project model exists. Without a jdt.ls backend,
`JdtLsProjectCache.initialize` NPEs on a null `executeCommandProvider` and
every project-data MCP tool returns empty. → **The real answer to "does
STS5 need jdt.ls headless?": YES — for any project intelligence. The Spring
symbol parser runs in-process; the project/classpath backend does not.**
See `docs/harness-design.md` → "Spike outcome".

## Launch contract (from `dist/extension.js`)

Two distinct launch paths, both ultimately `java … -jar …exec.jar`:

### LSP transport — corrected by the Task 3 spike
**The earlier reading of `extension.js` was wrong.** Authoritative source is
the LS's own `LanguageServerRunner` + `@ConfigurationProperties("languageserver")`
`LanguageServerProperties` (decompiled from `commons-language-server-*.jar`):

- Default (`languageserver.standalone=false`): LS speaks LSP over **stdio**
  to a parent process (`"Connected to parent using stdio"`). `-Dspring.lsp.
  client-port` is **not a real property** — the LS ignores it.
- `languageserver.standalone=true` + `languageserver.standalone-port=<P>`:
  the **LS itself listens** on `127.0.0.1:<P>` (`"Starting LS as standlone
  server port = {}"` — sic) and the client connects *to* it.

→ The harness uses **standalone mode**: spawn with
`-Dlanguageserver.standalone=true -Dlanguageserver.standalone-port=<P>`, then
connect a client `Socket` to `<P>`. Stdio is rejected because logback also
writes stdout — the two would interleave and corrupt the JSON-RPC stream.

### MCP server — plain Spring Boot web app, gated only by a `-D` flag
The MCP-specific options builder always adds
`-Dspring.config.location=classpath:/application.properties`, then:

```
boot-java.ai.mcp-server-enabled == true   →  -Dserver.port=<boot-java.ai.mcp-server-port>
otherwise                                 →  -Dspring.main.web-application-type=NONE
```

`boot-java.ai.mcp-server-port` default = **50627**;
`boot-java.ai.mcp-server-enabled` default = **false** (labeled "experimental").

Bundled `application.properties` (inside the exec jar):

```properties
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.stdio=false
spring.ai.mcp.server.name=spring-language-server-mcp
spring.ai.mcp.server.version=2.1.0
spring.ai.mcp.server.protocol=STREAMABLE
server.port=0
server.address=localhost
```

Other JVM args the launcher sets: `-Xmx1024m`, optional
`-Dspring.profiles.active=file-logging` + `-Dlogging.file.name=…`,
`-Dlogging.level.root=<logLevel>`. JDK 21+ enforced (`throw` if major < 21).

## Findings vs. plan assumptions

| Plan assumed | Reality | Impact |
|--------------|---------|--------|
| MCP at fixed `localhost:50627` | Port is `-Dserver.port`; jar default is `0` (random). 50627 is only the **IDE setting default**, passed in by the host. | Harness must **set `-Dserver.port=50627`** itself (or read the bound port). |
| Endpoint `…/sse` | `spring.ai.mcp.server.protocol=STREAMABLE` → Streamable-HTTP MCP, not legacy SSE. | Task 4 `curl`/Claude config targets the streamable MCP endpoint, **not** `/sse`. |
| "MCP may not activate without IDE handshake" (Risk #1) | MCP is a **standalone Spring Boot web app, on by default in the jar config**. The IDE's only involvement is choosing the port and *disabling* it via `-Dspring.main.web-application-type=NONE` unless the user opts in. No IDE-side handshake gates **startup**. | **Risk #1 substantially de-risked for startup.** Residual unknown moves to Task 3/4: do the MCP *tools* return real data without a project loaded via LSP `initialize`? Startup ≠ populated index. |
| Need to add LSP4J dependency | `org.eclipse.lsp4j` 1.0.0 already in the LS `lib/`. | Harness still needs LSP4J **on its own classpath** to construct the client side of the socket; version-match to 1.0.0. |
| May spawn jdtls | JDT embedded in-process via ECJ + equinox. | No jdtls bootstrap fragility for the standalone path (Risk #3 reduced). |

## Minimal command the harness should reproduce (hypothesis for Task 3)

```
java \
  -Dspring.config.location=classpath:/application.properties \
  -Dserver.port=50627 \
  -Dlanguageserver.standalone=true \
  -Dlanguageserver.standalone-port=<P> \
  -jar vendor/vsix-extracted/extension/language-server/spring-boot-language-server-2.1.1-SNAPSHOT-exec.jar
```

Then: the LS **listens** on `127.0.0.1:<P>`; the harness connects a client
socket to it, sends LSP `initialize` with the target project as a workspace
folder, and independently `curl`s the Streamable-HTTP MCP endpoint on `:50627`.
Whether the MCP tools need the LSP `initialize`/workspace before they return
useful Spring intel is the **first thing Task 3 must measure** (see
`docs/harness-design.md` for the result).
