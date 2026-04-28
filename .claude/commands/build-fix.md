# Build Fix - Fix Build Errors Workflow

Systematically fix build errors until the build passes.

## Workflow

### Step 1: Run Build
Execute the build command and capture all errors:
```bash
npm run build 2>&1 | head -100
# or
yarn build 2>&1 | head -100
# or
go build ./... 2>&1
# or
cargo build 2>&1
```

### Step 2: Parse Errors
- Count total number of errors
- Group errors by type (type errors, import errors, syntax errors)
- Identify dependencies between errors (fixing one may fix others)
- Prioritize errors that block other fixes

### Step 3: Fix Each Error
For each error:
1. Read the error message carefully
2. Navigate to the file and line number
3. Understand the root cause
4. Apply the fix
5. Track progress

### Step 4: Verify Fix
After fixing all errors:
```bash
npm run build
```

If new errors appear, repeat from Step 2.

### Step 5: Run Tests
Ensure fixes didn't break functionality:
```bash
npm test
```

## Common Error Types

### TypeScript Type Errors
- Missing type annotations
- Incompatible types
- Missing properties
- Null/undefined handling

### Import Errors
- Missing module
- Incorrect path
- Circular dependencies
- Missing exports

### Syntax Errors
- Missing brackets/parentheses
- Invalid syntax
- Trailing commas
- Reserved keywords

## Error Fix Patterns
| Error Pattern | Likely Fix |
|--------------|------------|
| `Cannot find module` | Install dependency or fix import path |
| `Type X is not assignable to Y` | Add type assertion or fix type |
| `Property does not exist` | Add property or fix spelling |
| `is possibly null/undefined` | Add null check or ! assertion |
| `Duplicate identifier` | Remove duplicate or rename |

## Progress Tracking
Use TaskCreate/TaskUpdate to track each error fix:
- Create todo for each unique error
- Mark complete as fixed
- Re-run build after batch of fixes
