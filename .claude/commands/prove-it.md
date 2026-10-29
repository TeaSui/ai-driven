---
description: Challenge a completion claim — list every assertion made and run the verification command that proves each one; show exact output
argument-hint: "[optional: specific claim or task to verify]"
---

For every completion or status claim in the current task, identify the actual reviewed state/target and applicable scope. Discover project-supported commands before running them. This explicit challenge requires fresh evidence, unlike routine reuse of still-valid results.

Keep checks within the authorized safe target. Do not install dependencies, start services or perform mutating requests merely to prove a claim; mark unavailable gates UNVERIFIED. Redact credentials, PII, environment values, auth headers and raw payloads from output.

For each claim:

1. **List every claim:** "tests pass", "build succeeds", "feature works", "bug fixed", etc.

2. **For each claim, identify the verification command:**
   | Claim | Command | Expected output |
   |-------|---------|-----------------|
   | Tests pass | `npm test` / `pytest` / `go test ./...` | 0 failures |
   | Build succeeds | `npm run build` / `go build` | exit 0 |
   | Bug fixed | original reproduction steps | error no longer reproduced |
   | Feature works | `curl` or acceptance test | expected response |
   | Linter clean | `eslint .` / `ruff .` / `golangci-lint run` | 0 errors |

3. **Run each command NOW** — do not reuse a previous run

4. **Report with decisive output (sensitive contents redacted), exit code and scope:**
   ```
   claim: "tests pass"
   command: npm test
   output: 47 passed, 0 failed
   result: VERIFIED ✓
   ```

5. **If a claim cannot be verified:** "UNVERIFIED: <claim> — <reason>"

$ARGUMENTS
