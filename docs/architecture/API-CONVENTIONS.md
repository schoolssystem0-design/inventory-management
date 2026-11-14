# API Conventions

REST over HTTPS, JSON, described by OpenAPI (springdoc). The frontend never hand-writes request types: run `npm run gen:api` after backend changes. CI fails if `openapi.json` and `schema.d.ts` are out of date.

## 1. URLs

| Area | Prefix | Who |
|---|---|---|
| Shop API | `/api/v1/...` | Shop users. The shop comes from the session, never from the URL |
| Platform API | `/api/v1/platform/...` | Platform users only |
| Public | `/api/v1/public/...` | No login: shop branding for the login page, setup-link check, lead form |
| Auth | `/api/v1/auth/...` and `/api/v1/platform/auth/...` | Login, logout, setup, reset, TOTP |

- Plural nouns: `/products`, `/sales/{id}`, `/sales/{id}/returns`.
- Actions that aren't plain create/update are sub-resources with `POST`: `/purchase-orders/{id}/receive`, `/sales/{id}/cancel`, `/stock-counts/{id}/post`.
- IDs are numbers. Document numbers (`INV-000123`) are for display and search, not for URLs.

## 2. Requests and responses

| Topic | Rule |
|---|---|
| Money | String with 2 decimals: `"1250.00"`. Parsed to `BigDecimal`. Never a JSON number |
| Quantity | String with up to 3 decimals: `"2.500"` |
| Dates | `date` = `"2026-11-08"` (shop time zone); `timestamp` = ISO 8601 UTC `"2026-11-08T07:15:00Z"` |
| Nulls | Optional fields may be `null`; cost fields are `null` when the user lacks `cost.view` |
| Lists | `GET /products?q=&categoryId=&page=0&size=50&sort=name,asc` returns `{ "items": [...], "page": 0, "size": 50, "total": 1234 }`. Max size 200 |
| Large exports | `POST /reports/{key}/export` returns `202` plus a job id; the file is emailed or downloaded from `/exports/{jobId}` |
| Validation | Bean Validation on request records; the same rules in zod on the client |
| Concurrency | Editable master data carries `version`; a stale update returns `409 stale_version` |
| Idempotency | `POST /sales`, `/customer-payments`, `/supplier-payments` and `/grns` require an `Idempotency-Key` header (UUID made by the client). The same key returns the first result instead of creating a duplicate |
| Approvals | Over-limit actions send `X-Approval-User` and `X-Approval-Pin`; missing or wrong gives `403 approval_required` with the limit that was hit |

## 3. Errors

RFC 9457 Problem Details (`spring.mvc.problemdetails.enabled: true`), always with a stable `code`:

```json
{
  "type": "about:blank",
  "title": "Not enough stock",
  "status": 409,
  "code": "insufficient_stock",
  "detail": "Only 2 of Samsung A15 128GB left at Main Shop.",
  "errors": [{ "field": "lines[0].qty", "code": "insufficient_stock", "available": "2.000" }]
}
```

