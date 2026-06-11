# Threat Model & Security Rules: LangSmith Tracing Egress (Phase 0)

**Feature:** LangSmith LLM-level tracing added to the existing custom Claude provider stack.
**Phase:** 0 (tracing only, zero behavior change) of `docs/design/langgraph-migration.md` (FINAL v3).
**Author:** security-engineer-subagent
**Date:** 2026-06-11
**Status:** AUTHORITATIVE — the Phase 0 implementer MUST satisfy every CRITICAL and HIGH rule below before merge. Phase 1 implementers consume the same rules where the egress surface persists.
**Run order:** Produced BEFORE implementation, per `rules/workflow-routing.md` (trust boundary: new external vendor egress).

---

## 1. Scope

**In scope:** The egress path created when `@traceable` (the `langsmith` SDK) wraps the two concrete Claude providers' message methods and ships trace payloads to LangSmith's hosted backend.

**Egress surface (verified against `backend/src/ai_driven/claude/provider.py`):**
- `AnthropicProvider.create_message` (`provider.py:208`)
- `AnthropicProvider.create_message_stream` (`provider.py:249`)
- `BedrockProvider.create_message` (`provider.py:390`)
- `BedrockProvider.create_message_stream` (`provider.py:448`)
- `ResilientProvider` (`provider.py:643`) wraps the above; tracing must not double-wrap or double-emit (see LSMITH-13).

> **Scope note (the plan under-specifies this):** `docs/plans/2026-06-11-langgraph-migration-phase0-1.md` Task 2 only tests/wires `create_message`. The **streaming** methods are an equal-severity egress surface — a streamed response reassembled by LangSmith contains the same content as a non-streamed one. LSMITH-02 makes masking coverage of the streaming path a hard requirement so it is not silently left unmasked.

**Out of scope:** Phase 2+ (LangGraph Postgres checkpointer, orchestrator graph). Forward-looking concerns are captured non-normatively in §6.

---

## 2. Assets & Data Classification

Trace payloads, if unmasked, would carry the following. Classification per `references/data-privacy-patterns.md`.

| Asset | In trace as | Classification | Notes |
|---|---|---|---|
| Jira ticket bodies | `messages[].content`, `system` | **Level 1/2 PII (variable)** | Customer-reported tickets may embed names, emails, phones, account refs, national IDs. Must be treated as Level 1 (worst case) because content is unstructured and unbounded. |
| Repo code snippets | `messages[].content`, tool results | **Confidential (proprietary IP)** | Source code, secrets-in-code risk, internal architecture. |
| System prompts | `system` | **Confidential** | Encodes internal agent logic, tool contracts, business rules. |
| Tool schemas / definitions | `tools` | **Confidential** | Reveals internal tool surface and capabilities. |
| Tool call inputs/results | `messages[].content` | **Level 1/2 PII + Confidential** | grep results, file contents, clarification answers (may be PII). |
| Model name | response.model | Internal (permitted) | Needed for cost/latency analysis. |
| Token usage | response.usage | Internal (permitted) | Needed for cost analysis. |
| Stop reason | response.stop_reason | Internal (permitted) | Needed for refusal/truncation analysis. |
| Latency / timing | span metadata | Internal (permitted) | Performance analysis. |
| `LANGSMITH_API_KEY` | SDK auth header | **Level 1 (credential)** | Grants write (and project read) access to the LangSmith tenant. |

**Permitted-in-trace allowlist (the ONLY fields that may leave when masking is on):** `model`, `max_tokens`, `temperature`, `top_p`, `stop_sequences` (non-PII config only), `usage` (token counts), `stop_reason`, message `role` values, message/tool **counts** and **lengths** (as redaction summaries, e.g. `<redacted:60chars>`), span timing, and the configured `langsmith_project` name. Everything else is deny-by-default.

---

## 3. Trust Boundaries & Data Flow

