<!-- Ready-to-file issue for spring-projects/spring-tools. Self-contained:
     paste the section below the rule into a new GitHub issue as-is.
     Keep this free of commit hashes and any local/private paths — it is
     written for an external audience. -->

# Upstream issue (ready to file)

Canonical, paste-ready version of the ask drafted in
[`decision-record.md`](decision-record.md). Prepared from an independent,
unofficial prototype — not affiliated with Broadcom or the Spring team.

---

**Title:** Support a standalone/headless project backend for the language server (run without a host jdt.ls)

### Summary

Spring Tools ships an embedded MCP server exposing exactly the Spring
intelligence an AI coding agent wants — bean graph, request mappings,
Boot/Java version, resolved classpath, stereotypes — and the language
server already boots headless. But **headless, every MCP tool that needs
a project model returns empty**, because the standalone path delegates
the project/classpath model to an external `jdt.ls` that isn't there.

This asks for headless / agent use to be a supported path. It is split
into **three asks of increasing scope** — asks 1 and 2 are small and
independently valuable (mergeable without committing to ask 3); ask 3 is
the clean long-term fix.

### Context — what already works

```
java -Dlanguageserver.standalone=true \
     -Dlanguageserver.standalone-port=<p> \
     -Dserver.port=<mcp> \
     -jar spring-boot-language-server-<ver>-exec.jar
```

boots the LS + MCP server with no IDE. The `/mcp` Streamable-HTTP
handshake completes and all ~13 MCP tools register. The transport and
tool surface are **already headless-capable**.

### Problem

With no host `jdt.ls`:

1. `initialize` NPEs on the standalone path — `SimpleLanguageServer`'s
   server-capabilities path on `semanticTokens`, and
   `JdtLsProjectCache.initialize` on a **null `executeCommandProvider`**.
2. Past that, `JdtLsProjectCache` delegates **all** project/classpath
   discovery to a host `jdt.ls` via `sts.java.*` delegate commands. With
   no host the Spring symbol scan runs but the bean **reconcile never
   fires** (`reconciling counter: 0`, `cached dependencies: {}`), so
   `getBeanDetails` / `getRequestMappings` / `getResolvedProjectClasspath`
   and the rest stay empty.

Net: the MCP server starts and advertises tools, but the project-model
tools — the entire point for an agent — return nothing.

### Evidence it is feasible

A standalone harness that:

- runs a real `jdt.ls` with the **existing** `jdt-ls-extension` /
  `jdt-ls-commons` / related jars loaded via LSP
  `initializationOptions.bundles`, and
- routes the LS's `sts/*` requests to `jdt.ls`'s `sts.java.*` delegates,
  relaying the classpath callback back,

makes the MCP tools return **fully correct, deterministic** data — e.g.
**149 beans + 203 request mappings** with injection points and resolved
request paths, **identical across 3/3 cold-cache runs** on a
single-module Maven Spring Boot project.

Key observation: Spring Tools **already ships the Eclipse JDT / Equinox
bundles in the exec jar's own `lib/`** — it is built to run JDT
in-process. The only missing piece is that the standalone path does not
wire that **embedded** JDT as the `JdtLsProjectCache` backend; it assumes
an external one.

### Asks (increasing scope; 1 and 2 stand alone)

1. **(small — bug fix) Null-guard the standalone `initialize` path.**
   Guard `semanticTokens` and the null `executeCommandProvider` so
   `-Dlanguageserver.standalone=true` does not NPE on `initialize` with
   no host. Unblocks experimentation regardless of the rest.

2. **(small — docs/contract) Make "bring your own jdt.ls with our
   bundles" supported.** The `initializationOptions.bundles` +
   `sts/*` ↔ `sts.java.*` mechanism already works; documenting it as a
   supported integration (rather than reverse-engineered) makes
   out-of-IDE / agent consumers a first-class case at near-zero cost.

3. **(the real fix) A supported standalone project backend on the
   embedded JDT.** When `languageserver.standalone=true` (or a new flag),
   drive `JdtLsProjectCache` against the LS's **in-process** Eclipse JDT
   plus a Maven/Gradle import, so no external `jdt.ls` process is needed.
   This collapses the headless story to **one process, no second-LS
   version-compatibility treadmill**.

### Why it matters

AI coding agents (Claude Code and others) consume MCP directly. Spring
Tools already built the right capability; the only thing between it and
every agent user is the IDE-host coupling for the project model. Asks
1–2 are cheap and unblock a community-maintained path immediately; ask 3
is the clean long-term shape.

Happy to share the prototype (LSP router + `jdt.ls` bridge) as concrete
evidence and to test patches.

### Related

Closest existing tracker: **sts4 #1882** — but that is about expanding
MCP capabilities *within the IDE*, not headless / agent use. This is a
distinct ask.
