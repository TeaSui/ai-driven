# Installed project validator workflow

Use this procedure instead of the retired inline OpenAPI and baseline scripts. It is an instruction workflow, not a bundled validator. Do not build a partial OpenAPI implementation or install packages to manufacture a passing result.

## Discover and bind

Run from the service/module named by the task. Inspect filenames and the relevant manifests/CI definitions before choosing a command:

```bash
rg --files -g '*openapi*' -g '*swagger*' -g '*contract*' -g '*pact*' -g '*lock*' -g 'pom.xml' -g '*gradle*' -g 'package.json' -g 'pyproject.toml' -g 'go.mod' -g '.github/workflows/*'
```

Read the selected spec's `openapi`/`swagger` field; record its SHA-256 and project commit plus working-change scope. Read exact installed versions from the matching lockfile/environment. Use the documented repository command and test selector; for example, an existing Python contract suite can run as `python -m pytest tests/contracts --junitxml=artifacts/contracts.xml` **only if that path and output directory were verified in this project**. Equivalent Gradle/Maven/Go/npm selectors come from the project's own CI. Do not invent a script or silently use a different service when a selector is absent.

Inspect how the suite validates actual request/response objects. A spec linter or snapshot alone is insufficient. Existing libraries such as [openapi-core](https://openapi-core.readthedocs.io/en/latest/) provide response validation; choose its adapter/API and supported spec version from the installed release, rather than copying a latest-version import into an older project.

## Deterministic scenario gate

Each scenario supplies real fixture IDs, complete request body/query/headers and expected behavior. Use the project's test client and validator. Include success and intended error responses. Assert exact expected status **before** schema validation, then validate declared media type, JSON decoding and the entire response schema. Run against an in-process app or an explicitly identified isolated service; no operation is safe merely because its HTTP method says GET.

Before accepting the validator integration, exercise these cases with synthetic data in the project's own test harness:

| Fixture | Required outcome |
|---|---|
| Known valid response with referenced/nested schema | Pass; nonzero tests actually ran |
| Scenario expects 200, server returns declared 401 or 500 | Fail exact-status assertion |
| JSON media type with malformed body | Fail decoding/validation |
| Wrong nested type, missing nested required property, invalid array item via `$ref` | Fail schema validation |
| Unsupported dialect, unresolved external reference or invalid spec | Fail or report unsupported; cannot pass required gate |
| Zero scenarios, runner/config failure or failed assertion | Required gate fails with nonzero status |
| Request with body/query | Server fixture verifies those inputs were retained |

Resolve local references under the intended spec root. Do not fetch arbitrary remote references without explicit scope; unsupported resolution blocks the gate. Record negative-fixture evidence, not only a passing happy path. Preserve actual exit status; do not hide it with `|| true`, a trailing success command or an unguarded pipeline. Do not report a skipped schema branch as compliance.

## Optional installed Schemathesis route

For a project already using Schemathesis, inspect `st --version` and `st run --help` (or its installed `schemathesis` entry point). The following is the documented CLI shape checked on 2026-10-24; use it only when that installed version supports these flags, the spec contains usable examples and `getAccount` is the verified intended operation ID:

```bash
st run ./openapi.yaml --url http://127.0.0.1:8080 --include-operation-id getAccount --phases examples --checks status_code_conformance,content_type_conformance,response_schema_conformance --report junit
```

This sends HTTP requests. Run it only against an already authorized disposable target and inspect project configuration/hooks first. Choose authentication through the project's secret-safe test fixture; do not put raw tokens on a logged command line. Review sanitized output before sharing it. Inspect warnings and report coverage: unresolved references, unsupported schemas, missing examples or skipped operations block claims about those scenarios. Match flags to the pinned release's [CLI reference](https://schemathesis.readthedocs.io/en/stable/reference/cli/) and [checks](https://schemathesis.readthedocs.io/en/stable/reference/checks/).

Schema conformance accepts statuses declared by the spec. Keep deterministic project assertions for the **particular scenario's expected status**. Do not call generated example checks a substitute for that assertion. The current CLI documents distinct failure/configuration exit codes; verify the installed version's behavior with the negative fixtures above. Missing CLI or incompatible flags means unsupported; do not auto-install a tool or fall back to the retired shell loop.

## Baseline regression

Bind a baseline to the same spec/version, scenario selectors and fixture setup. Retain sanitized request method, actual path/query, body, content type and non-secret headers; supply credentials at runtime through existing fixtures. Record expected status/media type and stable nested response fields or snapshots. Normalize only named volatile fields (for example generated timestamps); preserve array/type/null distinctions. Repeat full schema validation in both baseline and current runs.

Use the repository's installed assertion/snapshot runner so mismatches fail the command. Fail a required gate on missing baseline, zero cases or missing input reports. Baseline updates are an explicit review action, never part of comparison. Schemathesis failure baselines, if used by a pinned release, suppress known findings; they are not response snapshots or proof that those findings were fixed. Keep latency measurements and budgets separate from contract compliance.

## Handoff

Persist the exact command, runner version, spec hash, environment, executed count, status and sanitized report path. Say “unsupported: no compatible installed validator” when appropriate. This skill does not supply or certify a universal executable validator.
