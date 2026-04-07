# Test Coverage - Coverage Analysis Workflow

Analyze test coverage and generate tests for uncovered code.

## Workflow

### Step 1: Run Coverage Report
```bash
# JavaScript/TypeScript
npm test -- --coverage
npx jest --coverage
npx vitest --coverage

# Python
pytest --cov=src --cov-report=html

# Go
go test -coverprofile=coverage.out ./...
go tool cover -html=coverage.out

# Rust
cargo tarpaulin --out Html
```

### Step 2: Analyze Results
- Overall coverage percentage
- Files below threshold (80%)
- Uncovered lines/branches
- Critical paths without tests

### Step 3: Prioritize Gaps
Focus on:
1. Business-critical code
2. Complex logic with branches
3. Error handling paths
4. Public API functions
5. Recently changed code

Skip:
- Generated code
- Configuration files
- Simple getters/setters
- Third-party wrappers

### Step 4: Generate Missing Tests
For each uncovered section:
1. Understand what the code does
2. Identify inputs and expected outputs
3. Write test(s) covering the gap
4. Include edge cases

### Step 5: Verify Threshold
Re-run coverage and confirm:
- Overall coverage ≥ 80%
- Critical files ≥ 90%
- No regressions in existing coverage

## Coverage Report Template
```markdown
## Coverage Summary
| Metric | Current | Target | Status |
|--------|---------|--------|--------|
| Lines | X% | 80% | ✅/❌ |
| Branches | X% | 75% | ✅/❌ |
| Functions | X% | 80% | ✅/❌ |

## Files Below Threshold
| File | Coverage | Priority |
|------|----------|----------|
| path/to/file.ts | 45% | High |
| path/to/other.ts | 65% | Medium |

## Action Items
1. [ ] Add tests for file1.ts lines 45-67
2. [ ] Cover error path in file2.ts
3. [ ] Test edge cases in file3.ts
```

## Test Generation Tips
- Use arrange-act-assert pattern
- Test one behavior per test
- Use descriptive test names
- Mock external dependencies
- Test boundary conditions