```
[ai-driven backend, VPC/trusted]                    [TRUST BOUNDARY]        [LangSmith SaaS, external]
  Jira ticket + repo + prompts                            │
        │                                                 │
        ▼                                                 │
  ClaudeProvider.create_message(_stream)                  │
        │ (@traceable wraps here)                         │
        ▼                                                 │
  mask_inputs() / mask_outputs()  ──── redacted payload ──┼──── HTTPS/TLS ────▶  LangSmith ingest
        │                                                 │                       (US region by default;
        ▼                                                 │                        retention = vendor default)
  Anthropic API / AWS Bedrock (existing, separate boundary)
```

**Boundary 1 (new, this threat model):** ai-driven backend → LangSmith SaaS. New vendor, internet egress, carries LLM payloads. This is the boundary that triggered the full workflow.

**Boundary 2 (pre-existing):** backend → Anthropic/Bedrock. Already governed by DPA; Bedrock path is VPC/regional. Not re-analyzed here, but note the residency asymmetry: Bedrock can be regional (ap-southeast-1) while LangSmith default ingest is US.

---

## 4. STRIDE Analysis

| # | STRIDE | Threat | Affected asset | OWASP 2021 | Mitigating rule(s) | Severity |
|---|--------|--------|----------------|-----------|--------------------|----------|
| T1 | **Spoofing** | Leaked `LANGSMITH_API_KEY` lets an attacker write forged/poisoned traces or read the project, impersonating our tenant. | API key | A07 | LSMITH-05, LSMITH-06, LSMITH-07 | HIGH |
| T2 | **Spoofing** | Key committed to repo / baked into image / placed in a `Settings` field that ends up in `/health`, config dumps, or logs. | API key | A05, A07 | LSMITH-05, LSMITH-06 | CRITICAL |
| T3 | **Tampering** | Unbounded/oversized payloads or unexpected content types (bytes, nested objects) bypass the masker and leak raw content. | PII, code | A03, A04 | LSMITH-01, LSMITH-02, LSMITH-03 | CRITICAL |
| T4 | **Tampering** | A future code change adds a new kwarg (e.g. `metadata`, `extra_headers`) carrying PII that the masker's allowlist doesn't cover, silently leaking. | PII | A04 | LSMITH-03 (deny-by-default), LSMITH-12 | HIGH |
| T5 | **Repudiation** | No record of *when* tracing was enabled / in which environment, so a later PII-egress incident can't be scoped (which traces, which window). | enablement state | A09 | LSMITH-10, LSMITH-11 | HIGH |
| T6 | **Information Disclosure** | Masking off (or defaulting off) ships raw Jira PII + proprietary code to a US-region SaaS — the headline fintech/PDPA risk. | PII, code, prompts | A02, A04 | LSMITH-01, LSMITH-04, LSMITH-08 | CRITICAL |
| T7 | **Information Disclosure** | Streaming path (`create_message_stream`) left unmasked because the plan only wired `create_message`. | PII, code | A02 | LSMITH-02 | CRITICAL |
| T8 | **Information Disclosure** | Data residency: LangSmith default ingest is US; SG-resident customer PII crosses border without a DPA / residency decision. | PII | A02 | LSMITH-08, LSMITH-09 | CRITICAL (prod) |
| T9 | **Information Disclosure** | Masking failure (exception inside `mask_inputs`) handled by SDK falling back to raw payload, leaking content. | PII, code | A02, A04 | LSMITH-03, LSMITH-12 | HIGH |
| T10 | **Denial of Service** | LangSmith outage, slow ingest, or SDK error blocks/breaks the LLM call — tracing becomes a hard dependency on the critical path. | availability | A04 (insecure design) | LSMITH-13, LSMITH-14 | CRITICAL |
| T11 | **Denial of Service** | Synchronous/blocking trace export adds latency to every agent iteration (17-iteration loop) → timeout cascades. | availability | A04 | LSMITH-14 | HIGH |
| T12 | **Elevation of Privilege** | Tracing enabled in a non-prod env reads from prod-shaped data (e.g. staging pointed at prod Jira) and egresses it under a "dev" project that's less protected. | PII | A01, A05 | LSMITH-04, LSMITH-09 | HIGH |
| T13 | **Elevation of Privilege** | `@traceable` applied at the `ResilientProvider` AND concrete-provider layer → retries emit duplicate traces, amplifying egress and confusing audit. | PII, audit | A04 | LSMITH-13 | MEDIUM |

