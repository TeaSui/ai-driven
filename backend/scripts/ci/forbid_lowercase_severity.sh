#!/usr/bin/env bash
# Guard against regressions on the P1-P4 uppercase severity canon.
#
# Fails the build if a lowercase severity literal (e.g. "p1", 'p2')
# is re-introduced anywhere under src/. Passes cleanly otherwise.
#
# Used in CI and locally via `scripts/ci/forbid_lowercase_severity.sh`.
#
# Scope: source only. Tests intentionally exercise lowercase input to
# verify IncidentSeverity.normalize() coerces it, so tests/ is excluded.

set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# ERE alternation matches "p1"..."p4" or 'p1'..."p4" (case-sensitive).
PATTERN="\"p[1-4]\"|'p[1-4]'"

# -I skips binary files; -E uses extended regex.
if grep -RInE "$PATTERN" "$ROOT/src" 2>/dev/null; then
  echo ""
  echo "ERROR: lowercase severity literals found under src/."
  echo "The canonical form is uppercase: IncidentSeverity.P1.value (or the enum member)."
  echo "See docs/implementation/07-severity-taxonomy-migration.md."
  exit 1
fi

echo "OK: no lowercase severity literals under src/."
