#!/usr/bin/env bash
# Fail if known-stale documentation or UI strings reappear after Plan B sync.
# Usage: ./scripts/check_docs_drift.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PATTERNS=(
  '1080p 20 MB'
  '20 MB / 4K 50 MB'
  '20 MB/chunk'
  'sample every 300 ms'
  'ทุก 300 ms'
  'VideoFaceProcessor'
  'SCREEN.md.*เก่า'
  'Layout เก่า'
)

failed=0

for pattern in "${PATTERNS[@]}"; do
  if matches="$(rg -n --no-heading "$pattern" \
    docs/OPERATOR_FLOW.md docs/PIPELINE_FLOW.md docs/BUILD.md docs/SCREEN.md \
    docs/DOCS.md docs/ARCHITECTURE.md docs/PRD.md docs/PLATFORM_APIS.md \
    README.md CONTEXT.md \
    androidApp/src/main/kotlin/com/autobots/ui \
    2>/dev/null || true)"; then
    if [[ -n "$matches" ]]; then
      echo "FAIL: stale pattern '$pattern':"
      echo "$matches"
      echo
      failed=1
    fi
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo "Doc drift check failed. See CONVENTIONS.md §7."
  exit 1
fi

echo "OK: no known stale doc/UI patterns."
