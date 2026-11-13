# Stage 1 report — Backend A: foundation and setup

Date: 2026-11-08
Status: **complete**

## Done

| Area | What was built | Requirement IDs |
|---|---|---|
| Foundation | Spring Boot 4.1 / Java 21 project, Spring Modulith modules (`shared`, `platform`, `iam`, `shop`, `catalog`), Problem Details errors with lowercase codes, request IDs, rate limiting, virtual threads, Dockerfile, Maven wrapper | NFR-08, OPS-13 |
| Database | Flyway V001–V003: platform tables, shop users, shop setup, catalogue, seeds for 10 shop types, 3 plans (Rs. 2,500 / 5,500 / 12,000) and platform settings. Money `numeric(14,2)`, quantities `numeric(14,3)` | NFR-02, NFR-08, BIL-01, BIL-02 |
| Shop separation | Three layers as in the school system: Hibernate `@TenantId`, PostgreSQL row-level security on every shop table (app runs as a restricted role), composite foreign keys that cannot point at another shop's rows | NFR-01 |
| Platform sign-in | Email + password + authenticator code; first sign-in enrols the authenticator; lockout after 5 failures; bootstrap super admin from env vars; platform roles Super admin / Support / Billing / Onboarding with their own capabilities | PLT-01, 02, 03, 23 |
| Shops | 5-step creation (details, type, plan, starting data, owner) in one call; code check with suggestion and reserved names; shop list with search and filters; profile with plan, limits, usage, modules, branches, owner; edit details; module switches (super admin any, owner only offered ones); plan change re-applies module rules; suspend (users signed out at once), reactivate, close with code confirmation (data kept 90 days) | PLT-07..15, 19, 22, BIL-04, 07 (statuses), 08, 10, ONB-04, 07 |
| Owner access | Setup link (48 h, emailed, one-time), resend, change owner with reason | PLT-13, ONB-01 |
| Support mode | Support session with reason; read-only until the owner's 1-hour approval code; banner in the shop; every change in shop and platform audit logs | PLT-17 |
| Platform admin | Overview (counts by status/type/city, monthly recurring revenue, overdue, trials and payments due in 7 days), platform users, settings, audit log, client product import | PLT-04, 18, 23, 24, 27 |
| Shop sign-in | Shop code + username + password (+ authenticator code if turned on); lockout; login history; setup and reset links; temporary passwords that must be changed; change password ends other sessions; approval PIN; session timeout from shop settings; read-only shops can view but not change | ACC-06, 08, 09, 10, ONB-05 |
| Users and roles | Users with role and branch limits; 8 system roles + Pharmacist / Technician when their modules are on; owner protected; deactivation signs out at once; role change applies on next click; cost and profit hidden without `cost.view`; PIN approval service (used by the till in Stage 3) | ACC-01, 03, 04 (service), 05, 07 |
| Shop setup | Settings (business details, tax, receipts, stock rules, discount and write-off limits, timeout) with change history; branches and godowns; tills; document numbering (only goes up); payment methods (cash always on); logo upload checked by file content | SET-01..08 |
| Catalogue | Products with Urdu name, auto SKU, several barcodes, pack units, wholesale/min price, tax, reorder level, image; medicine, part-number and book fields behind their modules; search by name, Urdu name, SKU, barcode, formula, part numbers; price history; stale-edit protection; bulk price update with preview and rounding; deactivate/activate; in-store barcode generation; label data with the right format (EAN-13/UPC/EAN-8/Code 128) | CAT-01..09 |
| Import / export | Excel and CSV import with dry run (every row problem listed), all-or-nothing commit, update by SKU, auto-creates categories/brands/units, plan limit check, formula-injection safe export, per-type template | CAT-06, INT-05, PLT-18, PLT-22 |
| Context | `GET /shop/context`: shop, user, permissions, modules, allowed branches, plan limits and usage, settings, support and trial/overdue banners | — |
| CI | GitHub Actions: build, all tests, OpenAPI drift check | OPS-10 |

