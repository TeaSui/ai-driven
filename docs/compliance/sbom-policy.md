# SBOM Policy

## Purpose

A Software Bill of Materials (SBOM) provides a machine-readable inventory of every dependency in a software release. This enables:

- Rapid vulnerability triage when a CVE is published (know immediately which releases are affected)
- License compliance verification (open source obligations)
- Supply chain security audits (SOC 2, ISO 27001, customer due diligence)

## Scope

This policy covers the `ai-driven` platform, which consists of two dependency graphs:

| Component | Runtime | Dependency file |
|-----------|---------|-----------------|
| Backend API | Python 3.12 | `pyproject.toml` + `poetry.lock` |
| Web UI | Node.js 20 | `ai-driven-web-ui/package-lock.json` |

## SBOM Format

All SBOMs are produced in **CycloneDX JSON** (latest stable spec). CycloneDX is the OWASP-maintained standard and is accepted by NTIA minimum elements guidance.

## Generation

### When

SBOMs are generated automatically by the `SBOM Generation` GitHub Actions workflow:

- **On every release tag** (`v*`) — this is the authoritative per-release SBOM.
- **On manual trigger** (`workflow_dispatch`) — for ad-hoc audits between releases.

SBOMs are NOT committed to the repository. They change with every dependency update and committing them would create unnecessary noise in git history.

### How

The workflow calls `scripts/generate-sbom.sh`, which:

1. Installs `cyclonedx-bom` (Python) and runs `cyclonedx-py poetry --no-dev` against `pyproject.toml` + `poetry.lock`.
2. Runs `npx @cyclonedx/cyclonedx-npm --production` against `ai-driven-web-ui/package-lock.json`.
3. Runs `pip-audit` to produce a vulnerability report against the locked Python dependency set.
4. Runs `npm audit` to produce a vulnerability report for Node.js dependencies.

### Dev dependency handling

Dev/optional dependencies (test frameworks, linters) are **excluded** from the production SBOMs. They are not shipped in release artifacts and do not constitute supply chain risk for production. If needed for internal audits, pass `--with-dev` to `generate-sbom.sh` locally.

## Artifact Storage

| Artifact | Location | Retention |
|----------|----------|-----------|
| `sbom-python.json` | GitHub Actions artifact `sbom-<tag>` | 365 days |
| `sbom-nodejs.json` | GitHub Actions artifact `sbom-<tag>` | 365 days |
| `pip-audit-results.json` | GitHub Actions artifact `audit-<tag>` | 365 days |
| `npm-audit-results.json` | GitHub Actions artifact `audit-<tag>` | 365 days |

Artifacts are accessible at: **Actions → SBOM Generation → select run → Artifacts**.

For long-term archival beyond 365 days (e.g., regulated customer contracts), download and store the artifacts in the designated S3 compliance bucket at release time.

## Vulnerability Response

| Severity | Action | SLA |
|----------|--------|-----|
| Critical | Block release, patch immediately | Same day |
| High | Track in issue, patch in next release | 7 days |
| Medium | Track in issue, patch in next sprint | 30 days |
| Low | Accept or document as known risk | Next quarterly review |

The CI pipeline warns on critical/high findings but does not hard-fail the build automatically. This is intentional: some vulnerabilities exist in transitive dependencies with no upstream fix. All findings must be reviewed and tracked, not silently suppressed.

## Local Generation

To generate SBOMs locally (requires Python 3.12 and Node.js 20):

```bash
# Production SBOMs only
bash scripts/generate-sbom.sh --output-dir /tmp/sbom-local

# Including dev dependencies
bash scripts/generate-sbom.sh --with-dev --output-dir /tmp/sbom-local
```

## Ownership

The platform engineering team owns this policy. Review annually or after any significant supply chain event.
