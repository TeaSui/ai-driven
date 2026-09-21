# ~/.claude/scripts/

Headless Claude Code scripts for non-interactive tasks.

## validate.sh

Validates recently changed files: runs related tests, checks for mock data.

```bash
chmod +x ~/.claude/scripts/validate.sh
~/.claude/scripts/validate.sh              # all changed files in last commit
~/.claude/scripts/validate.sh "src/auth"  # filter by path
```

Output: test results + VALIDATED or BLOCKED status.

## Notes

- `claude -p "..."` = headless (non-interactive) mode
- `--allowedTools` limits scope for speed and safety
- `--output-format text` for clean pipe-friendly output
