# ~/.claude/scripts/

Headless Claude Code scripts for non-interactive tasks.

## validate.sh

Starts a headless Claude session using `~/.claude/skills/validate-feature/SKILL.md`.
By default it validates pending staged, unstaged and untracked work. An explicit
task target takes precedence; it never silently falls back to a previous commit.

```bash
chmod +x ~/.claude/scripts/validate.sh
~/.claude/scripts/validate.sh              # pending staged/unstaged/untracked work
~/.claude/scripts/validate.sh "src/auth"  # filter by path
```

Run from the intended project/module. The optional filter is literal path data.
Claude discovers the installed project's applicable checks: behavior tests for
code changes, relevant structural checks for prose/config, and integration checks
only when the required isolated service and authorization are available. It does
not implicitly install tools, start services or replay mutating API operations.

Output identifies the target, executed checks/evidence, skipped or unsupported
checks and `VALIDATED FOR <scope>`, `PARTIAL` or `BLOCKED`. A mock/TODO keyword hit
requires contextual review; it is not an automatic failure.

## Notes

- `claude -p "..."` = headless (non-interactive) mode
- `--allowedTools` pre-approves the named tools; it does not restrict the available
  tool set or make arbitrary shell commands safe. The launcher retains its existing
  tool permission flags. See the [official CLI reference](https://code.claude.com/docs/en/cli-reference).
- `--output-format text` for clean pipe-friendly output
