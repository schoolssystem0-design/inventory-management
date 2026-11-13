# Implementation Plan

How we build SelectCode POS from the requirements (REQUIREMENTS-REGISTER.md) on the locked stack (TECH-STACK.md). Work is split into milestones; each milestone ends with a staging release that passes its QA gate (QA-TEST-PLAN.md §8).

Durations are **estimates for a team of 2 backend + 1 frontend developer + 1 QA**, to be re-planned after Milestone 1.

## 1. Principles

1. **Phase 1 first, end to end.** A thin but complete path (create shop, sell, report) beats half-finished modules.
2. **Port behaviour, not code.** The current build (archived at `D:\inventory-management-system-old-build.zip`; rules summarised in §3 and `test-vectors/money.json`) is the specification for core rules. For each rule, write a backend test with the same numbers before writing the Java (§3).
3. **One stock engine.** Nothing changes stock except `StockService` (ARCHITECTURE.md §6).
4. **Tenant safety from day one.** RLS, `@TenantId` and the isolation test exist before the first business table.
5. **Modules behind switches.** Every type-specific feature is behind its module key from the first commit.
6. **Same patterns as the school system.** Copy `shared/tenant`, `shared/security`, `shared/audit`, `platform` setup-link and TOTP code from `D:\new-school-system` and adapt names (`school` becomes `shop`). Don't reinvent them.

## 2. Milestones

| # | Milestone | Requirement IDs | Estimate |
|---|---|---|---|
| M0 | Foundation | NFR-01, NFR-08, NFR-09 (part), OPS-10, OPS-12 | 2 weeks |
| M1 | Platform core and shop creation | PLT-01–04, 07–11, 13–15, 17, 19, 22–24, 27; BIL-01, 02, 04, 10; ONB-01, 04, 07 | 3 weeks |
| M2 | Shop setup, users and catalogue | ACC-01, 03, 05–10; SET-01–08; CAT-01–03, 05–09; PLT-18, 22; INT-05; HW-01, 03 | 3 weeks |
| M3 | Stock engine and purchasing | STK-01–06; PUR-01–07; NFR-03–06; ONB-03 | 3 weeks |
| M4 | POS, sales, returns, customers | POS-01–09; SAL-01–03, 05, 06; CUS-01–04; ACC-04; HW-02, 04, 06, 07; NTF-01, 04, 05 | 4 weeks |
| M5 | Cash, dashboard and reports | CSH-01, 03, 04; RPT-01, 02 (Phase 1 rows), 04; BRN-03 | 2 weeks |
| M6 | Shop-type modules, Phase 1 | MOB-01–06; SUP-01, 02, 04, 07; MED-01–07, 10; GAR-01, 02, 04, 05; ELE-01; HRD-01, 02; OTH-01–03 | 4 weeks |
| M7 | Billing automation and operations | BIL-05–09; ONB-02, 05, 06; OPS-01, 05, 09, 11, 13–16; NFR-10–13; PLT-17 hardening | 2 weeks |
| M8 | Phase 1 hardening and pilot | All Phase 1 acceptance checks; FBR (INT-01) only if a pilot is Tier-1 | 2 weeks |
| — | **Phase 1 release: first paying shops** | | ~25 weeks |
| M9 | Phase 2 | All Phase 2 IDs, in this order: offers/loyalty/vouchers (super store), repairs/installments (mobile), prescriptions/controlled (medical), garments/electronics/hardware extras, accounting, distribution, offline POS, FBR, WhatsApp, custom roles/domains, leads/resellers, help/tickets | ~20 weeks |
| M10 | Phase 3 | Phase 3 IDs by client demand | ongoing |

## 3. Porting map (current build → new system)

