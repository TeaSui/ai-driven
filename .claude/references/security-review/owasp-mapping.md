# OWASP edition and finding mapping

Use the project's explicitly required OWASP Top 10 edition. When unspecified, use **2025** with the companion [checklist](checklist.md). State the selected edition in the report and qualify all IDs (`A01:2025`, `A01:2021`, or the official 2017 form `A1:2017`).

## Procedure

1. Identify the concrete weakness, affected asset/boundary and STRIDE finding before assigning a category.
2. Verify the selected edition's category description and relevant weakness mapping at the official source. A category label or numeric position alone is insufficient.
3. Record the finding ID, edition-qualified category ID/name, source link, rationale, applicable rule IDs and verification evidence. If several categories apply, explain the primary cause and any secondary mappings.
4. Record risks outside that edition's web Top 10 rather than forcing a category. For each category with no findings, explain non-applicability or the actual coverage limit.
5. Preserve historical mappings with their original edition. A new assessment may add a separately justified mapping; never silently rename old IDs or claim a one-to-one crosswalk.

## Default 2025 category index

The category names/IDs below match the [official 2025 list](https://top10.owasp.org/2025/); review the category page for the specific finding.

| ID | Category |
|----|----------|
| A01:2025 | Broken Access Control |
| A02:2025 | Security Misconfiguration |
| A03:2025 | Software Supply Chain Failures |
| A04:2025 | Cryptographic Failures |
| A05:2025 | Injection |
| A06:2025 | Insecure Design |
| A07:2025 | Authentication Failures |
| A08:2025 | Software or Data Integrity Failures |
| A09:2025 | Security Logging and Alerting Failures |
| A10:2025 | Mishandling of Exceptional Conditions |

The [official introduction](https://top10.owasp.org/2025/0x00_2025-Introduction/) explains changes: SSRF moved into A01:2025, A03:2025 expands supply-chain scope, and A10:2025 covers exceptional conditions. This is why a 2021/2017 numeric substitution is unsafe.

## Explicit older-edition paths

- Project requires **2021**: use the [official 2021 index/category pages](https://top10.owasp.org/2021/), with `:2021` on IDs. For example, `A10:2021` is SSRF, not the 2025 A10 category. Build the report's coverage checklist from that edition while retaining relevant controls from the companion checklist.
- Project requires **2017**: use the [official 2017 index/category pages](https://owasp.github.io/www-project-top-ten/2017/), preserving its IDs such as `A1:2017` for Injection. XXE, XSS and insecure deserialization are separate categories in that edition; do not present the 2025 order as a 2017 checklist.
- If an explicitly required edition/reference cannot be verified, state the gap and mark category mapping unverified. Do not substitute training recall or claim a complete edition-specific assessment.

Sources verified for this content refresh on 2026-10-24. Recheck the selected official edition for a later substantive review; these links do not establish compliance or exhaustive testing.
