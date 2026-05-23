<!-- Session: cea764a5-7975-4740-a012-0f46699c5d6a | Created: 2026-05-18 | Completed: 2026-05-18 | Status: COMPLETED -->

# CI smoke workflow (release-readiness SHOULD #6)

**Status: COMPLETED (2026-05-18).**

## Goal

GitHub Actions CI from a clean checkout: bootstrap → `mvnw test
package` → boot the harness against the bundled `sample-boot-app` and
assert the deterministic tuple. Doubles as the rot-detector for the
pinned upstream URLs in `third_party.lock`.

## Outcome

- **`scripts/smoke.sh`** — committed, fail-loud. Boots the harness vs
  `src/test/resources/sample-boot-app`, bounded readiness wait
  (`kill -0` guard so an early harness exit fails fast, not after the
  full budget), SSE-aware MCP `initialize`+`tools/call`, asserts the
  fixture-deterministic tuple and **not** the Java version (per
  dual-review CR-001 on the prior change), `trap` cleanup of the
  harness + the m2e/`.sts5-ls.log` artifacts it drops in the fixture.
  Validated locally end-to-end: 7/7 assertions PASS, exits 0.
- **`.github/workflows/ci.yml`** — matrix `ubuntu-latest` +
  `macos-latest` (`fail-fast: false`), JDK 25, `~/.m2` cached,
  `vendor/` uncached + weekly `schedule` (URL rot-detector),
  `permissions: contents: read`, `concurrency` cancel-in-progress,
  `timeout-minutes: 30`. Scripts invoked via `bash …` so a checkout
  that drops the exec bit can't break CI (the bootstrap CR-004 lesson).
- Docs: README smoke section now leads with `scripts/smoke.sh`;
  `release-readiness.md` #6 → DONE.

## Tasks

1. `scripts/smoke.sh` — **done**.
2. `.github/workflows/ci.yml` — **done**.
3. Docs (README, release-readiness #6) — **done**.
4. Local validation — **done** (PASS, exits 0).
5. Dual review + commit + plan → completed/ — **done**.

## Notes / deviations

- CI itself can't run in this environment; validation is of the exact
  script CI invokes (`scripts/smoke.sh`), run locally to a clean PASS.
- No CI badge: no git remote yet → the URL would be guessed. Add on
  publish (recorded in release-readiness #6 follow-ups, not faked).
- Actions pinned to major tags; full-SHA pinning logged as a
  non-blocking hardening follow-up.
