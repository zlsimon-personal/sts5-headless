<!-- Session: cea764a5-7975-4740-a012-0f46699c5d6a | Created: 2026-05-17 | Status: ACTIVE -->

# Release-readiness assessment

Dual evaluation (Claude + Codex, independent, reconciled — no
contradictions; Codex's findings were a superset and are merged in). The
question: what to **add / remove / change** so this repo is fit to publish.
README has been rewritten to be honest and self-consistent; the items
below are the remaining work. Nothing here is started — it's the plan.

## BLOCKER — must fix before any public release

1. **Fresh clone cannot build/run. — DONE.** `scripts/bootstrap.sh` +
   checked-in `third_party.lock` (pipe-delimited: name|url|sha256|archive
   |local|dest) fetch+verify+extract the STS5 `.vsix` and jdt.ls into
   `vendor/`. `set -euo pipefail`, fail-loud on SHA-256 mismatch /
   download / extract (never a half-populated `vendor/`), idempotent
   (present+verified archives skip re-download; non-empty dest skips
   re-extract), required-tool preflight. Verified: re-extract-from-present
   -archives path restores `vendor/` (STS LS jar + jdt.ls launcher
   present), then a second run is a pure no-op. README setup section and
   `THIRD_PARTY.md` both point at it. (True cold-download path is the same
   `curl --retry 3` + the SHA-256 verify that was exercised on the present
   archives; CI #6 will cover the literal clean-network run.)
2. **Jar relocatability. — DONE.** New `Vendor` resolves the vendor root:
   `-Dsts5.vendor.dir` (validated, loud if bad) → vendor beside the jar
   (relocated install) → vendor at the jar's grandparent (in-repo
   `target/`) → cwd `./vendor` only when run from classes (tests/IDE); a
   packaged jar with no colocated vendor fails **loud**, never silent cwd.
   Memoized, resolved at use-time (no ExceptionInInitializerError).
   Verified: jar run by abs path from `/tmp` reaches `[ready]`; 7 Vendor
   unit tests cover every branch. (Dual-reviewed; the first cut only
   handled the in-repo layout + silently fell back — both fixed.)
3. **Private / internal leaks. — DONE.** Was: `pom.xml` comment + `CLAUDE.md`
   (×3) + `.claude/HANDOFF.md`/`settings.local.json`/`agent-memory` embedded
   a private sibling-repo plan path and an absolute home-dir path. Fixed:
   `.claude/` untracked + git-ignored (kept locally for dev), `pom.xml`
   private line removed, `CLAUDE.md` rewritten as accurate public
   contributor notes (no private paths), README scrubbed. `.serena/`
   already git-ignored. Tracked-tree leak scan now clean.
4. **Licensing/attribution accuracy. — DONE.** The old README/LICENSE
   claimed "EPL-2.0 matches upstream STS5" — **inaccurate**: the tested
   STS5 vsix (`vendor/vsix-extracted/extension/package.json`) is
   `publisher: vmware`, `license: EPL-1.0`; jdt.ls is EPL-2.0. README is
   corrected and `THIRD_PARTY.md` now lists each fetched artifact (name,
   source URL, version, SHA-256, license), states wrapper = EPL-2.0,
   carries the redistribution caveat (carry upstream license texts if a
   release ever ships the binaries) and the explicit "unofficial — not
   affiliated with Broadcom/the Spring team" + trademark disclaimer.
   `third_party.lock` header cross-references it. (Not legal advice;
   verify with the EPL FAQ + Spring trademark guidelines.)

## SHOULD — before calling it more than alpha

5. **Lower the build target from Java 25 → 21.** `pom.xml:25`
   `maven.compiler.release=25`, mirrored in `scripts/sts5-mcp-shim.sh` and
   README. No 25-only language features are used; STS5-LS/jdt.ls need only
   21+. 25 is a real adoption barrier. (Also drive runtime version from
   build metadata instead of the hardcoded `0.0.1` in
   `HarnessMain` `clientInfo` / `pom.xml` `0.0.1-SNAPSHOT`; tag an
   `alpha`/`beta`.)
6. **CI from a clean checkout. — DONE & GREEN.** `.github/workflows/ci.yml`:
   matrix `ubuntu-latest` + `macos-latest` (`fail-fast: false`), JDK 25,
   `bash scripts/bootstrap.sh` → `./mvnw -B -ntp clean test package` →
   `bash scripts/smoke.sh`. The smoke script (committed, also the
   documented manual path) boots the harness against
   `src/test/resources/sample-boot-app/` and asserts the
   fixture-deterministic tuple (project, Boot 4.0.6, 6 named beans, 2
   mapping paths, injection point — **not** Java version, per CR-001),
   fail-loud non-zero on mismatch or readiness timeout. **First-run
   proof:** success on both runners — ubuntu-latest 54s, macos-latest
   46s; 24/24 unit tests pass; smoke 7/7 assertions match locally
   ([run 26319168502](https://github.com/zlsimon-personal/sts5-headless/actions/runs/26319168502)).
   `vendor/` intentionally **not** cached + a weekly `schedule` so it
   stays the rot-detector for the pinned upstream URLs; `~/.m2` cached.
   Read-only `permissions`, job `timeout-minutes` backstop. Follow-ups
   (not blocking): SHA-pin the actions; bump action versions when
   upstream tags v5 (the v4 actions currently warn about Node.js 20
   deprecation — forced to Node 24 on 2026-06-02, removed 2026-09-16);
   CI badge.
7. **`docs/` reorganization.** Keep `decision-record.md` +
   `multiproject-design.md` (current architecture) on the main path; move
   `harness-design.md`, `path-b-design.md`, `sizing-probe.md`,
   `sts5-anatomy.md`, `mcp-shim.md` under `docs/history/` (valuable
   provenance, but they read as internal task history) with a one-line
   `docs/README.md` index.
8. **Add `CONTRIBUTING.md` + `SECURITY.md`** (and `CHANGELOG.md` once a
   tag is cut). Security/contrib matter more immediately than the changelog.
9. **Don't write logs into the user's project.** `HarnessMain` writes
   `.sts5-ls.log` into the target Spring repo; use the per-port state dir
   (`~/.cache/sts5-headless/<port>/`) or a configurable path.
10. **Platform story for the shim.** `sts5-mcp-shim.sh` uses BSD `stat -f`
    and assumes `curl/npx/nohup/seq`. Either make+test Linux support or
    state "shim: macOS only (first release)" prominently (README does).

## NICE

- `bootstrap.lock.json` / `third_party.lock` for machine-readable pins
  (subsumes part of #1).
- A copy-paste Claude Code example without `/ABS/PATH` placeholders.
- Pre-publish secret scan (`gitleaks`/`trufflehog`). A regex pass (both
  evaluators) found **no credentials** — the exposure is path/tooling
  leakage (#3), not secrets.
- Root `.sts5-ls.log`, `.project`, `.classpath`, `.settings/` are
  local-tool artifacts — **verified** none are tracked: the bundled
  `sample-boot-app` fixture generates exactly these under it when the
  harness runs, and `git add -n` confirms only its 8 checked-in files
  stage (`pom.xml`, 6 Java sources, `application.properties`); existing
  `.gitignore` patterns cover the generated rest.

## Verdict

Solid, working core. **All four BLOCKERs are now closed**: fresh-clone
bootstrap (#1), jar relocatability (#2), private-path scrub (#3), and
licensing/NOTICE accuracy (#4). What remains is **SHOULD/NICE** tier
(Java 25→21, CI from clean checkout, `docs/` reorg, CONTRIBUTING/SECURITY,
logs-out-of-user-repo, shim platform story) — none gate an alpha tag, but
CI (#6) is the highest-value next step since it is the rot-detector for
the pinned URLs in `third_party.lock` and the only true clean-network
test of the bootstrap.