**OWASP Top 10 (2021) coverage statement:**
- A01 Broken Access Control — T12 (env isolation), LSMITH-09.
- A02 Cryptographic Failures — T6/T7/T8 (data in transit + residency), LSMITH-02/04/08.
- A03 Injection — T3 (payload-shape handling); LSMITH-03. (No SQL/command surface in Phase 0.)
- A04 Insecure Design — T3/T9/T10/T11/T13 (fail-open design, single-control reliance); LSMITH-12/13/14.
- A05 Security Misconfiguration — T2/T12 (default-off, env gating); LSMITH-04/06.
- A06 Vulnerable Components — `langsmith>=0.4` dependency; LSMITH-15.
- A07 Identification & Auth Failures — T1/T2 (key handling); LSMITH-05/06/07.
- A08 Software & Data Integrity Failures — supply chain of the new dep; LSMITH-15.
- A09 Security Logging & Monitoring Failures — T5 (enablement auditing); LSMITH-10/11.
- A10 SSRF — **Not applicable.** Phase 0 makes outbound calls only to a fixed vendor endpoint configured by the SDK; no user-controlled URL, no server-side request to a user-supplied target.

---

## 5. Security Rules (LSMITH-01 .. LSMITH-15)

Each rule is binding, testable, and maps to a STRIDE finding. Target domain: **backend** (Python) unless noted; LSMITH-08/09 also require a **human/compliance** decision; LSMITH-15 targets **devops**.

### Data Egress & Masking

**LSMITH-01 — Masking ON by default and non-bypassable when tracing is on.** [CRITICAL] (T3, T6)
When `langsmith_enabled` is true, payload masking MUST be applied to every traced call. `langsmith_mask_payloads` defaults to `True`. Disabling masking (`langsmith_mask_payloads=False`) MUST be impossible in the `production` and `staging` environments — the code MUST raise/refuse to start (fail-secure) if masking is off while tracing is on in those environments.
*Verify:* unit test asserts default `True`; test asserts that constructing the tracer with `environment in {production, staging}` + masking off raises.

**LSMITH-02 — Masking covers BOTH the unary and streaming egress surfaces.** [CRITICAL] (T7)
`mask_inputs`/`mask_outputs` (or equivalent) MUST be applied to all four egress methods: `create_message` and `create_message_stream` on both `AnthropicProvider` and `BedrockProvider`. A streamed response's reassembled content MUST be redacted before any trace span is finalized.
*Verify:* a test enables tracing with a capturing transport and asserts no raw message/system content appears in the emitted span for a streaming call.

**LSMITH-03 — Redaction is allowlist (deny-by-default), not denylist.** [CRITICAL] (T3, T4, T9)
The masker MUST emit ONLY the permitted-in-trace allowlist in §2. Any input key not on the allowlist MUST be dropped or replaced with a redaction summary (`<redacted>` / `<redacted:Nchars>` / `<redacted:N tools>`). Adding a new field to the call signature MUST NOT cause it to leak by default. Non-string content (dict, list, bytes) on a content field MUST be redacted, not passed through.
*Verify:* test passes an unexpected kwarg with a PII value and asserts it does not appear in the masked output; test passes non-string `content` and asserts redaction.

**LSMITH-04 — Permitted metadata only.** [HIGH] (T6, T12)
Traces MAY retain: model name, token usage, stop reason, latency/timing, `max_tokens`/`temperature`/`top_p`, message `role`s, redaction-length summaries, and the project name. Traces MUST NOT retain message content, system prompt content, tool input/results content, tool schemas, raw `stop_sequences` if they could encode content, or any header/auth value.
*Verify:* `mask_outputs` test asserts `usage`/`stop_reason`/`model` present and `content` absent (matches plan Task 2 test); `mask_inputs` test asserts `system`/`messages`/`tools` redacted and `model`/`max_tokens` retained.

### API Key Handling

**LSMITH-05 — Key only via `LANGSMITH_API_KEY` env, read by the SDK directly.** [CRITICAL] (T1, T2)
The API key MUST NOT be a `Settings`/pydantic field, MUST NOT have a default, MUST NOT be committed, and MUST NOT be passed through application code that could log or dump it. It is read solely from the environment by the `langsmith` SDK.
*Verify:* `assert not hasattr(Settings(_env_file=None), "langsmith_api_key")` (matches plan Task 1 test); grep of the repo for `LANGSMITH_API_KEY` shows no literal value, only env reference.

