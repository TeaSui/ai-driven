# Git Workflow Rules

- Commit format: type(scope): description
- Types: feat, fix, refactor, test, docs, chore
- Branch naming: feature/, bugfix/, hotfix/
- PR requires: description, test plan, screenshots (if UI)
- Never force push to main/master
- Keep commits atomic and focused
- Write meaningful commit messages explaining "why"
- Rebase feature branches before merging (squash only if all commits are WIP noise)
- Delete branches after merge
- Tag releases with semantic versioning
- Run tests before pushing — no broken commits on shared branches
- Prefer rebase over merge for feature branches to keep linear history
