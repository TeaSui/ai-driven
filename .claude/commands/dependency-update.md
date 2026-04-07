# Dependency Update

Audit and update project dependencies safely.

## Steps

1. **Discover** — identify package manager (npm, pip, go mod, pub, gradle)
2. **Audit** — run security audit (`npm audit`, `pip-audit`, `govulncheck`, etc.)
3. **Categorize** — split into: security fixes (P0), major updates (P1), minor/patch (P2)
4. **Update P0 first** — security vulnerabilities, one at a time, test after each
5. **Update P2 next** — minor/patch are safe to batch since semver guarantees backwards compatibility, test after batch
6. **Update P1 last** — major updates have breaking changes, one at a time, read changelogs before each, test after each
7. **Verify** — run full test suite, build, lint. Show actual output.
8. **Report** — list what was updated, what was skipped (and why), any remaining vulnerabilities

## Rules

- Never update a dependency without running tests after
- Read changelogs for major version bumps before updating
- If a major update breaks tests, revert and report — don't fix the breakage without user approval
- Lock files must be committed with the update
