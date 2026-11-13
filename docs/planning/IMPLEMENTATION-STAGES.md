# Implementation Stages

The first release of SelectCode POS (every **Phase 1** requirement in REQUIREMENTS-REGISTER.md) is built in **six stages**. They're called *stages* so they don't get mixed up with the release phases (Phase 1/2/3) in the requirements.

| # | Stage | Builds on | Ends with |
|---|---|---|---|
| 1 | Backend A: foundation and setup | — | Tested API for platform, logins, shop setup, users, catalogue |
| 2 | Frontend A: shell, portal and setup screens | Stage 1 | Working screens for everything in Stage 1 |
| 3 | Backend B: business engine | Stage 1 | Tested API for stock, purchasing, POS, sales, customers, cash, reports, shop-type modules, billing |
| 4 | Frontend B: daily-work screens | Stages 2, 3 | Working screens for everything in Stage 3 |
| 5 | Connect and deploy | Stages 1–4 | Full system running on staging (Railway + Vercel + R2) |
| 6 | QA | Stage 5 | Release report: ready for pilot shops |

Backend and frontend alternate on purpose. Each frontend stage is built against a finished, tested backend and its real OpenAPI description, so nothing is built on guesses.

## How each stage runs

1. You say **"start Stage N"**.
2. Claude builds everything listed for that stage, with tests, in this folder (`D:\inventory-management-system`).
3. Claude checks the stage's **done when** list and writes a **stage report** (template at the end).
4. Claude **stops and waits for your approval**. Nothing from the next stage is started.
5. You approve, or ask for changes. Changes are made and reported before the next stage.

A stage is big and may take several working sessions. Progress is kept in the checklist in this file, so work can carry on in a new session.

## Code layout (created in Stage 1)

```
D:\inventory-management-system\
  backend/     Java 21 + Spring Boot 4.1 (Maven)
  frontend/    Next.js 16 + TypeScript (created in Stage 2)
  docs/        these documents
  .github/workflows/
```

---

## Stage 1 — Backend A: foundation and setup

**Goal:** a secure, multi-shop backend skeleton with the super admin platform, shop logins, shop setup and the product catalogue.

**Requirements:** NFR-01, NFR-02, NFR-07, NFR-08, NFR-09 · PLT-01–04, 07–11, 13–15, 17–19, 22–24, 27 · BIL-01, 02, 04, 10 · ONB-01, 04, 07 · ACC-01, 03, 05–10 · SET-01–08 · CAT-01–03, 05–09 · INT-05

**Work:**
- [x] Maven project `backend/` with Spring Boot 4.1, Java 21, Spring Modulith, Actuator, Problem Details, virtual threads, `application.yml`
- [x] Local Postgres: embedded PostgreSQL started by the app itself (no Docker or install needed) and `.env.example`
- [x] Flyway baseline: roles, `app_current_shop()`, platform tables, shop tables, RLS policies
- [x] `shared/tenant`, `shared/security`, `shared/audit`, `shared/money`, `shared/files` (local disk now, R2 in Stage 5), `shared/web` (errors)
- [x] Platform login with TOTP, bootstrap super admin
- [x] Shop types seeded (10 templates) and plans seeded (Starter, Business, Enterprise)
- [x] Shop creation (5-step wizard API), subdomain check, setup link by email (SMS in Stage 3)
- [x] Shop list, profile, module switches (`@RequiresModule`, `ShopFeatures`), suspend/reactivate, close/delete, owner access
- [x] Support session (read-only, audited)
- [x] Subscription record and trial dates; plan limits check
- [x] Shop login, setup/reset tokens, lockout, login history, PIN, session timeout
- [x] System roles and permissions, branch assignment, cost hiding
- [x] `GET /shop/context`
- [x] Shop settings with change history, branches, tills, numbering, payment methods
- [x] Catalogue: products, barcodes, units, categories, brands, images, deactivate, history, bulk price update
- [x] Excel import (dry run + commit) with per-type templates; catalogue export
- [x] Label data endpoint
- [x] Audit log on every write; platform audit log
- [x] Tests: unit tests, `*IT` per endpoint, `TenantIsolationIT`, `ArchitectureTest`, cost-hiding test
- [x] OpenAPI document generated at build
- [x] GitHub Actions: backend build and tests

