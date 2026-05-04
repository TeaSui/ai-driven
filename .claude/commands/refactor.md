---
name: refactor
description: Safe deep refactor with functionality preservation. Test → Refactor → Verify → Iterate until pass. Deletes dead code, rewrites to Principal Engineer standards.
---

# SAFE DEEP REFACTOR

## PHILOSOPHY
Refactor aggressively but **NEVER break functionality**. The codebase may have degraded after many AI iterations - rewrite it clean, but ensure it still works exactly as before.

**GOLDEN RULE**: If it worked before refactor, it MUST work after refactor.

---

## REFACTOR CYCLE
```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐            │
│   │  TEST    │───▶│ REFACTOR │───▶│  VERIFY  │            │
│   │ (Before) │    │          │    │ (After)  │            │
│   └──────────┘    └──────────┘    └────┬─────┘            │
│                                        │                   │
│                        ┌───────────────┴───────────────┐  │
│                        │                               │  │
│                        ▼                               ▼  │
│                   ┌─────────┐                    ┌─────────┐
│                   │  FAIL   │                    │  PASS   │
│                   │ (Fix)   │                    │ (Done)  │
│                   └────┬────┘                    └─────────┘
│                        │                               │
│                        └───────── Loop back ───────────┘
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## PHASE 1: BASELINE TEST (Before Refactor)

### Objective
Capture current functionality as the "source of truth". Whatever works now MUST work after refactor.

### Steps
```
1. DISCOVER existing tests
   - Find all test files
   - Run existing test suites
   - Document pass/fail status

2. RUN existing tests
   $ npm test / ./gradlew test / pytest
   - Record: total, passed, failed, skipped
   - Save output as BASELINE

3. IDENTIFY untested critical paths
   - List main features/endpoints
   - Note which lack test coverage

4. CREATE snapshot tests (if needed)
   - For critical paths without tests
   - API response snapshots
   - UI component snapshots
   - These are temporary "safety nets"

5. MANUAL functionality checklist
   - List core user journeys
   - Document expected behaviors
   - This is the CONTRACT that must not break
```

### Output: Baseline Report
```
## Baseline Test Report

### Test Suite Results
- Total: [N]
- Passed: [N]
- Failed: [N] (these were already failing)
- Skipped: [N]

### Critical Paths Identified
| ID | Feature | Has Tests | Manual Check |
|----|---------|-----------|--------------|
| CP-01 | User login | Yes | Works |
| CP-02 | Create task | Yes | Works |
| CP-03 | ... | ... | ... |

### Baseline Snapshots Created
- [List of snapshot files created]

### Known Issues (Pre-existing)
- [Issues that existed before refactor]

✅ BASELINE CAPTURED - Ready for refactor
```

---

## PHASE 2: REFACTOR

### Objective
Rewrite code to Principal Engineer standards. Delete dead code. Simplify. But keep the same external behavior.

### Principles
```
AGGRESSIVE CLEANUP:
- Unused code → DELETE
- Duplicate code → MERGE into one
- Complex code → SIMPLIFY
- Dead files → DELETE
- Outdated comments → DELETE
- Console.logs → DELETE

REWRITE STANDARDS:
- Functions ≤ 20 lines
- Single responsibility
- Clear naming (no abbreviations)
- Proper error handling
- Input validation
- Consistent patterns throughout

PRESERVE:
- All public APIs (same inputs → same outputs)
- All user-facing behaviors
- All integration points
- Database schema (unless migration planned)
```

### Backend Refactor Checklist
```
□ Delete unused imports
□ Delete unused functions/methods
□ Delete unused classes
□ Delete unused files
□ Delete commented-out code
□ Consolidate duplicate logic
□ Split oversized files (>300 lines)
□ Standardize error handling
□ Add input validation where missing
□ Fix naming inconsistencies
□ Apply consistent code style
□ Ensure proper layering (Controller → Service → Repository)
```

### Frontend Refactor Checklist
```
□ Delete unused components
□ Delete unused hooks
□ Delete unused utilities
□ Delete console.logs
□ Delete commented-out code
□ Remove all TypeScript `any` types
□ Consolidate duplicate components
□ Split oversized components (>150 lines)
□ Extract reusable hooks
□ Add missing loading states
□ Add missing error states
□ Standardize component patterns
```

### Output: Refactor Summary
```
## Refactor Summary

### Deleted
| Type | Count | Examples |
|------|-------|----------|
| Files | [N] | file1.ts, file2.ts |
| Functions | [N] | unusedHelper(), oldMethod() |
| Lines | [N] | - |

### Rewritten
| File | Changes |
|------|---------|
| [path] | [description] |

### New Structure
[tree output]

