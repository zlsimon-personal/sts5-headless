<!-- Session: cea764a5-7975-4740-a012-0f46699c5d6a | Created: 2026-05-18 | Completed: 2026-05-18 | Status: COMPLETED -->

# Sample Boot fixture + re-measured evidence

**Status: COMPLETED (2026-05-18).**

## Goal

Replace the private test fixture (a personal app named 13× across 5
docs, incl. an FQCN, plus a second private project) with a checked-in
minimal Spring Boot project under `src/test/resources/`, run the real
harness against it, and record the **actually measured** numbers. Also
fix commit-hash refs the history squash invalidated. Outcome: a public,
reproducible smoke target + honest, self-consistent evidence; finishes
the private-name scrub the earlier history rewrite started.

Scope note: an **integration** fixture (jdt.ls runs a real Maven import
— needs network/`~/.m2` + the two-JVM runtime), not a unit one. Exactly
what CI SHOULD #6 needs.

## Outcome (what was actually built)

`src/test/resources/sample-boot-app/` — `spring-boot-starter-web` +
`spring-data-commons` (the lightest Spring Data flavor: Boot-managed via
`spring-data-bom 2025.1.5`, offline-cached, **no DB/JPA/Hibernate**).
Sources: `SampleApplication`, `GreetingController` (2 mappings),
`GreetingService` (constructor injection point), `GreetingRepository`
(`CrudRepository<Greeting,String>`), `Greeting`, `SampleConfig`
(`@Bean Clock`).

Design iterated on measured evidence: `@Repository` POJO → `@Component`
→ spring-data-commons `CrudRepository`, after measuring that STS5 only
enumerates Spring Data repositories, not POJO `@Repository`. Boot pinned
**4.0.6** (cached → resolves offline), not the 3.5.x first guessed.

**Measured, harness end-to-end, 3/3 cold-cache identical:** 1 project
(`sample-boot-app`), Spring Boot 4.0.6, **6 beans**, **2 mappings**
(`/api/greetings`, `/api/greetings/{name}`), injection point
`greetingService → repository: GreetingRepository`. `distinct
measurement tuples across 3 runs: 1`. (Reported Java version — 21.0.11
in this environment — tracks the local JDK jdt.ls resolves; it is
**not** part of the fixture-deterministic tuple. Dual review CR-001.)
Fixture is a **static-analysis model fixture, not a runnable app**:
`spring-data-commons` gives the `CrudRepository` type but no module to
instantiate the bean at runtime (intentional — keeps it DB-free and
deterministic; Codex review note).

## Tasks

1. **Create the sample project — done.** Final spring-data-commons form.
2. **Verify it resolves — done.** Offline `./mvnw -o compile` rc=0.
3. **Build the harness — done.** `./mvnw -DskipTests package`.
4. **Measure end-to-end — done.** curl MCP probe (SSE-aware) numbers above.
5. **Determinism check — done.** 3/3 cold runs identical (script
   `/tmp/measure.sh`, throwaway).
6. **Repoint docs — done.** `decision-record.md` (live evidence → real
   sample numbers + scope line), `harness-design.md`, `path-b-design.md`,
   `sizing-probe.md`, `multiproject-design.md` (historical docs: private
   names redacted, historically-measured numbers kept as dated records,
   FQCN + 2nd project genericised; cross-linked the reproducible
   fixture; resolved the path-b ↔ decision-record number contradiction).
7. **Fix squash-invalidated hashes — done.** Dropped embedded short
   hashes in `decision-record.md` + `multiproject-design.md`.
8. **Wire as smoke target — done.** README "Smoke test (reproducible)"
   table; `release-readiness.md` #6 (CI now unblocked) + NICE item.
   `.gitignore` needed **no change** — existing `target/`/`*.log`/
   `.project`/`.classpath`/`.settings/` rules already cover the
   fixture's generated artifacts (`git add -n` verified only the 8
   source files stage).
9. **Dual review + commit — done.** code-reviewer + Codex, Phase 4
   dispositions in the commit; this plan moved active/ → completed/.

## Notes / deviations

- TDD exception (per plan): no new harness behaviour — a fixture +
  measured-fact docs; the end-to-end measurement *is* the verification.
- One STS5 advisory diagnostic on `SampleConfig.java` (count only in the
  log); project compiles cleanly (offline javac rc=0) and indexes — a
  benign Spring advisory, documented not chased.
- Determinism held (no surprise to report); the `@Repository`-not-
  enumerated behaviour is the one informative finding, folded into the
  fixture design + a code comment.