**Done when:**
- `./mvnw verify` passes with every test green
- `TenantIsolationIT` covers every endpoint and passes
- Super admin can create a shop of each type through the API, and its owner can sign in, set up the shop and add products
- `openapi.json` is produced
- Stage report written

**Needs from you:** nothing (runs on this PC; the database is embedded).

**Status:** complete — see [stage-reports/STAGE-1-REPORT.md](../stage-reports/STAGE-1-REPORT.md).

---

## Stage 2 — Frontend A: shell, portal and setup screens

**Goal:** the app's look, navigation and every screen for Stage 1 features, connected to the real Stage 1 API on this PC.

**Requirements:** the UI side of every Stage 1 requirement · HW-01 (product scanning), HW-03 (labels), HW-06, HW-07

**Work:**
- [x] `frontend/` with Next.js 16, TypeScript strict, Tailwind 4, Mobile Shop design tokens and fonts (Manrope, JetBrains Mono, Noto Nastaliq Urdu), Base UI primitives (the headless layer shadcn now builds on)
- [x] API client generated from `openapi.json` (`npm run gen:api`), TanStack Query, error handling from Problem Details
- [x] Host-based routing: `admin.` → platform portal, `<shop>.` → shop (localhost subdomains in dev)
- [x] App shell: sidebar, phone bottom bar, Ctrl K search, loading skeletons, empty states, module and permission gating (`useFeature`, `usePermission`)
- [x] Auth screens: login (platform with TOTP, shop), setup link, password reset, PIN set — *lock screen moved to Stage 4 (till): quick unlock needs a PIN-unlock endpoint; expired sessions already return to sign-in*
- [x] Platform portal: overview, shop list, 5-step creation wizard, shop profile (modules, plan, owner, suspend, close/delete), shop types, plans, platform users, settings, audit log, support session banner
- [x] Shop setup: business profile, receipts, tax, numbering, payment methods, stock rules, settings history; branches and tills; users, roles, branch assignment, login history, activity history
- [x] Owner first-login checklist
- [x] Catalogue: product list with filters and paging, product form (with type-specific field areas switched by module), barcodes, units, categories, brands, bulk price update with preview, import wizard (upload → row errors → commit), product history, camera and USB scanning into fields
- [x] Barcode label printing page (3 sizes)
- [x] PWA manifest and icons
- [x] Vitest component tests; Playwright journeys for create shop → owner setup → add products
- [x] GitHub Actions: frontend lint, typecheck, tests, build; OpenAPI drift check

**Done when:**
- Lint, typecheck, unit tests and build pass
- Playwright journeys pass against the local backend
- Every screen works at 360 px and on desktop
- Screenshots of the main screens in the stage report

**Needs from you:** approval of the look on the screenshots.

**Status:** complete 2026-11-08 — see [stage-reports/STAGE-2-REPORT.md](../stage-reports/STAGE-2-REPORT.md). Waiting for approval.

---

## Stage 3 — Backend B: business engine

**Goal:** every business rule for daily trading, tested with the shared test numbers.

**Requirements:** STK-01–06 · PUR-01–07 · POS-01–09 (server side) · SAL-01–03, 05, 06 · CUS-01–04 · CSH-01, 03, 04 · RPT-01, 02 (Phase 1 reports), 04 · BRN-03 · ACC-04 · NFR-03–06, 10–13 · ONB-02, 03, 05, 06 · BIL-05–09 · NTF-01, 04, 05 · MOB-01–06 · SUP-01, 02, 04, 07 · MED-01–07, 10 · GAR-01, 02, 04, 05 · ELE-01 · HRD-01, 02 · OTH-01–03 · OPS-01, 05, 13–16