| Current build file | Rule to keep | New home | Port test |
|---|---|---|---|
| `lib/stock.js` `moveStock`, `averageInCost`, `StockError` | Lock row, signed delta, balance after, negative check, weighted average | `stock/app/StockService` | `StockServiceTest`: receive 10 @ 1,850 then 5 @ 1,910 → avg 1,870 (same numbers as the current build's test run) |
| `lib/sales.js` `loadSale`, `unitNetPrice` | Return value of a line = unit price × (sale total ÷ subtotal); new system stores this spread per line (ARCHITECTURE.md §7) | `sales/app/SaleMath` | Returns of discounted lines refund the same amounts as today |
| `app/(app)/pos/actions.js` `createSale` | Validate cart, stock, payments, on-account due, held sale removal | `sales/app/SalesService.post` | POS sale with partial payment leaves the same due |
| `app/(app)/sales/[id]/return/actions.js` | Return value first clears what the customer still owes on that invoice; the most that can be refunded is value − due; any part of that not refunded is kept as a return charge | `sales/app/ReturnService` | 2,150 return: 1,900 debt cleared, 250 refunded |
| `app/(app)/customers/actions.js` `receivePayment` | Oldest invoice first | `customers/app/PaymentAllocator` | Allocation order test |
| `app/(app)/purchases/actions.js` | Partial receive, billed = value received, close short, cancel | `purchasing/app/GrnService`, `PurchaseOrderService` | Partial receive billed 27,820 |
| `app/(app)/stock/reorder` | Target = the larger of 2 × reorder level, last 30 days' sales, or 1; suggested qty = target − on hand − on order, rounded up, never below 0 | `purchasing/app/ReorderService` | Same suggestions for the same data |
| `app/(app)/stock/actions.js` | Count sets absolute qty; adjust with reason; transfers | `stock/app/AdjustmentService`, `TransferService` | Count difference posted as movement |
| `lib/stats.js` | Dashboard figures, stock value, balances | `reports/app/*Queries` | Same totals on seeded data |
| `lib/token.js` `PERMS`, `can()` | admin/manager/staff permissions | `iam` permissions and system roles | Staff never sees cost |
| `app/print/receipt`, `app/print/labels` | Receipt layout, label sizes, EAN/UPC detection | `frontend/src/app/(print)` | Visual check (QA) |
| `components/BarcodeScanner.js`, `ScanInto.js`, `CommandSearch.js` | Camera scan, scan into field, Ctrl K | `frontend/src/components` (TypeScript) | Manual + Playwright |
| `app/globals.css` tokens | Colours, fonts, surfaces | `frontend/src/app/globals.css` | Visual review |

## 4. Work packages

Each work package is one or more pull requests. Tick a box only when the package meets the definition of done (§5).

### M0 Foundation
- [ ] Repository in `D:\inventory-management-system` with `backend/`, `frontend/`, `docs/`, branch protection, CODEOWNERS
- [ ] Backend skeleton: Spring Boot 4.1, Java 21, Modulith, Actuator, Problem Details, virtual threads, `application.yml` like the school system
- [ ] Flyway `V001__baseline.sql`: roles (owner and app login), `app_current_shop()`, `shop`, `platform_user`, `app_user`, `audit_log`, `login_event`, RLS policies
- [ ] `shared/tenant` (TenantContext, TenantIdentifierResolver, TenantAwareDataSource, ShopScopedEntity) ported from the school system
- [ ] `shared/security` (two principals, session JDBC, CSRF, CORS), `shared/audit`, `shared/money` (Money, Qty)
- [ ] `TenantIsolationIT` framework that discovers every endpoint automatically
- [ ] `ArchitectureTest` (Modulith verify + ArchUnit rules)
- [ ] Frontend skeleton: Next 16, TS, Tailwind 4 with Mobile Shop tokens, fonts, shadcn, TanStack Query, API client, `gen:api`, host-based routing middleware, AppShell copied from the current build and typed
- [ ] CI: backend `verify`, frontend lint/typecheck/test/build, OpenAPI drift check
- [ ] Railway staging (API + Postgres), Vercel staging, R2 bucket, Sentry, uptime monitor

### M1 Platform core
- [ ] Platform login with TOTP, bootstrap super admin from env (as school system)
- [ ] Shop types seeded from `resources/shop-types/*.json` (10 types)
- [ ] Plans seeded (Starter, Business, Enterprise)
- [ ] Shop creation wizard API + UI (5 steps), subdomain check, setup link by email/SMS
- [ ] Shop list, profile, module switches, suspend/reactivate, close/delete with code confirmation
- [ ] Subscription record per shop; trial dates
- [ ] Platform overview (counts, revenue placeholders)
- [ ] Support session (read-only) with banner and audit
- [ ] Platform audit log screen

### M2 Shop setup, users and catalogue
- [ ] `GET /shop/context`; `useFeature`, `usePermission` hooks
- [ ] Settings screens (profile, tax, receipts, numbering, payment methods, stock rules) with change history
- [ ] Branches and tills
- [ ] Users, system roles, branch assignment, PIN, password reset, lockout, login history
- [ ] Products with barcodes, units, categories, brands, images (R2), deactivate, history
- [ ] Bulk price update with preview
- [ ] Excel import (dry run with row errors, then commit) with templates per type; mapping screen for other software
- [ ] Barcode labels (3 sizes, EAN/UPC/Code128)
- [ ] Cost hiding serializer and test

### M3 Stock and purchasing
- [ ] `StockService` with tests ported from the current build (§3); `StockAudit` nightly job
- [ ] Opening stock (count screen, posted as `opening`)
- [ ] Adjustments with reasons and approval over limit
- [ ] Stock counts (snapshot, count, review, post)
- [ ] Transfers with dispatch/receive
- [ ] Stock list, history, alerts (low/out/dead)
- [ ] Suppliers, ledger, payments (oldest first)
- [ ] Purchase orders, GRN (partial, bonus, bill number), purchase returns, PO PDF
- [ ] Reorder screen to POs

### M4 POS and sales
- [ ] `/pos/catalog` compact endpoint with ETag; client cache in memory and IndexedDB
- [ ] POS screen: search/scan, cart, qty, line and bill discounts, customer, split payments, change, hold/resume, keyboard shortcuts, phone layout
- [ ] `SalesService.post` with idempotency key, row locks, payments, customer ledger, cost capture
- [ ] Retry queue for failed sends
- [ ] Till sessions, cash in/out, Z-report
- [ ] Receipts: 80 mm, 58 mm, A4 PDF; Urdu; reprint
- [ ] Returns, exchanges, credit notes, same-day cancel
- [ ] Customers, ledger, statements, payments, credit limit approval
- [ ] Quotations (module)
- [ ] Manager PIN approvals across POS
- [ ] SMS via Android gateway (outbox + worker), email, in-app alerts
- [ ] PWA manifest; Ctrl K search

### M5 Cash and reports
- [ ] Expenses, daily cash summary, P&L
- [ ] Dashboard per role
- [ ] Phase 1 reports from REQUIREMENTS-REGISTER RPT-02 (sales, profit, products, stock, adjustments, returns, purchases, customers, suppliers, cash, tax, branch comparison)
- [ ] Excel/PDF export, background export jobs
- [ ] Owner Excel backup

### M6 Shop-type modules, Phase 1
- [ ] `serials` + `used_buyback` + `warranty` (mobile, electronics)
- [ ] `batches` + FEFO + expiry block + near-expiry report + `formula` + `pack_units` (medical, super store)
- [ ] `weighed`, fast till mode, carton units, `multi_till` shift closing (super store)
- [ ] `variants` grid, size sets, variant report, exchange-first returns (garments, cosmetics)
- [ ] `quotations`, multiple units (hardware)
- [ ] `bundles` and book fields (books); `part_numbers` (auto parts)
- [ ] Demo shop seed per type (OPS-01)

### M7 Billing and operations
- [ ] Subscription invoices, payments, reminders, automatic status job, override with reason
- [ ] Plan limits enforcement and upgrade/downgrade rules
- [ ] Read-only mode enforcement (all write endpoints)
- [ ] Onboarding checklist (platform and owner views)
- [ ] Closed shop retention and deletion job; final export
- [ ] Rate limits; terms/privacy acceptance
- [ ] Daily `pg_dump` to R2 with retention; restore procedure tested (DEPLOYMENT-RUNBOOK.md §7)

### M8 Hardening and pilot
- [ ] Load test (NFR-10, NFR-11) and fixes
- [ ] Security review against SECURITY.md checklist
- [ ] Full-day test per demo shop type
- [ ] Pilot: 1 mobile shop, 1 super store, 1 medical store on production for 2 weeks with daily check-ins
- [ ] FBR integration if a pilot shop is Tier-1

## 5. Definition of done (every work package)

- [ ] Requirement IDs listed in the pull request; behaviour matches the PDF wording
- [ ] Backend: unit tests for rules, integration test (`*IT`) for the endpoint, tenant isolation covered automatically, module and permission checks in place
- [ ] Frontend: types from `gen:api`, zod validation, loading skeleton, empty state, error message from `detail`, works at 360 px, keyboard usable
- [ ] Money and quantities use `Money`/`Qty` and string JSON
- [ ] Audit log written for every create/update/cancel
- [ ] Flyway migration is add-only and runs on a copy of staging data
- [ ] No cost/profit in responses for roles without `cost.view`
- [ ] Docs updated (DATA-MODEL, API-CONVENTIONS, module table) if shape changed
- [ ] QA test cases for the IDs pass on staging (QA-TEST-PLAN.md)
- [ ] Reviewed and approved by one other developer

## 6. Risks and how we handle them

| Risk | Effect | Handling |
|---|---|---|
| Tenant leak through a missed filter | Shop sees another shop's data; business-ending | RLS as a second wall; automatic isolation test on every endpoint; review checklist |
| Stock drift from a code path bypassing `StockService` | Wrong stock and profit | ArchUnit rule: only `stock` module writes `product_stock`/`stock_movement`; nightly audit job |
| POS slow on poor internet | Cashiers can't sell | Client-side catalogue cache, retry queue, Phase 2 offline |
| Scope creep from 19 shop types | Phase 1 slips | Phase 1 limited to mobile, super store, medical; others use core plus Phase 1 modules |
| Printer and scanner variety | Support load | Supported hardware list (DEPLOYMENT-RUNBOOK.md §10); print helper in Phase 2 |
| FBR rules change | Tier-1 clients blocked | FBR module isolated behind an interface; retry queue; monitored sync status |
