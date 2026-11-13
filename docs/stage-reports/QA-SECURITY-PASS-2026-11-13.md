# QA and security pass — 2026-11-13

Done as a QA engineer and security reviewer, against `QA-TEST-PLAN.md` and `SECURITY.md`. Everything ran in the **sandbox only**: the embedded test database and local demo shops. Throwaway shops and users were created, switched off and purged. The mobile shop's Neon database and any real data were never touched.

## Result

**Bugs found: 9 (1 privacy, 3 security, 2 functional, 3 hardening). All fixed, each with a test.** No data leak between shops was found. Full backend suite after the fixes: **194 / 194 pass**.

| # | Severity | Finding | Fix | Test |
|---|---|---|---|---|
| 1 | **Major (privacy)** | Purging a closed shop deleted its database rows but **left its uploaded files** (CNIC photos, prescription photos, phone photos) in storage forever. SECURITY.md requires them gone. | Storage can now delete a shop's whole folder (local disk and R2), called on every purge and demo reset. It refuses anything outside `shops/{id}/`. | `QaSecurityIT.purgingAShopRemovesEveryRowAndEveryFile`, `storageDeletesOnlyInsideAShopFolder` |
| 2 | **Major (fraud)** | A technician could set a repair part to Rs 1; on hand-back it was billed at that price with no manager PIN for the price change. | A price other than the list price needs the "change prices" permission. | `AbuseIT.technicianCannotPriceRepairPartsBelowTheListPrice` |
| 3 | **Major (fraud)** | The same gap on challans: any user allowed to make challans could set any price. | The same rule. The new-challan screen locks the price box for those users. | `AbuseIT.challanPricesNeedThePriceChangePermission` |
| 4 | **Major (access)** | A user limited to some branches could see and act on installment plans of other branches. | Plans, their due list, receipts and plan creation follow the invoice's branch. | `QaSecurityIT.branchLimitedUserSeesOnlyTheirBranchInTheNewModules` |
| 5 | Major (functional) | Size × colour variants were made **without barcodes**, so they could not be scanned at the till (TC-GAR-01). | Each new variant gets its own in-store EAN-13. | `QaSecurityIT.variantGridMakesTwelveVariantsWithTheirOwnBarcodes` |
| 6 | Minor (privacy) | Patient phone numbers on prescriptions were shown to everyone with "view sales". | Masked unless the user may see personal details or is a pharmacist. | Covered by the isolation and role runs |
| 7 | Minor (functional) | Taking more loyalty points off a customer than they have gave a vague "conflict" error. | Checked first: "The customer has only N points". | `AbuseIT.pointsCannotGoBelowZeroOrBeUsedInFractions` |
| 8 | Hardening | Report "limit" and "days" filters had no upper bound, so one request could ask for millions of rows. | Capped at 5,000 rows and 3,650 days. | `QaSecurityIT.reportLimitsAreCapped` |
| 9 | Hardening | No Content-Security-Policy on the website; no Referrer-Policy on the API; Excel export titles were not protected against formula injection. | Website: `frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'`. API: Referrer-Policy and `frame-ancestors 'none'`. Export titles and header lines escaped like data cells. | `QaSecurityIT.securityHeadersAndNoInternalsInErrors` |

## What was tested

### Automated (backend: unit, integration and architecture)
- **The whole test plan (`QA-TEST-PLAN.md` §5–6).**
  - I mapped all 87 cases to tests. Every case that can run without real hardware or staging has an automated test.
  - Still manual or on staging: speed and load (TC-PRF-01), printers and scanners (TC-PRN-01, label scanning in TC-CAT-05), devices (TC-DEV-01), restore (TC-BKP-01), the 50,000-row export (TC-RPT-02), the timed 40-item basket (TC-POS-01), and the browser-side offline queue and idle lock (TC-POS-07, TC-ACC-06, which have unit tests only).
  - Gaps found and now tested: TC-ACC-01, TC-ACC-02, TC-GAR-01, TC-MED-04, TC-MED-05, TC-SUP-03, TC-CUS-03.
- **Permission matrix (new).**
  - 9 system roles × every shop endpoint (over 200).
  - Each role is refused (403, before anything runs) on every endpoint it has no permission for: more than 500 refused calls, with zero wrong answers.
  - Each role is let into every list it is meant to see.
- **Shop isolation.** As Shop A, every endpoint was called with Shop B's ids (including all Stage 4b ones): all 404. Every list was scanned for Shop B's data: nothing found. Row-level security is on every shop table; the app's database role cannot bypass it.
- **Branch limits** on sales, repairs, installments, challans and deliveries.
- **Sandbox data removal.** A full demo shop (every module, photos included) is purged and checked table by table plus file by file.
  - Users: switched off → their session ends at once and sign-in is refused; the owner can't be switched off.
  - Passwords: lock after 5 wrong tries; password reset, setup link and authenticator (`AccountIT`).
- **Abuse:**
  - Prices changed through side doors, points below zero or in fractions, points without a customer.
  - A challan billed twice, or cancelled after billing.
  - Nonsense offer values, SQL-looking text in searches and coupon codes, missing records.
- **Money and stock exactness.**
  - Stock and balances stay exact after 1,000 random documents.
  - Concurrent sales of the last unit or the same IMEI sell only once.
  - Retries with the same key make one sale.
  - The offer and scale label maths pass the same shared numbers on server and till.

### Security review (static)
| Check | Result |
|---|---|
| SQL built from text | None. The only values joined into SQL are typed numbers (branch ids, checked against the user's branches) or fixed column names. |
| `dangerouslySetInnerHTML`, `eval`, `innerHTML` | None. Printed receipts and documents escape every value. |
| Secrets in the code | None. `.env*` is in `.gitignore`. |
| Stack traces or SQL in errors | Off (`include-stacktrace: never`, checked by a test). |
| Uploads | Type read from the file's bytes (PNG, JPEG, WebP, PDF only), 5 or 10 MB limit, served with `nosniff`. No SVG or HTML. |
| Excel formula injection | Data cells and now titles escaped. |
| CSRF | Required on every change (a POST without a token gets 403). |
| CORS | Only configured origins; an unknown origin gets no CORS headers. |
| Actuator and Swagger | Locked (401), except health. API docs are off in production. |
| Dev outbox (shows reset links) | Exists only in the `local` profile. |
| npm packages | `npm audit`: 0 vulnerabilities. |

## Not done here (and why)

- **Browser (Playwright) re-run after these fixes.** The dev server on :3000 has crashed ("Jest worker encountered 2 child process exceptions") and my attempt to stop it was blocked. Before it crashed, 22 of 23 browser tests passed. The 23rd (pharmacy sale) only needed the new prescription step, which is now in the test.
- **Backend library scan (OWASP dependency-check).** It needs the full NVD download. It goes into the CI pipeline in Stage 5.
- **Manual checklists** (real printers, scanners, phones, Urdu shaping, screen readers), **load test** and **restore test**: Stage 5 and Stage 6, on staging.
- **Outside penetration test**: planned before the first Enterprise client (QA plan §1).

## Recommendations (not bugs)

1. Re-encode uploaded images (strip metadata such as GPS from phone photos of CNICs). SECURITY.md asks for it and it is not done yet.
2. A data-removal request for a single customer (a customer asks to be forgotten). Today a customer can only be switched off; their name stays on old invoices, which the law requires to be kept. Decide the policy before going live.
3. Rate-limit coupon checks at the till (`/pos/coupons/{code}`) like sign-in. Only users with "sell" can call it, so the risk is low.
