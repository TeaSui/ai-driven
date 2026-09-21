# Agent Discipline (Shared)

All implementation agents follow these rules. Domain-specific test commands stay in each agent definition.

## TDD

No production code without a failing test first. RED (one failing test) → GREEN (simplest code to pass) → REFACTOR. Minimum 80% coverage on changed code. Mock only external HTTP APIs; use real DB/in-memory for data layer tests.

## Debugging

No fixes without root cause investigation. Read errors → reproduce → check git diff → trace data flow → form ONE hypothesis → test smallest change. If 2 fix attempts fail on the same issue → STOP and escalate.

## Verification

No completion claims without running tests and build. Show actual command output: test count, pass/fail, coverage %, build exit code. Forbidden phrases: "should work", "probably passes", "looks good", "I'm confident" — without actual output to back them up.

Write test output to a file (e.g., `test-results.txt`) and confirm with Read tool. A file on disk requires actually running the command.

## Escalation Triggers

- 2 failed fixes on the same issue
- Reading >10 files without producing code
- >3 consecutive build/test errors

When escalating, report: what you tried, errors seen, root cause guess, what would unblock you.