⚠️ REFACTOR COMPLETE - Proceeding to verification
```

---

## PHASE 3: VERIFY (After Refactor)

### Objective
Confirm ALL functionality preserved. Same tests must pass. Same behaviors must work.

### Steps
```
1. RUN same test suite
   $ npm test / ./gradlew test / pytest
   - Compare with BASELINE
   - ALL previously passing tests MUST still pass

2. COMPARE results
   | Metric | Before | After | Status |
   |--------|--------|-------|--------|
   | Passed | X | Y | ✅/❌ |
   | Failed | X | Y | ✅/❌ |
   
3. CHECK snapshot tests
   - API responses match?
   - Component renders match?

4. VERIFY critical paths manually
   - Go through checklist from Phase 1
   - Each must work exactly as before

5. VERDICT
   - ALL PASS → Phase 4 (Done)
   - ANY FAIL → Phase 3.5 (Fix)
```

### Output: Verification Report
```
## Verification Report

### Test Comparison
| Metric | Baseline | After Refactor | Status |
|--------|----------|----------------|--------|
| Total | [N] | [N] | - |
| Passed | [N] | [N] | ✅/❌ |
| Failed | [N] | [N] | ✅/❌ |

### Critical Path Verification
| ID | Feature | Before | After | Status |
|----|---------|--------|-------|--------|
| CP-01 | User login | Works | Works | ✅ |
| CP-02 | Create task | Works | Works | ✅ |

### Verdict: [PASS / FAIL]
```

---

## PHASE 3.5: FIX (If Verification Failed)

### Objective
Fix broken functionality WITHOUT reverting to messy code. Find the root cause, fix properly.

### Steps
```
1. IDENTIFY what broke
   - Which tests failed?
   - Which critical paths broken?

2. ANALYZE root cause
   - What refactor change caused this?
   - Was it a logic error or missing piece?

3. FIX while maintaining clean code
   - Do NOT just revert to old messy code
   - Fix the issue properly
   - Keep the clean structure

4. RE-VERIFY
   - Run tests again
   - Check critical paths again
   - Loop until all pass
```

### Output: Fix Report
```
## Fix Report

### Issues Found
| Issue | Root Cause | Fix Applied |
|-------|------------|-------------|
| [Test/Feature] | [What went wrong] | [How fixed] |

### Re-verification
[Run Phase 3 again]

→ If PASS: Proceed to Phase 4
→ If FAIL: Repeat Phase 3.5
```

---

## PHASE 4: COMPLETE

### Final Checklist
```
□ All baseline tests passing
□ All critical paths working
□ No regression in functionality
□ Code is clean and maintainable
□ Dead code removed
□ Consistent patterns throughout
□ Documentation updated (if needed)
```

### Final Report
```
## Refactor Complete ✅

### Summary
- Files deleted: [N]
- Files modified: [N]
- Lines removed: [N]
- Lines added: [N]
- Net reduction: [N] lines

### Test Results
- Before: [N] passed / [N] total
- After: [N] passed / [N] total
- Status: ✅ All functionality preserved

### Quality Improvements
- [Improvement 1]
- [Improvement 2]
- [Improvement 3]

### Code Health
| Metric | Before | After |
|--------|--------|-------|
| Files | [N] | [N] |
| Lines of code | [N] | [N] |
| Test coverage | [X]% | [Y]% |
| Lint errors | [N] | 0 |

### Breaking Changes
None - all functionality preserved.

### Recommended Next Steps
- [Optional improvements for future]
```

---

## EXECUTION ORDER
```
1. Backend first (APIs must be stable for frontend)
   - Phase 1: Test backend
   - Phase 2: Refactor backend
   - Phase 3: Verify backend
   - (Phase 3.5: Fix if needed)
   - Phase 4: Complete backend

2. Frontend second
   - Phase 1: Test frontend
   - Phase 2: Refactor frontend  
   - Phase 3: Verify frontend
   - (Phase 3.5: Fix if needed)
   - Phase 4: Complete frontend

3. Integration verification
   - Run E2E tests (if exist)
   - Manual full flow test
```

---

## RULES

1. **FUNCTIONALITY IS SACRED** - Never break what works
2. **TEST BEFORE TOUCH** - Always baseline first
3. **VERIFY AFTER CHANGE** - Always confirm after
4. **FIX, DON'T REVERT** - Keep clean code, fix issues properly
5. **DELETE AGGRESSIVELY** - Dead code must go
6. **REWRITE, DON'T PATCH** - Clean rewrite over messy patches
7. **LOOP UNTIL PASS** - Don't stop until all tests green

---

## ARGUMENTS

$ARGUMENTS

If no arguments provided, refactor entire codebase (backend then frontend).

Examples:
- `backend` - Refactor backend only
- `frontend` - Refactor frontend only
- `src/features/auth` - Refactor specific module only