**LSMITH-06 — No key (or trace payload) reaches logs, error responses, `/health`, or config dumps.** [CRITICAL] (T2)
Tracer init/teardown and masking code MUST NOT log the key, the project URL with embedded credentials, or raw payloads at any log level. The enablement-state log (LSMITH-10) MUST log only the boolean state, environment, project name, and masking-on boolean — never the key.
*Verify:* test captures logs during enable/disable and asserts no key substring; review confirms no `logger.*` call includes the key or raw payload.

**LSMITH-07 — Key is least-privilege and rotatable.** [HIGH] (T1) — *human/devops action, advisory*
The LangSmith key issued to the backend SHOULD be scoped to the minimum (write/ingest to the target project; no org-admin). Key rotation MUST be possible without a code change (env/secret-store update only). Recommend storage in AWS Secrets Manager / SSM SecureString in deployed envs, injected as the env var at runtime — never baked into an image.
*Verify:* deployment manifest review; rotation runbook exists.

### Environment Gating

**LSMITH-08 — Production enablement gated on a signed-off data-residency decision.** [CRITICAL, prod blocker] (T6, T8)
Tracing MUST remain disabled in `production` until a documented data-residency decision (LangSmith EU region OR self-hosted/VPC LangSmith) is signed off by a named owner, AND a DPA covering LangSmith as a processor exists (per `references/data-privacy-patterns.md` "Cross-Border Data Transfer" / "third-party data processors"). Until then, prod enablement is a release blocker even with masking on (masking reduces but does not eliminate residual content risk).
*Verify:* this rule references a decision record; the prod config ships `AI_DRIVEN_LANGSMITH_ENABLED` unset/false; CI/deploy check asserts the prod profile does not enable tracing absent the signed-off record.

**LSMITH-09 — Per-environment project isolation and explicit opt-in per environment.** [HIGH] (T8, T12)
Each environment MUST use a distinct `langsmith_project` (e.g. `ai-driven-dev`, `ai-driven-staging`, `ai-driven-prod`). Enablement is per-environment and MUST default off in every environment (`langsmith_enabled=False`). A non-prod environment MUST NOT be pointed at prod-classified data sources while tracing is on without the same LSMITH-08 gate.
*Verify:* default `langsmith_project == "ai-driven-dev"` and `langsmith_enabled is False` (matches plan Task 1 test); deploy config sets distinct project per env.

### Audit & Logging of Enablement State

**LSMITH-10 — Enablement state is logged at startup.** [HIGH] (T5)
On application startup, the system MUST emit exactly one structured log line recording: `langsmith_enabled` (bool), `environment`, `langsmith_project`, and `masking_on` (bool). This makes "was tracing on, where, and was it masked?" answerable during incident scoping. The line MUST NOT contain the API key or any payload.
*Verify:* startup-log test asserts the four fields present and key absent.

**LSMITH-11 — Enablement state is observable/auditable post-hoc.** [HIGH] (T5)
The enablement booleans MUST be queryable at runtime (e.g. exposed as a DataDog gauge/tag or an internal status field) so an auditor can confirm the live state without a redeploy. Reuse the existing DataDog channel (design §4: DataDog stays). MUST NOT expose the key.
*Verify:* a metric/status field reflects `langsmith_enabled` and `masking_on`.

### Failure Modes (Fail-Open for Availability, Fail-Secure for Content)

**LSMITH-12 — Masking failure fails SECURE (drop the trace, never the content).** [HIGH] (T9)
If masking raises or cannot fully redact a payload, the system MUST NOT emit that span with raw content. It MUST either emit a fully-redacted placeholder span or drop the span entirely, and log a counter (no payload). The LLM call itself MUST proceed normally (see LSMITH-13).
*Verify:* test injects a masker exception and asserts (a) the LLM call result is unchanged, (b) no raw content is emitted.

