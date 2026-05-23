# Third-party runtimes

`sts5-headless` is a wrapper. It does **not** redistribute Spring Tools or
Eclipse JDT LS. `scripts/bootstrap.sh` downloads them from upstream into
`vendor/` (git-ignored), verifies each SHA-256 against
[`third_party.lock`](third_party.lock), and refuses any archive whose
members would escape the extraction directory (absolute paths, `..`
traversal, symlink/hardlink members). The pins below are the exact
artifacts this build was developed and tested against.

| Artifact | Version | License | Source |
|---|---|---|---|
| Spring Tools VS Code extension (`.vsix`) | 2.1.1 (STS 5.1.1.RELEASE) | **EPL-1.0** — publisher `vmware` (Broadcom) | `https://cdn.spring.io/spring-tools/release/vscode-extensions/vscode-spring-boot/2.1.1/vscode-spring-boot-2.1.1-RC1.vsix` |
| Eclipse JDT Language Server | 1.58.0 (milestone `202604151538`) | **EPL-2.0** — Eclipse Foundation | `https://download.eclipse.org/jdtls/milestones/1.58.0/jdt-language-server-1.58.0-202604151538.tar.gz` |

SHA-256 (authoritative copy in `third_party.lock`):

```
sts5.vsix      3877e58e4fc8244a82d653ec61aeb0d21629ea4be09811f191d69f81c9dbdd5c
jdtls.tar.gz   2a5bbe55ec91b4325392050dc422cead3220a2459b3766be35e1fff45b4a50d9
```

## Licensing & attribution

- This wrapper's own source: **EPL-2.0** — see [LICENSE](LICENSE).
- The fetched runtimes are licensed by their respective projects (above);
  they are **not** distributed by this repository. If you ever build a
  release artifact that *bundles* them, that is redistribution — carry the
  upstream license texts/NOTICEs and re-verify obligations under the
  [EPL FAQ](https://www.eclipse.org/legal/epl-2.0/faq/). This is not legal
  advice.
- **Unofficial.** Not produced by, affiliated with, or endorsed by
  Broadcom or the Spring team. "Spring", "Spring Tools", "Spring Boot",
  and "Eclipse" are trademarks of their respective owners, used here only
  to describe interoperability (see the
  [Spring trademark guidelines](https://spring.io/trademarks)).

## Bundled in this repo (not downloaded)

- `org.eclipse.lsp4j` (EPL-2.0) — Maven dependency (see `pom.xml`).
- Apache Maven Wrapper (`mvnw`, `.mvn/`) — Apache-2.0.
- `mcp-remote` (npm) — fetched at runtime by the shim via `npx`, not
  vendored.