**Work:**
- [x] `StockService` (locks, signed movements, negative check, average cost), `StockAudit` nightly
- [x] Opening stock, adjustments with approval, stock counts, transfers in transit, stock list, history, alerts
- [x] Suppliers, ledger, payments (oldest first); purchase orders, GRN (partial, bonus, bill no.), purchase returns, PO PDF; reorder suggestions → POs
- [x] POS catalogue endpoint (compact, ETag); `SalesService.post` with idempotency key, payments, on-account, cost capture
- [x] Till sessions, cash in/out, Z-report; held sales
- [x] Returns (value clears amount owed first), exchanges, credit notes, same-day cancel; quotations
- [x] Customers, ledger, statements PDF, payments (oldest first), credit limit approval
- [x] Manager PIN approvals (discount, below cost, return, write-off, credit limit)
- [x] Expenses, daily cash summary, P&L
- [x] Dashboard and all Phase 1 reports with Excel/PDF export, background export jobs, owner Excel backup
- [x] Shop-type modules, Phase 1: serials/IMEI, used buyback, warranty; batches, FEFO, expiry block, near-expiry, formula, pack units; weighed items, fast till (server side: compact POS catalogue; the scan-only screen is Stage 4), carton units, multi-till shift closing; variants grid and size sets; multiple units and quotations; bundles and book fields; part numbers
- [x] Notifications outbox: email, SMS through the Android gateway (request format to be confirmed against the school system gateway), in-app alerts
- [x] Subscription invoices, payments, reminders, automatic status job, read-only enforcement, plan upgrade/downgrade rules
- [x] Onboarding checklist data; closed-shop retention job; owner data export; rate limits; terms acceptance
- [x] Demo shop seeders (one per type) and QA seed data
- [x] Tests: every rule with `docs/test-vectors/money.json`; concurrency tests (last unit, same IMEI); random-document stock and balance check; isolation and cost-hiding still green

**Done when:**
- `./mvnw verify` passes
- Acceptance checks TC-STK-01/02/03, TC-MNY-01, TC-PUR-01, TC-MED-01, TC-SEC-01/02/04, TC-BIL-01 pass as automated tests
- Each demo shop type can be seeded and a full trading day run through the API (scripted test)
- Stage report written

**Needs from you:** decision on any business rule the requirements leave open (listed in the report).

**Status:** complete 2026-11-08 — see [stage-reports/STAGE-3-REPORT.md](../stage-reports/STAGE-3-REPORT.md). Waiting for approval.

---

## Stage 4 — Frontend B: daily-work screens

**Goal:** every screen staff use each day, fast at the counter, connected to the Stage 3 API on this PC.

**Requirements:** the UI side of every Stage 3 requirement · HW-02, HW-04 · POS-09 client queue

**Work:**
- [x] Lock screen with quick PIN unlock (moved from Stage 2; needs a PIN-unlock endpoint)
- [x] POS: local catalogue cache (memory + IndexedDB), instant search and scan, cart, quantity and decimals, line and bill discounts, customer pick/quick-add, split payments and change, hold/resume, keyboard shortcuts, manager PIN dialog, retry queue with status, phone layout, fast till mode (super store), IMEI/batch/variant pickers by module
- [x] Till open/close, cash in/out, Z-report
- [x] Receipts: 80 mm, 58 mm, A4; Urdu; reprint; cash drawer kick through the printer
- [x] Sales list and detail, returns, exchanges, cancel, quotations
- [x] Customers: list, profile, ledger, statement, receive payment
- [x] Purchasing: suppliers, POs, receive (GRN with batches/serials), purchase returns, supplier payments, reorder screen
- [x] Stock: stock list, adjustments, counts (scan to count), transfers, history, alerts
- [x] Cash: expenses, daily cash summary
- [x] Dashboard by role; all Phase 1 reports with filters and export
- [x] Shop-type screens: IMEI search, used-phone buyback with photos, warranty claims, near-expiry list, formula search, variant grid, shift closing
- [x] Subscription banner and read-only mode; onboarding checklist progress; data export
- [x] Vitest for cart maths (the money helpers it uses are checked against `money.json`); Playwright journeys: sale, return, purchase, count, till close for each demo type

**Done when:**
- Lint, typecheck, tests and build pass
- Playwright journeys pass against the local backend
- POS search under 100 ms with 50,000 products in the local cache (measured)
- Screenshots and a short screen recording of a sale in the stage report

