# Coding Standards

Rules for everyone writing code for SelectCode POS. Where this file is silent, follow the School Management System's existing code.

## 1. Git workflow

| Rule | Detail |
|---|---|
| Branches | `main` is protected and always deployable to staging. Work on `feat/<req-id>-short-name`, `fix/<bug-id>-short-name`, `chore/...` |
| Commits | Imperative, short subject, requirement or bug ID: `POS-03: ask for manager PIN above discount limit` |
| Pull requests | Small (under ~400 changed lines where possible). Template: what and why, requirement IDs, screenshots for UI, migration notes, test evidence |
| Reviews | One approving review; two for `shared/tenant`, `shared/security`, `stock`, `sales`, `billing` and migrations |
| Merging | Squash merge after CI is green. No force-push to `main` |
| Releases | Tag `vX.Y.Z` on `main`; production deploy from tags (DEPLOYMENT-RUNBOOK.md §6) |

## 2. Backend (Java 21, Spring Boot)

- **Package by module**, then `api` / `app` / `domain` (ARCHITECTURE.md §2). Another module's `domain` is never imported.
- **Controllers** are thin: validate input, check permission/module, call one `app` service method, map to a response record. No repositories in controllers.
- **Services** own transactions: one public `@Transactional` method per business action (`postSale`, `receiveGrn`). Read-only queries use `@Transactional(readOnly = true)`.
- **Records** for requests and responses (`record CreateSaleRequest(...)`), named `<Verb><Thing>Request` / `<Thing>View`.
- **Entities** extend `ShopScopedEntity` (tenant tables) or `PlatformEntity`. No business logic in setters; use intention-named methods (`sale.cancel(reason, by)`).
- **Errors:** throw `BusinessException(code, detail, args)` subclasses (`StockException`, `ApprovalRequiredException`). They map to Problem Details with a stable `code` (API-CONVENTIONS.md §3).
- **Stock:** only `stock.app.StockService` writes `product_stock` and `stock_movement` (ArchUnit-enforced).
- **Events:** cross-module side effects (alerts, notifications, accounting posting) are published Spring Modulith events handled after commit.
- **Time:** use the injected `Clock`; never `LocalDate.now()` directly. Business dates use the shop's time zone.
- **Logging:** SLF4J with shop id and user id in MDC; no personal data, tokens or passwords.
- **Formatting:** Spotless with Palantir Java Format; tabs as in the school system. Build fails on violations.

## 3. Money and quantities (both sides)

| Rule | Java | TypeScript |
|---|---|---|
| Type | `Money` (wraps `BigDecimal`, scale 2) and `Qty` (scale 3) | `string` from the API; `decimal.js`-style helpers in `lib/money.ts` for cart maths |
| Rounding | `HALF_UP` at line level; bill discount spread with remainder on the last line | Same function, same tests (shared test vectors in `docs/test-vectors/money.json`) |
| Display | Server never formats | `formatMoney("1250.5") → "Rs 1,250.50"`, tabular numbers, JetBrains Mono for figures |
| Never | `double`, `float`, JS `number` arithmetic on money | `parseFloat` on money |

## 4. Database

- One migration per pull request where possible: `V{next}__{module}_{what}.sql`, add-only (no drop/rename of columns in use). Renames are done by add → backfill → switch → drop in a later release.
- Every new shop table: `shop_id` first column, RLS enable + policy, indexes starting with `shop_id`, `created_at`/`created_by`.
- Constraints in the database as well as in code (`check`, `unique`, foreign keys).
- Large backfills run in batches from a Java migration, not one `UPDATE`.

## 5. Frontend (Next.js 16, TypeScript)

- Read `frontend/AGENTS.md` and the Next.js docs in `node_modules/next/dist/docs/` before using new framework APIs (Next 16 differs from older versions).
- **Folders:** routes in `src/app/(shop|platform|auth|print)`, feature code in `src/features/<module>`, shared UI in `src/components`.
- **Data:** TanStack Query hooks per feature (`useProducts`, `usePostSale`) on the generated API client. No `fetch` in components.
- **Types:** `strict: true`; no `any`; API types only from `schema.d.ts`.
- **Forms:** zod schema + controlled inputs; show the server's `detail` for business errors and field `errors` next to fields.
- **Features and permissions:** `useFeature('batches')` and `usePermission('cost.view')` decide what renders. Never assume.
- **Speed:** every route has `loading.tsx`; lists paginate; the POS never waits for the network to show search results.
- **Accessibility:** labels for every input, keyboard paths for POS (F2 search, F4 customer, F8 hold, F9 pay, Esc clear), focus states visible.
- **Lint and format:** ESLint (`eslint-config-next`) and Prettier must pass; `npm run typecheck` must pass.

## 6. UI design (same as the Mobile Shop System)

Dark graphite design copied from the current build's `app/globals.css`:

| Token | Value | Use |
|---|---|---|
| `--color-page` | `#141415` | Page background |
| `--color-deep` | `#0f0f10` | Sidebar, deep areas |
| `--color-card` | `#19191b` | Cards (`.glass`, 16 px radius, hairline edge) |
| `--color-raised` | `#232325` | Hover, raised tiles |
| `--color-ink` / `muted` / `faint` | `#ededed` / `#a1a1a6` / `#85858b` | Text roles |
| `--color-line` | `rgb(237 237 237 / 0.08)` | Hairlines |
| `--color-aqua` / `aqua-strong` / `on-aqua` | `#4fbfae` / `#3aa898` / `#062420` | The one accent: primary action, selection, today's figure |
| `--color-good` / `warn` / `bad` / `info` | `#4cc38a` / `#d9a84e` / `#ee6b6f` / `#7fa8d8` | Meaning only (stock OK, low, out, info) |
| Fonts | Manrope (UI), JetBrains Mono (numbers, codes, SKUs, IMEIs), Noto Nastaliq Urdu | |

- Depth comes from surface steps, not shadows. One accent colour per screen.
- Numbers use `tabular-nums`; money right-aligned in tables.
- Mobile: bottom bar (Home, Sales, POS, Products, More) like the current build; tables scroll inside their card.
- The platform portal uses the same tokens with a small "Platform" badge in the header, so staff always know where they are.

## 7. Tests

- Backend: `*Test` for unit tests and `*IT` for integration tests (embedded Postgres). Test names say the rule: `returnsMoreThanSoldIsRefused`.
- Frontend: Vitest next to the code (`cart.test.ts`); Playwright specs in `frontend/tests/<journey>.spec.ts` using the seeded staging data.
- Every bug fix comes with a test that fails before the fix.
- Shared test vectors (money, discount spreading, average cost, return refunds) live in `docs/test-vectors/` and are used by both Java and TS tests.

## 8. Documentation

- A pull request that changes a table, endpoint, module key or permission updates DATA-MODEL.md, API-CONVENTIONS.md, SHOP-TYPES-AND-MODULES.md or the permissions list in the same pull request.
- Requirement changes are made in the requirements doc first, then in REQUIREMENTS-REGISTER.md (new IDs, never reused).
