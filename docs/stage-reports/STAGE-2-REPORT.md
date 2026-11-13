# Stage 2 report — Frontend A: shell, portal and setup screens

Date: 2026-11-08
Status: **complete — waiting for approval**

## Done

| Area | What was built |
|---|---|
| Project | `frontend/`: Next.js 16.4 (App Router, Turbopack), React 19.3, TypeScript strict, Tailwind 4 with the Mobile Shop graphite/aqua tokens, Manrope + JetBrains Mono + Noto Nastaliq Urdu, Base UI primitives, TanStack Query, sonner toasts, lucide icons. 29 pages, about 7,600 lines |
| API connection | Types generated from `backend/openapi.json` (`npm run gen:api`); one `api()` client with CSRF token handling and automatic retry, Problem Details errors (`ApiError` with field errors and extra values such as `limit`); same-origin proxy `/api/v1/*` → Spring Boot, so cookies stay per shop subdomain and no CORS is needed |
| Routing | `proxy.ts` picks the area from the host: `admin.<root>` → platform portal, `<code>.<root>` → that shop, bare domain → landing page; `/support/*` on the admin host opens a shop in support mode. Works locally as `demo-mart.localhost:3000` / `admin.localhost:3000` |
| App shell | Sidebar (desktop), top bar and bottom tab bar (phone), Ctrl K search over pages and products (name, SKU, barcode, formula), trial / payment / read-only / support banners, loading skeletons, empty states, menus and buttons hidden by permission (`useCan`) and by module (`useFeature`), forced password change screen, sign-out |
| Sign-in and accounts | Shop sign-in (with authenticator code when turned on, lockout messages), setup link, forgot / reset password, my account (password, authenticator on/off, approval PIN), platform sign-in with TOTP and first-time enrolment by QR code |
| Platform portal | Overview (counts, revenue, overdue, due this week, by type/status/city), shop list with search and filters, **5-step shop creation wizard** (details with live code check → type and features → plan and price → starting data → owner) that shows the one-time owner link, shop profile (details, features, plan, owner access, support session, suspend / close), shop types, plans, platform users, settings, audit log |
| Shop setup | Owner home with plan usage and a getting-started checklist; settings in 9 tabs (business, receipts, tax, numbering, payment methods, stock rules, limits, features, change history); branches, godowns and tills; users (temporary password or emailed link, branch limits, reset, deactivate); roles with permission matrix; activity and login history |
| Catalogue | Product list (search, category / brand / status filters, paging, multi-select, Excel export, camera scan), product form (Urdu name, barcodes by USB scanner, camera or typing, pack sizes, prices, picture, stock alerts, and medicine / part-number / book sections that appear only when that feature is on), product history, categories / brands / units, bulk price change with preview, Excel import (upload → row-by-row problems → commit), barcode labels in 3 sizes with copies and printing |
| PWA | Manifest, icons, theme colour |
| CI | `.github/workflows/frontend.yml`: lint, typecheck, unit tests, build, API-types drift check, then the Playwright journeys against a real API with demo data |

Screenshots of the main screens are in [stage-2-screens/](stage-2-screens) (desktop 1440 px and phone).

## Tests

| Check | Result |
|---|---|
| `npm run lint` (ESLint, Next and React 19 rules) | clean |
| `npm run typecheck` (TypeScript strict) | clean |
| `npm test` (Vitest) | **15 passed** — money / quantity formatting, host routing, menu visibility by permission, API error parsing, form accessibility, import results, **API proxy** (401 passes through, query strings, multiple cookies, API down → 503) |
| `npm run build` | passes, 31 routes |
| `npm run e2e` (Playwright, real API on this PC) | **19 passed**, run twice in a row with no flaky tests |
| Backend `./mvnw verify` (re-run after the small banner text change) | **68 passed** |

The Playwright journeys:

- **Shop lifecycle (9 steps, one new shop each run):**
  - The super admin signs in with TOTP.
  - They create a medical store with the wizard; the same shop code is then refused.
  - The owner opens the one-time link. A weak password is refused, and the link can't be used a second time.
  - The owner signs in, sees the checklist, and adds a product with a barcode and formula.
  - The product can be found by formula, and a duplicate barcode is refused.
  - The labels page draws an EAN-13 barcode.
  - The owner adds a cashier. The cashier signs in with the temporary password and must choose a new one. They see no Users menu and can't open the users page directly.
  - The owner changes a setting, and it is still there after a reload.
  - Support opens the shop read-only, with the owner-code box shown.
- **Demo shop:**
  - landing page; unknown shop code; wrong password message; signed-out visitor sent to sign-in
  - all 13 shop pages open with no browser errors
  - Ctrl K search; sign-out
  - **a session from one shop does not open another shop**
- **Phone (Pixel 7):** the main pages have no sideways scrolling, and the menu works.

## Bugs found and fixed while testing

1. **Every 401 from the API looked like "server cannot be reached".** The proxy streamed request bodies to the API. Under the Fetch standard, Node's `fetch` turns a 401 reply to a streamed request into a network error. So a wrong password or a "code required" step showed the wrong message, and platform sign-in could not finish. Fixed by buffering the body. A Vitest test now covers it, and the proxy logs any failure on the server.
2. The wizard's address hint had `selectcodepos.com` hard-coded. It now uses the real root domain (so it shows `.localhost` here and the staging domain on staging).
3. "14 day(s) left", "0 shop(s)" and "1 branch(es)" now use proper plurals. Plan limits show thousands separators (25,000).

## Changes to the plan

- **Lock screen moved to Stage 4 (till).** A quick-unlock screen needs a PIN-unlock API that the till will add. Until then, an expired session already sends the user back to sign-in.
- shadcn → **Base UI** primitives directly. This is the same headless layer that current shadcn builds on, with fewer files to maintain.

## Notes

- Platform sign-in allows 10 attempts per 5 minutes per address, and each sign-in uses 2 (password, then code). The e2e tests save and reuse the platform session so repeated runs don't hit the limit.
- Each lifecycle run creates one new trial shop (`e2e-…`) in the local embedded database. That database is thrown away when the local API stops.
- **How to run it:** see README "Run the web app". Start the API (`LocalDevApplication`), then `npm run dev`, then open http://demo-pharmacy.localhost:3000 (user `owner`, password `Demo-Shop-2026`) or http://admin.localhost:3000.

## Needs from you

1. Approval of the look (screenshots in `stage-2-screens/`).
2. Approval to start **Stage 3 — Backend B: business engine**.
3. Whether to make the first git commit now (Stages 1 and 2 are not committed yet).