**Needs from you:** approval of the POS flow.

**Status:** complete 2026-11-09 — see [stage-reports/STAGE-4-REPORT.md](../stage-reports/STAGE-4-REPORT.md). Waiting for approval.

---

## Stage 4b — Phase 2 shop-type modules (added 2026-11-13 at your request)

**Goal:** every default module of the 10 shop types works, so each type is complete before deployment. Testing of all types happens later (Stage 6).

**Requirements:** SUP-03, HW-05 (scale barcodes) · SUP-05, XSL-02 (offers and coupons) · CUS-06, SUP-06 (loyalty) · MOB-07 (repairs) · MOB-08, ELE-03 (installments) · MED-08, MED-09 (prescriptions, controlled medicines) · ELE-04, HRD-04 (delivery, challans)

**Design decisions (change any you disagree with):**
- **Scale barcodes:** EAN-13 starting 20–29: 2 prefix digits, 5-digit item code (the product's *scale code*), 5-digit value, check digit. The shop chooses whether the value is weight (grams) or price. Read at the till and on the server.
- **Offers:** percent off, amount off, buy X get Y free, N for a fixed price, and bill offers (percent or amount off above a minimum spend). For all items, a category or chosen products, with dates, days of the week and hours (happy hour). The best offer per line applies, never two together. Offer discounts don't need manager approval and don't count towards the discount limit. **Coupons** are offers that apply only when their code is entered, with an optional use limit.
- **Loyalty:** customers earn points on what they pay (points per Rs X), redeem points as a payment (Rs per point, minimum points), and points expire after N months without a purchase. Returns and cancels take back points earned and give back points used.
- **Repairs:** job card (customer, device, IMEI, fault, accessories, estimate, advance, technician, promised date), statuses Received → Checking → Waiting for parts → Repairing → Ready → Delivered (or Cancelled/Can't repair), parts and labour on the job, SMS when ready. Delivery posts a normal invoice (parts out of stock, labour as a service) with the advance counted as paid.
- **Installments:** made from an invoice on account: down payment, number of months, markup %, guarantor (name, CNIC, phone, address), late fee per day after a grace period. Schedule, due list, overdue list; receiving an installment is a customer payment that also marks the schedule.
- **Prescriptions:** medicines marked RX need a prescription on the sale when the shop turns that rule on (patient, doctor, photo). **CONTROLLED** medicines need a pharmacist's PIN approval and every sale and return is written into the controlled-medicine register, which can be printed.
- **Delivery:** any invoice can get a delivery (address, date, driver, vehicle, installation; any delivery charge is a line on the invoice) tracked Pending → On the way → Delivered/Installed/Failed. **Challans:** goods sent out on a challan leave stock at once and are later turned into an invoice (or cancelled, stock back).

**Work:**
- [x] Database migration, permissions, numbering, module guards for all seven
- [x] Scale barcodes: product scale code, shop setting, till parsing, server check
- [x] Offers and coupons: setup screens, engine shared by till and server (same test numbers), receipt lines
- [x] Loyalty: settings, earn/redeem at the till, reversal on returns and cancels, expiry job, customer screen
- [x] Repairs: job cards, statuses, parts, SMS, delivery invoice, job card print
- [x] Installments: plan from invoice, schedule, receipts, late fees, due and overdue lists, plan print
- [x] Prescriptions and controlled register: POS checks, pharmacist approval, register screen and print
- [x] Delivery and challans: screens, challan print, convert to invoice
- [x] Demo data in the demo shops for each module; backend tests per module; Playwright page checks

**Done when:**
- Every default module of all 10 shop types has working screens and API
- Backend tests pass for each new rule; lint, typecheck, unit tests and build pass
- Stage report with screenshots

**Needs from you:** any change to the design decisions above.

**Status:** complete 2026-11-13 (screenshots pending a dev-server restart) — see [stage-reports/STAGE-4B-REPORT.md](../stage-reports/STAGE-4B-REPORT.md). Waiting for approval.

---

## Stage 4c — Fast food / restaurant shop type (added 2026-11-14 at your request)

**Goal:** an 11th shop type for burger, pizza, shawarma and food points, with its own modules and staff roles.

**Built:**
- [x] Shop type `fast_food` with menu categories and kitchen units. On by default: recipes, kitchen, deals (bundles), several tills; on Business plans and up also offers and delivery
- [x] **Menu items from recipes** (`recipes`): a menu item lists its ingredients per serving; each sale takes the ingredients out of stock and its cost is what they cost. Deals break down into their menu items' ingredients
- [x] **Kitchen** (`kitchen`): order type (dine-in with table, takeaway, delivery), a token per branch per day, kitchen notes on lines, kitchen tickets and a kitchen screen (New → Preparing → Ready → Collected) that refreshes by itself, kitchen ticket print, token on the receipt
- [x] Roles: **Kitchen staff** (kitchen screen only, no money) and **Rider** (deliveries only), plus the existing cashier and manager
- [x] Returned food is not restocked (recorded as waste); a same-day cancel gives the ingredients back and cancels the ticket
- [x] Demo shop **demo-fastfood** (Burger Point) with ingredients, recipes, deals, history and orders on the kitchen screen
- [x] Tests: `FastFoodIT` (6), shop isolation and the role/permission matrix include the new records and roles, full trading day for all 11 types, Vitest, Playwright `fast-food.spec.ts`

**Status:** complete 2026-11-14. Waiting for approval.

---

## Stage 5 — Connect and deploy

**Goal:** the whole system running on staging exactly as it will in production.

**Work:**
- [ ] Railway project (separate from the school system): `api`, Postgres, `db-backup` cron; private networking
- [ ] Vercel project with wildcard domain and `admin.` domain; `sin1`
- [ ] Cloudflare R2 buckets (files, backups) and keys; switch file storage to R2
- [ ] Email provider and SMS gateway connected
- [ ] Environment variables per DEPLOYMENT-RUNBOOK.md §4; bootstrap super admin, then removed
- [ ] CORS, CSRF, cookies and security headers checked across domains
- [ ] Demo shops created on staging; nightly reset job
- [ ] Monitoring: Sentry, uptime monitors, alerts
- [ ] Backup run and first restore test
- [ ] End-to-end Playwright suite running against staging
- [ ] Speed check on a real phone over 4G; fixes

**Done when:**
- Staging runs a full business day for each demo shop type from a phone and a PC
- Backup and restore test passed
- Alerts arrive when the API is stopped
- Stage report with URLs and test logins (shared privately, not in the repo)

**Needs from you:** Railway, Vercel and Cloudflare account access; the domain (`selectcodepos.com` or chosen alternative) bought and its DNS on Cloudflare; email sender account; an Android phone for the SMS gateway.

---

## Stage 6 — QA

**Goal:** prove the release is ready for pilot shops.

**Work:**
- [ ] Run the full QA-TEST-PLAN.md: every P1 and P2 case, all acceptance checks (§5)
- [ ] Load test (50,000 products, 20 busy tills) and fixes
- [ ] Security review against SECURITY.md, including a tenant-leak hunt by hand
- [ ] Manual checklists: printers, scanners, devices, Urdu, accessibility
- [ ] Fix every Blocker, Critical and Major bug (each with a regression test)
- [ ] Full-day test per demo shop type
- [ ] Release notes and QA release report (go/no-go)
- [ ] Pilot plan: 1 mobile shop, 1 super store, 1 medical store

**Done when:**
- QA exit criteria in QA-TEST-PLAN.md §4 are met
- Release report says **go**, with any accepted Minor bugs listed

**Needs from you:** pilot shop owners for UAT; final go decision.

---

## Stage report template

```
# Stage N report — <name>
Date:
Status: complete / complete with open items

## Done
- <work item> — <requirement IDs>

## Tests
- Backend: X passed, 0 failed (./mvnw verify)
- Frontend: lint ok, typecheck ok, X unit tests, Y Playwright journeys
- Acceptance checks in this stage: <IDs> — pass/fail

## Not done or not checked (and why)
-

## Decisions needed from you
-

## How to try it
<commands and logins on this PC / staging>

Waiting for approval to start Stage N+1.
```