The full list of 98 API operations is in `backend/openapi.json` and summarised in [API-CONVENTIONS.md §4a](../architecture/API-CONVENTIONS.md).

## Tests

`./mvnw verify` — **68 tests, 0 failures** (1 min 48 s):

| Suite | Tests | What it proves |
|---|---|---|
| `ArchitectureTest` | 7 | Module boundaries, no float/double, every shop entity is shop-scoped, every endpoint declares its access rule, controllers don't touch repositories, only the platform module can read across shops, unique API type names |
| `UnitRulesTest` | 6 | Money/quantity/percent parsing, barcode formats and check digits, password policy, authenticator codes, catalogues |
| `TenantIsolationIT` | 13 | Each separation layer on its own, **plus every shop endpoint called as Shop A with Shop B's ids → 404 and Shop B unchanged**, and every list endpoint free of Shop B's data (QA TC-SEC-01) |
| `CostHidingIT` | 3 | **Every GET endpoint as a cashier: no cost/profit value anywhere** (TC-SEC-04); role permissions; price change needs `catalog.prices` |
| `PlatformIT` | 16 | Sign-in with authenticator, all 10 shop types created with the right modules/categories/units/roles/owner email, setup link one-time, code check, plan limits, module switches gate the API, owner module switches, suspend/reactivate, read-only, close, plan change, owner change, support mode end to end, overview/users/settings/audit |
| `AccountIT` | 8 | Lockout and login history, reset by email, password rules, authenticator on/off, users/roles/branches, setup link for new user, PIN approval and lockout, session timeout |
| `ShopSetupIT` | 4 | Settings validation and history, logo upload by content, branches and tills, numbering and payment methods |
| `CatalogIT` | 9 | Products and barcodes, price history and stale edits, deactivate, module fields, bulk price, import/export, plan limit and platform import, labels, category tree |
| `SmokeIT`, `OpenApiExportIT` | 2 | App starts and a shop works end to end; OpenAPI written |

Bugs the tests caught and that are fixed: product search failed with a database error (missing space in SQL); changing a shop's modules or plan failed (role update ran outside the shop context); the login-page lookup crashed for shops without a logo.

Live check on this PC: API started with demo data, owner signed in, shop context and product list answered (product list in 30 ms).

## Not done or not checked (and why)

- **Customers and suppliers import** for client onboarding (PLT-18) — those tables arrive in Stage 3; product import works now.
- **SMS** for setup links — email only now; the Android SMS gateway comes in Stage 3 (NTF-01).
- **Files go to local disk**; Cloudflare R2 is switched on in Stage 5 (code for R2 is already in place).
- **Custom domains** (PLT-16) and **custom roles** (ACC-02) are Phase 2 items, not in this stage.
- **Deleting closed shops after 90 days** is a Stage 3 job (ONB-06); the date is already recorded.
- The module cache and rate limiter are per server; fine for one backend replica. Two replicas (production plan) need a shared cache — noted for Stage 5.
- Platform users created by a super admin get a temporary password but are not forced to change it (they must enrol the authenticator). Can be tightened later.
- Code is **not committed to git yet** — waiting for your go-ahead.

## Decisions needed from you

1. May I `git init` and commit the Stage 1 code (and create a GitHub repository when you give access)?
2. Shop codes become subdomains (`alimobiles.selectcodepos.com`). Reserved words: admin, www, api, app, mail, staging, support, help, status, platform, static, assets, cdn, dev, test, demo. Add any others?

## How to try it

```bash
cd D:\inventory-management-system\backend
mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"
```

- Shops: `demo-mobile`, `demo-mart`, `demo-pharmacy` — username `owner`, password `Demo-Shop-2026`
- Super admin: `admin@local.test` / `Admin-Local-2026` / authenticator secret `JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`
- API description: http://localhost:8080/v3/api-docs · emails: http://localhost:8080/api/v1/dev/outbox

The screens come in Stage 2.
