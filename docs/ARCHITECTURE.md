# Architecture

## 1. System at a glance

```
 Users (owner, cashier, ...)        Super admin / support
          |  HTTPS                          |  HTTPS
          v                                 v
  shopcode.selectcodepos.com        admin.selectcodepos.com
  +-------------------------------------------------------+
  |  Vercel: Next.js 16 frontend (one app, two areas)     |
  |  /(shop)/...  shop screens   /(platform)/... portal   |
  +-------------------------------------------------------+
          |  HTTPS, session cookie, CORS = our domains only
          v
  +-------------------------------------------------------+
  |  Railway: Spring Boot API  (/api/v1/...)              |
  |  platform | iam | catalog | stock | sales | purchasing|
  |  customers | suppliers | cash | reports | ...         |
  +-------------------------------------------------------+
          | private network                 | S3 API
          v                                 v
  Railway PostgreSQL (RLS on)        Cloudflare R2 (files, PDFs, daily dumps)
```

- The browser never talks to the database. Only the API does, over Railway's private network.
- The shop is found from the host name (`ali.selectcodepos.com` or a verified custom domain). A session belongs to exactly one shop.
- One frontend deployment serves both the shop app and the platform portal. Next.js middleware routes on the host: `admin.` goes to the portal, anything else to a shop.

## 2. Repository layout

```
D:\inventory-management-system\
  backend/
    pom.xml, Dockerfile, mvnw
    src/main/java/com/selectcode/pos/
      PosApplication.java
      shared/        tenant, security, audit, money, files, pdf, excel, messaging, events, web, numbering, persistence
      platform/      shops, shop types, plans, subscriptions, platform users, support mode, leads, resellers
      iam/           shop users, roles, permissions, login, PIN, sessions, login history
      shop/          shop profile, branches, settings, numbering, tax, modules on/off (ShopFeatures)
      catalog/       products, categories, brands, units, barcodes, price levels, variants, labels, import
      stock/         stock engine, movements, adjustments, counts, transfers, batches, serials (IMEI)
      sales/         POS, invoices, payments, returns, exchanges, holds, tills/sessions, quotations
      customers/     customers, ledger, credit, loyalty
      purchasing/    suppliers, purchase orders, GRN, purchase returns, supplier payments, reorder
      cash/          expenses, cash/bank accounts, daily cash summary
      accounting/    chart of accounts, journals, cheques (Phase 2+)
      promotions/    offers, coupons, gift vouchers (Phase 2)
      repairs/       job cards (mobile/electronics, Phase 2)
      installments/  plans, schedules, recovery (Phase 2)
      pharmacy/      prescriptions, controlled register, formula (medical)
      distribution/  routes, order booking, load sheets, recovery (Phase 2)
      notifications/ SMS gateway, email, WhatsApp, in-app alerts
      reports/       read-only report queries and exports
      fbr/           FBR POS integration (Phase 2)
    src/main/resources/
      application.yml
      db/migration/V001__baseline.sql ...
      fonts/ (Noto Nastaliq Urdu for PDFs)
    src/test/java/...   unit tests, *IT integration tests, ArchitectureTest
  frontend/
    package.json, next.config.ts, playwright.config.ts, vitest.config.mts
    src/
      app/(platform)/...    super admin portal routes
      app/(shop)/...        shop routes: dashboard, pos, sales, products, stock, ...
      app/(auth)/...        login, setup link, password reset
      features/<module>/    screens, hooks, forms per module (mirrors backend modules)
      components/ui/        shadcn components
      components/shell/     AppShell, nav, command search (Ctrl K), bottom bar
      lib/api/              fetch client, schema.d.ts (generated), query keys
      lib/money.ts, lib/format.ts, lib/features.ts (module switches)
    tests/ (Playwright)
  docs/   (this folder)
  .github/workflows/
```