| Status | `code` examples |
|---|---|
| 400 | `validation_failed` (with field `errors`), `malformed_request` |
| 401 | `not_signed_in`, `session_expired` |
| 402 | `plan_limit` (with `limit`: users, branches, products, tills), `subscription_read_only` |
| 403 | `forbidden`, `module_disabled`, `approval_required`, `support_read_only` |
| 404 | `not_found` (also for another shop's ids: never reveal they exist) |
| 422 | business rules: `link_invalid`, `default_branch`, `import_has_errors` (with row `errors`), `category_cycle`, `no_filter`, `file_type_not_allowed` |
| 409 | `insufficient_stock`, `serial_unavailable`, `batch_expired`, `duplicate_barcode`, `duplicate_sku`, `stale_version`, `credit_limit_exceeded`, `already_returned` |
| 423 | `account_locked`, `shop_suspended` |
| 429 | `rate_limited` |

`detail` is written for the cashier (plain words, product and branch names). The frontend shows it as is.

## 4. Endpoint list (Phase 1)

### Auth and context
`POST /auth/login` · `POST /auth/logout` · `POST /auth/setup` (token + new password) · `POST /auth/reset-request` · `POST /auth/reset` · `POST /auth/pin` (set own PIN) · `POST /auth/totp/enroll|verify` · `GET /shop/context` (shop, branches, user, permissions, modules, plan limits, subscription banner)

### Platform
`GET /platform/overview` · `GET|POST /platform/shops` · `GET|PATCH /platform/shops/{id}` · `POST /platform/shops/{id}/suspend|reactivate|close|delete` · `PUT /platform/shops/{id}/modules` · `GET /platform/shops/{id}/users` · `GET /platform/shops/{id}/roles` · `PATCH /platform/shops/{id}/users/{userId}` · `POST /platform/shops/{id}/users/{userId}/reset-password` · `POST /platform/shops/{id}/owner/setup-link` · `POST|DELETE /platform/shops/{id}/domains` · `POST /platform/shops/{id}/support-session` · `POST /platform/shops/{id}/import` · `GET|POST|PATCH /platform/shop-types` · `GET|POST|PATCH /platform/plans` · `GET /platform/subscriptions` · `POST /platform/subscriptions/{id}/invoices` · `POST /platform/subscription-invoices/{id}/payments` · `GET|POST /platform/users` · `GET /platform/audit` · `GET|PATCH /platform/settings`

### Shop setup and users
`GET|PATCH /settings` · `GET|POST|PATCH /branches` · `GET|POST|PATCH /tills` · `GET|POST|PATCH /users` · `POST /users/{id}/reset-password|activate|deactivate` · `GET /roles` · `GET /login-events` · `GET /audit`

### Catalogue
`GET|POST /products` · `GET|PATCH /products/{id}` · `POST /products/{id}/deactivate` · `GET /products/{id}/history` · `POST /products/bulk-price` (preview flag) · `POST /products/import` (multipart; `dryRun=true` returns row errors) · `GET /products/import/template` · `GET /products/lookup?barcode=` · `GET /pos/catalog?branchId=` (compact list for the till; ETag) · `GET|POST|PATCH /categories|brands|units` · `POST /labels` (returns label data for printing)

### Sales
`POST /till-sessions` (open) · `POST /till-sessions/{id}/close` · `POST /till-sessions/{id}/cash-movements` · `POST /sales` · `GET /sales` · `GET /sales/{id}` · `POST /sales/{id}/cancel` · `GET /sales/{id}/receipt` (print data) · `GET /sales/{id}/pdf` · `POST /sales/{id}/returns` · `POST /exchanges` · `GET|POST|DELETE /held-sales` · `GET|POST /quotations` · `POST /quotations/{id}/convert`

### Customers
`GET|POST|PATCH /customers` · `GET /customers/{id}/ledger` · `GET /customers/{id}/statement.pdf` · `POST /customer-payments` · `GET /customers/search?q=`

### Purchasing
`GET|POST|PATCH /suppliers` · `GET /suppliers/{id}/ledger` · `GET|POST /purchase-orders` · `POST /purchase-orders/{id}/receive|close|cancel` · `GET /purchase-orders/{id}/pdf` · `GET|POST /grns` · `GET|POST /purchase-returns` · `POST /supplier-payments` · `GET /reorder` · `POST /reorder/purchase-orders`

### Stock
`GET /stock?branchId=&q=` · `GET /stock/movements` · `GET|POST /adjustments` · `GET|POST /stock-counts` · `PUT /stock-counts/{id}/lines` · `POST /stock-counts/{id}/post` · `GET|POST /transfers` · `POST /transfers/{id}/receive` · `GET /alerts` · `POST /alerts/{id}/seen` · `GET /serials/{serial}` · `GET /batches?productId=`

### Cash and reports
`GET|POST /expenses` · `GET /cash/daily?date=&branchId=` · `GET /dashboard` · `GET /reports/{key}?from=&to=&branchId=...` · `POST /reports/{key}/export` · `GET /search?q=` · `GET /backup.xlsx` (owner)

Phase 2+ modules add their own groups (`/repairs`, `/installments`, `/promotions`, `/prescriptions`, `/routes`, `/orders`, `/accounts`, `/journals`, `/cheques`, `/fbr`), each guarded by `@RequiresModule`.

## 4a. Built in Stage 1 (exact paths)

The list above is the plan; these are the Stage 1 endpoints as built (the OpenAPI document `backend/openapi.json` is the full reference):

| Area | Endpoints |
|---|---|
| Shop auth | `GET /auth/csrf` · `POST /auth/login` {shopCode, username, password, totpCode?} · `POST /auth/logout` · `POST /auth/change-password` · `POST /auth/setup` · `POST /auth/password-reset/request` · `POST /auth/password-reset/confirm` · `POST /auth/pin` · `POST /auth/totp/start` · `POST /auth/totp/confirm` · `POST /auth/totp/disable` |
| Context | `GET /shop/context` · `GET /modules` · `PUT /modules/{key}` (owner switches offered modules) · `GET /public/shops/{code}` (login page branding) |
| Users | `GET/POST /users` · `GET/PATCH /users/{id}` · `POST /users/{id}/activate|deactivate|reset-password` · `GET /roles` · `GET /permissions` · `GET /login-events` · `GET /audit` |
| Shop setup | `GET/PATCH /settings` · `GET /settings/history` · `POST /settings/support-code` · `GET/POST /branches` · `PATCH /branches/{id}` · `POST /branches/{id}/activate|deactivate|make-default` · `GET/POST /tills` · `PATCH /tills/{id}` · `GET /numbering` · `PATCH /numbering/{id}` · `GET /payment-methods` · `PATCH /payment-methods/{id}` · `POST /files` (multipart, `purpose`) · `GET /files/{id}` |
| Catalogue | `GET/POST /products` · `GET/PATCH /products/{id}` · `GET /products/{id}/history` · `GET /products/lookup?code=` · `POST /products/{id}/activate|deactivate|generate-barcode` · `POST /products/bulk-price/preview` · `POST /products/bulk-price` · `GET /products/import/template` · `POST /products/import` (multipart, `dryRun`) · `GET /products/export` · `POST /labels` · `GET/POST /categories|brands|units` · `PATCH /categories|brands|units/{id}` |
| Platform | `POST /platform/auth/login` · `POST /platform/auth/totp/enroll` · `GET /platform/auth/me` · `GET /platform/overview` · `GET /platform/shop-types` · `GET /platform/plans` · `GET /platform/modules` · `GET/POST /platform/shops` · `GET /platform/shops/code-check` · `GET/PATCH /platform/shops/{id}` · `PUT /platform/shops/{id}/modules` · `PATCH /platform/shops/{id}/subscription` · `POST /platform/shops/{id}/suspend|reactivate|close` · `POST /platform/shops/{id}/owner/setup-link` · `POST /platform/shops/{id}/owner/change` · `POST /platform/shops/{id}/import/products` · `POST/DELETE /platform/support-session` · `POST /platform/support-session/approve` · `GET/POST /platform/users` · `PATCH /platform/users/{id}` · `POST /platform/users/{id}/reset` · `GET/PATCH /platform/settings` · `GET /platform/audit` |

## 4b. Built in Stage 3 (exact paths)

Stage 3 added 147 operations (245 in total); `backend/openapi.json` is the full reference.

| Area | Endpoints |
|---|---|
| Stock | `GET /stock` (on hand with cost and retail value; `filter=low,out,negative,instock`) · `GET /stock/products/{id}` (per branch, batch, in transit) · `GET /stock/movements` (history with balance after) · `GET /stock/audit` (stock vs history) · `GET /batches` · `GET /serials` (in stock by default; with `q` or `status=ALL` every status, so an IMEI search finds sold phones) · `GET /serials/{serial}` (IMEI history) · `GET/POST /adjustments` · `GET /adjustments/{id}` · `POST /opening-stock` · `GET/POST /stock-counts` · `GET /stock-counts/{id}` · `PUT /stock-counts/{id}/lines` · `POST /stock-counts/{id}/post|cancel` · `GET/POST /transfers` · `GET /transfers/{id}` · `POST /transfers/{id}/receive` · `GET /alerts` · `GET /alerts/summary` · `POST /alerts/refresh` · `POST /alerts/{id}/seen` |
| Purchasing | `GET/POST /suppliers` · `GET/PATCH /suppliers/{id}` · `POST /suppliers/{id}/activate|deactivate` · `GET /suppliers/{id}/ledger|statement|statement.pdf` · `GET/POST /purchase-orders` · `GET /purchase-orders/{id}` · `GET /purchase-orders/{id}/pdf` · `POST /purchase-orders/{id}/close|cancel` · `GET/POST /grns` (POST needs `Idempotency-Key`) · `GET /grns/{id}` · `GET/POST /purchase-returns` · `GET /purchase-returns/{id}` · `GET/POST /supplier-payments` (key) · `GET /supplier-payments/{id}` · `GET /reorder` · `POST /reorder/purchase-orders` |
| Customers | `GET/POST /customers` · `GET /customers/lookup?phone=` · `GET/PATCH /customers/{id}` · `POST /customers/{id}/activate|deactivate` · `GET /customers/{id}/ledger|statement|statement.pdf|open-invoices` · `POST /customers/{id}/statement/send` · `GET/POST /customer-payments` (key) · `GET /customer-payments/{id}` |
| Till and POS | `POST /till-sessions` · `GET /till-sessions` · `GET /till-sessions/current` · `GET /till-sessions/{id}` · `GET/POST /till-sessions/{id}/cash-movements` · `POST /till-sessions/{id}/close` · `GET /till-sessions/{id}/z-report` · `GET /pos/catalog?branchId=` (ETag / `If-None-Match` → 304) |
| Sales | `POST /sales` (key; approvals via `X-Approval-User` / `X-Approval-Pin`) · `GET /sales` · `GET /sales/{id}` · `GET /sales/{id}/receipt` · `GET /sales/{id}/pdf` · `POST /sales/{id}/send` (email / SMS) · `POST /sales/{id}/cancel` · `POST /sales/{id}/returns` · `GET /sale-returns` · `GET /sale-returns/{id}` · `POST /exchanges` (key) · `GET /credit-notes?number=&customerId=` · `GET/POST /held-sales` · `GET/DELETE /held-sales/{id}` · `GET/POST /quotations` · `GET /quotations/{id}` · `POST /quotations/{id}/convert|cancel` |
| Shop-type modules | `GET/POST /buybacks` · `GET /buybacks/{id}` · `GET/POST /warranty-claims` · `GET/PATCH /warranty-claims/{id}` · `GET/POST /products/{id}/variants` · `GET/POST /size-sets` · `DELETE /size-sets/{id}` · `GET/PUT /products/{id}/bundle` (each guarded by its module: `module_disabled` when off) |
| Cash | `GET/POST /expense-categories` · `PATCH /expense-categories/{id}` · `GET/POST /expenses` · `GET /expenses/{id}` · `POST /expenses/{id}/void` · `GET /cash/daily?date=&branchId=` · `GET /cash/profit-loss?from=&to=&branchId=` |
| Reports | `GET /dashboard` · `GET /search?q=` · `GET /reports` (the reports this user may run) · `GET /reports/{key}?from=&to=&branchId=&…` · `GET /reports/{key}/download?format=XLSX|PDF` · `POST /reports/{key}/export` (202 + job) · `POST /backup` (owner's full Excel backup, 202 + job) · `GET /exports` · `GET /exports/{id}` · `GET /exports/{id}/file` |
| Notifications and shop admin | `GET /notifications` · `GET /notifications/summary` · `POST /notifications/{id}/read` · `POST /notifications/read-all` · `GET /onboarding` · `POST /onboarding/printer-tested` · `GET /terms` · `POST /terms/accept` · `GET /subscription/invoices` |
| Platform billing | `GET /platform/subscription-invoices` · `GET /platform/subscription-invoices/{id}` · `POST /platform/subscription-invoices/{id}/payments` · `POST /platform/shops/{id}/invoices` · `POST /platform/shops/{id}/plan-change` · `POST /platform/shops/{id}/status` · `GET /platform/shops/{id}/onboarding` · `POST /platform/jobs/billing` · `POST /platform/demo-shops/{type}/reset` |

New error codes: `insufficient_stock` (with `available`), `serial_unavailable`, `duplicate_serial`, `batch_expired`, `already_returned`, `refund_too_large`, `payment_short` (with `due`), `overpaid`, `credit_off`, `approval_required` (with `limit`), `pin_wrong`, `pin_locked`, `till_closed`, `till_open`, `not_your_till`, `not_same_day`, `sale_has_returns`, `po_closed`, `count_open`, `nothing_counted`, `serial_count_difference`, `transfer_closed`, `credit_note_invalid|expired|balance`, `exchange_credit_left`, `credit_note_only`, `not_enough_cash`, `use_convert`, `quotation_closed|expired`, `too_many_rows`, `export_not_ready`, `request_in_progress`, `rate_limited` (600 requests a minute per user; 5 exports an hour per shop).

## 5. Security headers and limits

- Session cookie: `HttpOnly; Secure; SameSite=Lax; Domain=` the shop host. CSRF token required on every state-changing request (Spring Security CSRF with a cookie-to-header token).
- CORS: only `https://*.selectcodepos.com`, verified custom domains and the Vercel preview domain in staging.
- Rate limits: login 10/min per IP and 5 failures per account; API 600/min per user; exports 5/hour per shop.
- Request size: 1 MB JSON, 10 MB uploads (imports, images, CNIC photos).