**LSMITH-13 — Tracing failure fails OPEN for the LLM call (tracing is never on the critical path).** [CRITICAL] (T10, T13)
Any LangSmith error — SDK exception, network failure, auth failure, ingest timeout — MUST NOT break, block, or change the return value/signature of `create_message`/`create_message_stream`. When `langsmith_enabled` is false, the decorator MUST be a pure pass-through (no behavior, signature, or return-value change; no API key required). Tracing MUST be applied at exactly one layer (the concrete providers OR the resilient wrapper, not both) to avoid duplicate spans on retry.
*Verify:* plan Task 2 test `test_traced_llm_is_passthrough_when_disabled` (return value and call kwargs identical); add a test that, with tracing enabled, an exporter raising still returns the LLM result unchanged; assert single span per logical call under `ResilientProvider` retry.

**LSMITH-14 — Trace export is non-blocking / bounded.** [HIGH] (T10, T11)
Trace submission MUST NOT add unbounded latency to the call path. Use the SDK's background/async submission; if a synchronous flush is ever used, it MUST have a short bounded timeout and failures swallowed per LSMITH-13. The 17-iteration agent loop MUST NOT slow measurably due to tracing.
*Verify:* a latency assertion or review confirming background submission; no synchronous unbounded flush on the call path.

### Dependency / Supply Chain

**LSMITH-15 — Pin and scan the new dependency.** [MEDIUM] (A06/A08)
`langsmith>=0.4` MUST be pinned to a resolved version in the lockfile and included in dependency vulnerability scanning (recommend Trivy/Grype/Snyk in CI; gitleaks/trufflehog already cover secret scanning for LSMITH-05). Upgrades MUST be reviewed since this dependency sits on a PII egress path.
*Verify:* lockfile contains a resolved `langsmith` version; CI dependency scan includes it.

---

## 6. Forward-Looking Concerns (Phase 2+, NON-NORMATIVE)

These are flagged for the next threat model, not binding on Phase 0:

- **Checkpointer egress (Phase 2):** the Postgres `AsyncPostgresSaver` will persist full conversation state (unmasked, by design — it's the resume source). That is a *storage* boundary (RDS, in-VPC) distinct from LangSmith *egress*, but it holds the same Level 1 PII and needs its own at-rest encryption (KMS CMK), retention/TTL (the plan's Phase 4 reaper), and access-audit analysis.
- **LangSmith eval datasets (design §4):** eval datasets built from real traces could persist PII into a long-lived dataset even with span masking. When the eval path lands, require that datasets are built from masked data or from a synthetic/sanitized corpus.
- **Residency decision (LSMITH-08) is the gating artifact** for ever turning this on in prod; resolve it before Phase 2 if prod tracing is desired earlier.

---

## 7. Compliance Mapping

- **MAS TRM:** Data Protection (TRM 10) — third-party processor egress of customer data requires encryption in transit (TLS, SDK default) and a documented processor; Audit Trail (TRM 11) — enablement-state logging (LSMITH-10/11). PDPA/MAS cross-border transfer drives LSMITH-08.
- **PDPA (Cross-Border Data Transfer, `references/data-privacy-patterns.md`):** SG-resident PII to a US-region SaaS requires a residency decision + DPA — LSMITH-08/09. Also "Never send raw PII to external LLM APIs" → masking (LSMITH-01..04) is the control that keeps trace egress compliant.
- **OWASP Top 10 2021:** full mapping in §4.

---

## 8. Implementer Checklist (Phase 0 gate)

A Phase 0 PR is mergeable only when:
- [ ] LSMITH-01 masking default `True` + fail-secure when off in staging/prod
- [ ] LSMITH-02 masking on all four egress methods incl. streaming
- [ ] LSMITH-03 allowlist/deny-by-default masking with non-string handling
- [ ] LSMITH-05/06 no key as Settings field, no key/payload in logs
- [ ] LSMITH-10 startup enablement-state log (4 fields, no key)
- [ ] LSMITH-13 pass-through when disabled + fail-open on tracing error + single span layer
- [ ] LSMITH-08 prod ships disabled; residency decision recorded before any prod enablement
- [ ] LSMITH-15 dependency pinned + scanned

**Last updated:** 2026-06-11
