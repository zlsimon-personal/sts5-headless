#!/usr/bin/env bash
# Fetch + verify + extract the pinned third-party runtimes (Spring Tools
# .vsix, Eclipse jdt.ls) into vendor/. Idempotent and fail-loud: a
# checksum mismatch, an unsafe archive member, or a download/extract
# failure aborts loudly — it never leaves a half-populated vendor/ that
# "mostly works", and a partially-extracted dir is never cached as good
# (extraction is staged in a temp dir and atomically swapped in).
#
# Pins live in third_party.lock (SHA-256 verified). Re-run any time;
# already-good artifacts are skipped. Requires: bash, curl, unzip, tar,
# and sha256sum OR shasum. macOS/Linux.
#
# Network: contacts only the hosts in the pinned URLs in third_party.lock
# (currently cdn.spring.io and download.eclipse.org). Review that file and
# THIRD_PARTY.md before running.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
LOCK="$ROOT/third_party.lock"
VENDOR="$ROOT/vendor"
say() { printf '[bootstrap] %s\n' "$*" >&2; }
die() { printf '[bootstrap] FATAL: %s\n' "$*" >&2; exit 1; }

[ -f "$LOCK" ] || die "missing $LOCK"
for t in curl unzip tar; do
  command -v "$t" >/dev/null 2>&1 || die "required tool not found: $t"
done
# SHA-256: GNU coreutils `sha256sum` or Perl `shasum` — either is fine.
if command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
  sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  die "need sha256sum or shasum to verify checksums (neither found)"
fi

mkdir -p "$VENDOR"
# All temp files/dirs live under one staging root, cleaned on any exit
# (success, failure, or interrupt). Same filesystem as vendor/ so the
# final dir-rename is atomic.
TMPROOT="$(mktemp -d "$VENDOR/.bootstrap.XXXXXX")"
trap 'rm -rf "$TMPROOT"' EXIT

# Reject archive members that would escape the extraction dir: absolute
# paths, parent-dir traversal, and (for tar) symlink/hardlink members.
# Info-ZIP unzip already refuses `../` and strips leading `/`, but we
# check names explicitly for both formats rather than trust tool version
# behavior. The pinned artifacts are clean today (verified); this guards
# against a future pin pointing at a hostile-but-correctly-hashed file.
audit_archive() {
  local name="$1" type="$2" file="$3" names m
  case "$type" in
    zip)    names="$(unzip -Z1 -- "$file")" || die "$name: cannot list zip members" ;;
    tar.gz) names="$(tar -tzf "$file")"     || die "$name: cannot list tar members"
            local types
            types="$(tar -tvzf "$file" | awk '{print substr($1,1,1)}' | sort -u)" \
              || die "$name: cannot inspect tar member types"
            case "$types" in
              *l*|*h*) die "$name: archive contains a link member (refusing to extract)" ;;
            esac ;;
    *)      die "$name: unknown archive type '$type'" ;;
  esac
  while IFS= read -r m; do
    [ -n "$m" ] || continue
    case "$m" in
      /*|../*|*/../*|*/..|..) die "$name: refusing unsafe archive member path: $m" ;;
    esac
  done <<EOF
$names
EOF
}

while IFS='|' read -r name url sha archive localfile dest || [ -n "${name:-}" ]; do
  case "$name" in ''|\#*) continue ;; esac      # skip blanks/comments
  [ -n "$url" ] && [ -n "$sha" ] && [ -n "$archive" ] \
    && [ -n "$localfile" ] && [ -n "$dest" ] \
    || die "malformed lock entry for '$name' (need name|url|sha256|archive|local|dest)"
  archive_path="$VENDOR/$localfile"
  dest_path="$VENDOR/$dest"

  # 1. ensure the archive is present and matches the pinned hash
  if [ -f "$archive_path" ] && [ "$(sha256 "$archive_path")" = "$sha" ]; then
    say "$name: archive present & verified ($localfile)"
  else
    say "$name: downloading $url"
    tmp="$(mktemp "$TMPROOT/$localfile.part.XXXXXX")"
    curl --proto '=https' -fSL --retry 3 --retry-all-errors --retry-connrefused \
      -o "$tmp" "$url" || die "$name: download failed ($url)"
    got="$(sha256 "$tmp")"
    if [ "$got" != "$sha" ]; then
      die "$name: SHA-256 mismatch for $url
  expected $sha
  got      $got
  size     $(wc -c < "$tmp" | tr -d ' ') bytes
(a tiny size usually means an HTML error/redirect page slipped past curl.
upstream artifact changed or tampered — update third_party.lock only after
verifying the new artifact, or pin the version you trust)"
    fi
    mv -f "$tmp" "$archive_path"
    say "$name: downloaded & verified"
  fi

  # 2. extract idempotently. A non-empty dest is only ever produced by a
  #    complete extraction (staged in a temp dir, atomically swapped),
  #    so "non-empty -> already extracted" cannot cache a partial tree.
  if [ -d "$dest_path" ] && [ -n "$(ls -A "$dest_path" 2>/dev/null || true)" ]; then
    say "$name: already extracted -> vendor/$dest"
    continue
  fi
  audit_archive "$name" "$archive" "$archive_path"
  stage="$(mktemp -d "$TMPROOT/$dest.stage.XXXXXX")"
  case "$archive" in
    zip)    unzip -q -o "$archive_path" -d "$stage" || die "$name: unzip failed" ;;
    tar.gz) tar --no-same-owner -xzf "$archive_path" -C "$stage" || die "$name: tar failed" ;;
  esac
  rm -rf "$dest_path"
  mv -f "$stage" "$dest_path"
  say "$name: extracted -> vendor/$dest"

  # 3. fail loud if the pin produced nothing usable
  [ -n "$(ls -A "$dest_path" 2>/dev/null || true)" ] \
    || die "$name: extraction left vendor/$dest empty (corrupt archive?)"
done < "$LOCK"

say "vendor/ ready. Build: ./mvnw -DskipTests package"