Each backend module has `api/` (controllers and request/response records), `app/` (services, the module's public API) and `domain/` (entities, repositories). Other modules may call only the `app` services a module exposes, or listen to its events. `ArchitectureTest` (ArchUnit + Spring Modulith `verify()`) fails the build on any other dependency.

## 3. Multi-tenancy

All shops share one database. Isolation is enforced **three times**, as in the school system:

1. **Hibernate `@TenantId`.** Every tenant entity extends `ShopScopedEntity` with `@TenantId Long shopId`. `TenantIdentifierResolver` supplies the current shop from `TenantContext`. Inserts get the shop stamped; queries get filtered. No shop in context means the special id `-1`, which matches nothing.
2. **PostgreSQL row-level security.** Every tenant table has `ENABLE ROW LEVEL SECURITY` and a `tenant_isolation` policy on `shop_id = app_current_shop()`. `TenantAwareDataSource` runs `set_config('app.shop_id', ?, false)` on every connection checkout. The app connects as a restricted login role that is subject to RLS. Flyway runs as the owner role.
3. **Tests.** `TenantIsolationIT` calls every endpoint as Shop A against Shop B's ids and expects 404 (QA-TEST-PLAN.md, TC-SEC-01).

Cross-shop reads exist only for the platform overview. They use `TenantContext.allShops()` (`app.all_shops = true`), are allowed only for platform principals, and return counts and totals, never customer data.

**Branch scoping** inside a shop is a normal query filter, not RLS. `CurrentActor.branchIds()` limits branch-scoped queries for users tied to branches.

## 4. Shop types and module switches

- `platform.shop_type` holds the defaults: modules, product field sets, categories, units and the Excel template.
- `shop_module(shop_id, module_key, enabled, source)` holds the effective switches for a shop (`source` = `type`, `plan` or `override`).
- `ShopFeatures` (in `shop/app`) answers `isOn(ModuleKey)`. It is cached per shop and evicted when a switch changes.
- **Server enforcement:** controllers of a switchable module carry `@RequiresModule(ModuleKey.IMEI)`. An interceptor returns `403 module_disabled`. Services that branch on a module (e.g. POS asks for a batch) call `ShopFeatures`.
- **Plan enforcement:** `PlanLimits` checks counts (users, branches, products, tills) before create and returns `402 plan_limit` with the limit name.
- **Frontend:** `GET /api/v1/shop/context` returns the shop, branch list, user, permissions and enabled modules. `useFeature('imei')` hides menu items, form fields and columns. Hiding is cosmetic; the server is the gate.

Full key list and defaults: SHOP-TYPES-AND-MODULES.md.

## 5. Authentication and authorisation

| | Platform users | Shop users |
|---|---|---|
| Login URL | `admin.selectcodepos.com/login` | `<shop>.selectcodepos.com/login` |
| Principal | `PlatformPrincipal` (super_admin, support, billing, onboarding, reseller) | `ShopPrincipal` (shop_id, user_id, role, permissions, branch_ids) |
| Second factor | TOTP required | TOTP optional (owner setting) |
| Session | Spring Session JDBC, idle timeout 30 min | Idle timeout setting (default 10 min); POS screen locks with PIN instead of signing out |
| First password | One-time setup link (48 h) | One-time setup link, or a temporary password the user must change |

- **Permissions** are strings such as `sales.create`, `sales.discount`, `stock.adjust`, `cost.view` and `reports.view`. Roles are bundles of permissions, and owners can copy and edit roles (Phase 2). Controllers use `@PreAuthorize("hasAuthority('stock.adjust')")`.
- **Cost hiding:** response records that carry cost or profit get them from `CostView.of(actor, value)`, which returns `null` without `cost.view`. A JSON serializer test makes sure no cost field leaks (TC-SEC-04).
- **Manager PIN (step-up):** actions over a limit (discount, return, write-off, price below cost, credit limit breach) need `X-Approval: <approverUserId>:<pin>` checked by `ApprovalService`. The approval is recorded on the document.
- **Support mode:** a platform user opens a shop through `POST /api/v1/platform/shops/{id}/support-session`. That creates a read-only `ShopPrincipal` flagged `support=true`. Writes need an approval code from the owner. Every request is audited, and the shop UI shows a banner.
- **Login protection:** 5 failed attempts lock the account for 15 minutes. Login events are stored with IP and user agent.

## 6. Stock engine (the heart)

One service, `StockService` in `stock/app`, is the only code that changes stock. It is ported from `lib/stock.js` in the current build.

```
StockService.move(MoveCommand cmd)   // inside the caller's transaction
  1. SELECT ... FROM product_stock WHERE shop_id, product_id, branch_id[, batch_id] FOR UPDATE
     (row created with qty 0 if missing: INSERT ... ON CONFLICT DO NOTHING, then lock)
  2. new_qty = qty + delta; if new_qty < 0 and !settings.allowNegative -> throw StockException(product, available)
  3. UPDATE product_stock SET qty = new_qty
  4. INSERT stock_movement(type, delta, balance_after, unit_cost, doc_type, doc_id, user_id, branch_id, batch_id, serial_id)
StockService.averageInCost(product, qtyIn, unitCost)   // called before move() on receipts
  lock the product row; on_hand = stock across ALL branches of the shop
  new_avg = (max(on_hand,0) * avg + qtyIn * unitCost) / (max(on_hand,0) + qtyIn), rounded to 2 dp
  (if on_hand <= 0, new_avg = unitCost)
```

- Callers (sales, purchasing, adjustments, transfers, returns) open one `@Transactional` method per business document. The document, its lines, the stock moves, payments and ledger entries commit together or not at all.
- **Lock order:** lines are sorted by `(product_id, branch_id, batch_id)` before locking, so two documents never deadlock.
- **Serials (IMEI):** `serial_unit` rows carry `status` (`in_stock`, `sold`, `returned`, `reserved`, `defective`) with a unique index on `(shop_id, serial)` for active units. A sale locks the serial row and moves it; a second till selling the same IMEI fails cleanly.
- **Batches:** `batch` rows (`product`, `batch_no`, `expiry`, cost). Stock is kept per batch when the `batches` module is on. FEFO picking is done by `BatchPicker`. Expired batches are refused unless an adjustment writes them off.
- **Rebuildable:** `StockAudit.verify(shop)` sums movements and compares them to `product_stock`. It runs nightly and alerts on any difference.

## 7. Money

- `Money` value type (`BigDecimal`, scale 2, `RoundingMode.HALF_UP`) in `shared/money`. Quantities use scale 3.
- Sale totals: line net = qty × price − line discount. Bill discount is spread across lines in proportion, with the last line taking the rounding remainder. Tax is calculated per line and then summed. Each line stores its net and tax, so a return refunds exactly what that line earned. (The current build has no line discounts and works this out on the fly as `unit_price × total ÷ subtotal` in `lib/sales.js` `unitNetPrice`. The stored-per-line way gives the same result, except that rounding lands on the last line.)
- Balances are stored as running figures (`customer.balance`, `supplier.balance`) **and** can be rebuilt from ledgers. A nightly job compares them.

## 8. Documents, numbering and immutability

- Document numbers come from `numbering` (`shop_id, branch_id, doc_type, prefix, next_no`), taken with `SELECT ... FOR UPDATE`. Gaps are allowed only when a transaction rolls back.
- Posted documents (invoice, GRN, return, adjustment, payment) are never updated or deleted. Corrections are new documents (return, reversal, cancellation with a reason). An invoice can be cancelled the same day by a manager, which writes reversing movements.
- Every write goes through `AuditService.record(action, entity, before, after)`.

## 9. Files and PDFs

- Uploads (logos, product images, CNIC photos, prescriptions) go to R2 under `shops/{shopId}/{kind}/{uuid}`. They're served by pre-signed URL after a permission check. Images are resized on upload and limited to 5 MB.
- PDFs (A4 invoices, POs, statements, registers) are made by OpenPDF in `shared/pdf` and streamed, not stored, unless they're emailed.
- Thermal receipts are HTML pages printed by the browser: `app/(print)/receipt/[id]` at 80 mm or 58 mm width, with Urdu by font.

## 10. Background jobs

Spring `@Scheduled` with a Postgres advisory lock (only one instance runs each job):

| Job | When | What |
|---|---|---|
| Subscription status | 00:30 daily | Trial/Active/Grace/Read-only moves; reminders 7/3/1 days |
| Low stock and expiry alerts | 06:00 daily | In-app alerts; owner summary |
| Owner daily summary | Shop closing time setting | SMS/WhatsApp/email |
| Stock and balance audit | 03:00 daily | `StockAudit.verify`, ledger vs balance check |
| Database dump to R2 | 02:00 daily | `pg_dump` from a Railway cron service, kept 30 days |
| FBR retry | Every 5 min | Resend invoices not yet accepted |
| Demo shop reset | 04:00 daily | Restore demo shops from seed |

## 11. Frontend structure and speed

- **Server state** lives in TanStack Query. Query keys are `[shopId, module, ...]`. Lists use `keepPreviousData` for instant paging.
- **POS** loads the branch's sellable product list once (id, name, barcodes, price, stock, flags) into memory and IndexedDB, refreshed every 60 s and after each sale. Search and scan run locally in under 100 ms. The sale is sent to the API with an `Idempotency-Key`. If the network fails, it's queued and retried (Phase 1). Full offline mode is Phase 2.
- **Navigation:** every route has a `loading.tsx` skeleton and links prefetch, matching the current build's feel.
- **Command search (Ctrl K)** calls `GET /api/v1/search?q=` across products, customers, suppliers, invoices and pages.
- **Printing:** `window.print()` on dedicated print routes. The Phase 2 print helper app adds silent printing.
- **PWA:** manifest and icons so the app installs on desktop and phone. The service worker caches the app shell only.

## 12. Environments

| | Frontend | Backend | Database |
|---|---|---|---|
| Local | `npm run dev` (HTTPS option for camera) | `./mvnw spring-boot:run` | Local Postgres 16 or Docker |
| Staging | Vercel preview/staging domain | Railway staging environment | Railway staging Postgres (demo data only) |
| Production | Vercel production | Railway production | Railway production Postgres |

Details: DEPLOYMENT-RUNBOOK.md.
