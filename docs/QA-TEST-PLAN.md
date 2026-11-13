# QA Test Plan

How we prove SelectCode POS does what the requirements say, keeps shops apart, keeps stock and money exact, and is fast enough at the counter. Test case IDs (`TC-...`) map to requirement IDs in REQUIREMENTS-REGISTER.md.

## 1. Scope

| In scope | Out of scope |
|---|---|
| Every Phase 1 requirement before the Phase 1 release; Phase 2/3 requirements before their releases | Third-party systems themselves (FBR, SMS operators, banks); we test our side of each integration |
| Platform portal, all shop modules, all 10 Phase 1 shop-type templates | Hardware not on the supported list |
| Security, data separation, performance, printing, devices | Penetration test by an outside firm (planned before the first Enterprise client) |

## 2. Test levels

| Level | Tool | Who | Runs | Target |
|---|---|---|---|---|
| Unit (backend) | JUnit 5 | Developers | Every push | Every rule in `stock`, `sales`, `purchasing`, `customers`, `billing`, `SaleMath`, `Money`; ≥ 90% line coverage in those packages |
| Integration (backend) | `*IT` with embedded Postgres, MockMvc | Developers | Every pull request | Every endpoint: happy path, validation, permission, module switch, plan limit, read-only shop |
| Architecture | ArchUnit, Spring Modulith `verify()` | CI | Every pull request | Module boundaries; only `stock` writes stock tables; controllers don't use repositories |
| Tenant isolation | `TenantIsolationIT` | CI | Every pull request | Every endpoint as Shop A against Shop B ids returns 404 |
| Unit/component (frontend) | Vitest + Testing Library | Developers | Every pull request | Cart maths, money formatting, forms, feature/permission gating, scanner input handling |
| End-to-end | Playwright (Chromium + mobile viewport) | QA | Every merge to `main` (staging), nightly full run | The critical journeys in §6 |
| Performance | k6 (or Gatling) against staging | QA + backend | Before each release | NFR-10, NFR-11 |
| Manual exploratory | Checklists in §7 | QA | Each milestone | Printing, devices, scanners, Urdu, look and feel |
| UAT | Pilot shop owners and staff | Project lead | Before Phase 1 release | Real day of trading per shop type |

## 3. Environments and data

| Environment | Data | Use |
|---|---|---|
| Local | Seed script: 2 shops (A, B), every role, 200 products | Developers |
| CI | Fresh embedded Postgres per run, fixtures per test | Automated tests |
| Staging | Demo shops for every type (OPS-01), reset nightly; plus "QA Shop A/B" not reset | QA, UAT, demos |
| Production | Real shops. **Never test with fake sales on a real shop.** Use the production demo shop for smoke tests | Smoke test after release |

**Seed data per demo shop type** (in `backend/src/test/resources/seed/` and the staging seeder):

| Type | Must contain |
|---|---|
| Mobile shop | 20 phone models with 3–5 IMEI units each (new, used, box-open, PTA and non-PTA), 50 accessories, 2 used-phone buybacks, 1 open warranty claim |
| Super store | 2,000 barcoded items, 30 weighed items, 20 carton/piece items, 3 tills, 1 active offer |
| Medical store | 1,500 medicines with formula, 3 batches each (one expired, one expiring in 45 days), strip/box units, 5 controlled items |
| Garments | 40 designs with size × colour variants |
| Hardware | Items in box/piece and coil/metre, 3 contractor customers with balances |
| Others | 100 items with their type fields |

Every shop has 30 days of sales history, 5 suppliers with open POs, 10 credit customers and users for every role (passwords in the private QA vault, never in the repo).

## 4. Entry and exit criteria

**Entry (a build may be tested on staging):** CI green; migrations applied; seed loaded; release notes list the requirement IDs.

**Exit (a build may go to production):**
- 100% of P1 test cases for the release pass
- No open Blocker or Critical bugs; Major bugs have a written decision from the project lead
- All acceptance checks in §5 pass
- Performance targets met
- Restore test done within the last 30 days

## 5. Acceptance checks (from PDF section 15)

| ID | Check | Requirement | Automated |
|---|---|---|---|
| TC-SEC-01 | Shop A user gets 404 for every Shop B product, sale, customer, file, through every endpoint | NFR-01 | Yes |
| TC-SEC-02 | Switched-off module's endpoints return 403 `module_disabled` when called directly | NFR-01, PLT-11 | Yes |
| TC-STK-01 | After 1,000 random documents, `product_stock` equals the sum of movements for every product, branch and batch | NFR-05 | Yes |
| TC-STK-02 | Two tills sell the last unit at the same moment: one succeeds, the other gets `insufficient_stock` | NFR-04 | Yes |
| TC-STK-03 | Two tills sell the same IMEI at once: one succeeds, the other gets `serial_unavailable` | MOB-03, NFR-04 | Yes |
| TC-MNY-01 | Customer and supplier balances equal invoices − returns − payments to the paisa after the random run | NFR-02, D5 | Yes |
| TC-PUR-01 | Receive 10 @ 1,850 then 5 @ 1,910 → average cost 1,870.00; profit on an earlier sale unchanged | PUR-04, NFR-06 | Yes |
| TC-MED-01 | Medical store POS picks the earliest valid expiry and refuses an expired batch | MED-03, MED-04 | Yes |
| TC-SEC-04 | Cashier never receives cost/profit in any response, print or export (JSON scan of every endpoint) | ACC-05 | Yes |
| TC-BIL-01 | Overdue shop moves Grace → Read-only on schedule; read-only shop gets 402 on every write | BIL-07, ONB-05 | Yes |
| TC-PRF-01 | POS search < 100 ms; sale saved < 1 s at p95 with 50,000 products and 20 busy tills | NFR-10, NFR-11 | Load test |
| TC-PRN-01 | Receipts correct on 80 mm and 58 mm in English and Urdu; labels scan with a USB scanner | SET-05, CAT-07 | Manual |
| TC-DEV-01 | POS usable on Windows PC, Android tablet and phone | HW-06 | Manual |
| TC-BKP-01 | Shop restored from last night's dump matches production counts | NFR-13 | Monthly manual |
| TC-DAY-01 | Each demo shop type runs a full day: open till, purchase, sales, return, count, close, reports | All | Manual |

## 6. Test cases by area

Priority: **P1** must pass to release · **P2** should pass · **P3** nice to have. Each case lists steps and the expected result in short form; detailed steps live in the Playwright specs or the QA sheet.

### Platform (PLT, BIL, ONB)

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-PLT-01 | Platform login with password only | Asked for TOTP; no access without it | PLT-03 | P1 |
| TC-PLT-02 | Shop user tries `admin.` login | Refused | PLT-01 | P1 |
| TC-PLT-03 | Create shop through wizard with type Medical | Shop exists; medical modules on; categories/units seeded; owner receives setup link | PLT-08, ONB-07 | P1 |
| TC-PLT-04 | Create shop with a taken code | Error; suggestion offered | PLT-09 | P1 |
| TC-PLT-05 | Switch `serials` on for a super store | IMEI fields appear; API accepts serials; audit row written | PLT-11 | P1 |
| TC-PLT-06 | Switch a module off that has data | Screens hidden, API 403, data intact; switch on shows data again | PLT-11 | P1 |
| TC-PLT-07 | Suspend shop with reason | Shop users signed out; login shows "contact support"; reactivate restores | PLT-14 | P1 |
| TC-PLT-08 | Delete shop with wrong code | Refused; correct code + reason schedules deletion in 90 days | PLT-15 | P1 |
| TC-PLT-09 | Support session | Read-only; banner visible to shop; write needs owner code; actions audited | PLT-17 | P1 |
| TC-PLT-10 | Overview numbers | Match database counts for seeded shops | PLT-04 | P2 |
| TC-BIL-02 | 3rd user on Starter | 402 `plan_limit` with upgrade message | BIL-08 | P1 |
| TC-BIL-03 | Downgrade with 5 users to Starter | Applies next cycle; existing users kept; no new users | BIL-09 | P2 |
| TC-BIL-04 | Record subscription payment | Invoice paid; next due moves; receipt sent | BIL-05 | P1 |
| TC-BIL-05 | Reminder job | Messages on 7/3/1 days before due, once each | BIL-06 | P2 |
| TC-ONB-01 | Setup link used twice / after 48 h | Second use and expired link refused | ONB-01 | P1 |
| TC-ONB-02 | Opening stock | Posted as `opening`; not in purchase reports | ONB-03 | P1 |

### Access (ACC)

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-ACC-01 | Each system role opens each menu | Only permitted menus; direct URL gives 403 | ACC-01 | P1 |
| TC-ACC-02 | Branch 2 cashier lists sales | Only Branch 2 | ACC-03 | P1 |
| TC-ACC-03 | Discount over limit | Asks manager PIN; wrong PIN refused; approval recorded on sale | ACC-04 | P1 |
| TC-ACC-04 | 5 wrong passwords | Locked 15 min; login history shows attempts | ACC-06 | P1 |
| TC-ACC-05 | Edit product price | Audit shows old/new, user, time | ACC-07 | P1 |
| TC-ACC-06 | Idle POS | Locks with PIN; other screens sign out after the setting | ACC-09 | P2 |

### Catalogue (CAT)

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-CAT-01 | Duplicate SKU or barcode | `duplicate_sku` / `duplicate_barcode` | CAT-01, CAT-02 | P1 |
| TC-CAT-02 | Two barcodes, scan either at POS | Same product | CAT-02 | P1 |
| TC-CAT-03 | Bulk +5% on a brand with preview | Preview matches; apply changes only that brand; price history rows | CAT-05 | P1 |
| TC-CAT-04 | Import 3,000 rows with 12 bad rows (dry run) | 12 row errors listed; nothing saved; fix and commit imports 3,000 | CAT-06 | P1 |
| TC-CAT-05 | Labels EAN-13, UPC, Code128 | Print and scan correctly | CAT-07 | P1 |
| TC-CAT-06 | Deactivate a sold product | Hidden from POS; history kept; delete not offered | CAT-09 | P1 |

### POS and sales (POS, SAL)

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-POS-01 | 40-item basket by scanner | All lines correct; total correct; under 2 min | POS-01 | P1 |
| TC-POS-02 | Split payment cash + card, cash tendered more | Change correct; payments stored per method | POS-02 | P1 |
| TC-POS-03 | Partial payment on account | Due on sale and customer balance | POS-02 | P1 |
| TC-POS-04 | Bill discount spread | Line nets sum exactly to total (rounding on last line) | POS-03 | P1 |
| TC-POS-05 | Hold, serve another, resume | Held cart intact; resumed sale posts; hold removed | POS-04 | P1 |
| TC-POS-06 | Same idempotency key sent twice (network retry) | One sale | POS-09 | P1 |
| TC-POS-07 | Network off for 30 s during sale | Sale queued and posted on reconnect; cashier sees status | POS-09 | P1 |
| TC-POS-08 | Close till with counted cash short by 200 | Difference −200 recorded; Z-report shows it | POS-07 | P1 |
| TC-POS-09 | Sell more than stock (negative not allowed) | `insufficient_stock` with available qty | NFR-04 | P1 |
| TC-SAL-01 | Partial return of a discounted line | Refund uses net unit price; stock back if restock | SAL-02 | P1 |
| TC-SAL-02 | Return on a sale that still has money owing | Return value clears the amount owed first; refund allowed only up to value − due; a smaller refund leaves the rest as a return charge (current build: value 2,150, due 1,900 → max refund 250) | SAL-02 | P1 |
| TC-SAL-03 | Return damaged | No restock; loss in profit report | SAL-02 | P1 |
| TC-SAL-04 | Return more than sold | `already_returned` | D6 | P1 |
| TC-SAL-05 | Exchange M for L, customer pays difference | One document pair; stock both ways; payment | SAL-03 | P1 |
| TC-SAL-06 | Cancel today's sale as manager; try yesterday's | Today reversed with reason; yesterday refused | SAL-05 | P1 |
| TC-SAL-07 | Quotation to invoice | Prices kept; stock checked at conversion | SAL-06 | P2 |

### Customers and purchasing (CUS, PUR)

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-CUS-01 | Payment across 3 open invoices | Oldest cleared first; receipt lists allocations | CUS-03 | P1 |
| TC-CUS-02 | Sale over credit limit | Needs manager PIN | CUS-04 | P1 |
| TC-CUS-03 | Statement PDF | Opening, lines, closing match ledger | CUS-02 | P1 |
| TC-PUR-02 | PO 50, receive 48 + 2 bonus, close short | Stock +50; billed for 48; PO closed | PUR-02, PUR-03 | P1 |
| TC-PUR-03 | Purchase return of expired batch | Stock and supplier balance reduced | PUR-05 | P1 |
| TC-PUR-04 | Reorder to PO | Suggested qty per formula; one PO per supplier | PUR-07 | P1 |

### Stock (STK)

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-STK-04 | Adjustment out (damaged) over value limit | Needs approval; write-off value in report | STK-02 | P1 |
| TC-STK-05 | Stock count while sales continue | Snapshot at start; sales during count accounted; differences posted after approval | STK-03 | P1 |
| TC-STK-06 | Transfer dispatched, not received | In transit; not sellable at either branch; receive adds to destination | STK-04 | P1 |
| TC-STK-07 | Product history | Every movement with balance after, matching stock | STK-05 | P1 |

### Shop-type modules

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-MOB-01 | Purchase phones by scanning IMEIs | Units created; duplicate IMEI refused | MOB-01, MOB-03 | P1 |
| TC-MOB-02 | IMEI search | Shows supplier, purchase, sale, customer, warranty | MOB-02 | P1 |
| TC-MOB-03 | Used phone buyback without CNIC | Refused; with CNIC + photos posts and prints slip | MOB-04 | P1 |
| TC-MOB-04 | Return a sold IMEI | Unit back in stock as `returned`/used per choice | MOB-01, SAL-02 | P1 |
| TC-SUP-01 | Sell 1.250 kg at Rs. 180/kg | Line 225.00; stock −1.250 | SUP-02 | P1 |
| TC-SUP-02 | Receive 2 cartons × 24, sell 5 pieces | Stock 43 pieces | SUP-04 | P1 |
| TC-SUP-03 | Three tills, shift close each | Each till's cash separate | SUP-07 | P1 |
| TC-MED-02 | Receive with batch and expiry | Stock per batch | MED-02 | P1 |
| TC-MED-03 | Sell 1 strip from a box of 10 strips | Stock in base unit correct; price per strip | MED-06 | P1 |
| TC-MED-04 | Search by formula | Alternatives in stock listed | MED-07 | P1 |
| TC-MED-05 | Near-expiry report 60 days | Lists the seeded 45-day batch with value and distributor | MED-05 | P1 |
| TC-GAR-01 | Create design with 4 sizes × 3 colours | 12 variants with own barcodes; grid stock entry | GAR-01 | P1 |
| TC-HRD-01 | Buy coil (90 m), sell 12.5 m | Stock 77.5 m | HRD-01 | P1 |
| TC-OTH-01 | Sell a class book set | Each book's stock reduced | OTH-02 | P1 |

### Cash, reports, notifications

| ID | Case | Expected | Req | P |
|---|---|---|---|---|
| TC-CSH-01 | Daily cash summary | Opening + cash sales + receipts − payments − expenses = closing | CSH-03 | P1 |
| TC-RPT-01 | Each Phase 1 report on seeded data | Totals match an independent SQL query in the test | RPT-02 | P1 |
| TC-RPT-02 | Export 50,000-row report | Runs in background; file emailed; app stays responsive | OPS-13 | P2 |
| TC-NTF-01 | SMS receipt through gateway | Message in outbox → sent; failure retried 3 times then marked failed | NTF-01 | P2 |

## 7. Manual checklists (each milestone)

- **Printing:** 80 mm and 58 mm receipts on two printer brands; A4 invoice PDF; Urdu shaping correct; logo prints; labels at 3 sizes scan.
- **Scanners:** USB scanner in POS, product form, GRN, count; camera scanning on Android Chrome and iPhone Safari over HTTPS.
- **Devices:** Windows 10/11 Chrome and Edge; Android tablet; 360 px phone; touch targets ≥ 40 px.
- **Look and feel:** matches Mobile Shop design tokens; loading skeletons appear immediately; no layout shift on POS.
- **Accessibility:** keyboard-only POS sale; focus visible; labels on inputs; contrast of text on cards.
- **Urdu:** Urdu product names display right-to-left in lists, POS and receipts.

## 8. Release gates per milestone

| Milestone | Gate |
|---|---|
| M0 | TenantIsolationIT and ArchitectureTest run in CI; staging deploys from `main` |
| M1 | TC-PLT-01–09, TC-ONB-01 |
| M2 | TC-ACC-*, TC-CAT-*, TC-SEC-04 |
| M3 | TC-STK-*, TC-PUR-*, TC-MNY-01 |
| M4 | TC-POS-*, TC-SAL-*, TC-CUS-* |
| M5 | TC-CSH-*, TC-RPT-* |
| M6 | TC-MOB-*, TC-SUP-*, TC-MED-*, TC-GAR-*, TC-HRD-*, TC-OTH-* |
| M7 | TC-BIL-*, TC-BKP-01 |
| M8 | All §5 acceptance checks, TC-DAY-01 per type, UAT sign-off by pilot shops |

## 9. Bugs

| Severity | Meaning | Example | Fix by |
|---|---|---|---|
| Blocker | Data leak between shops, data loss, wrong money/stock, cannot sell | Shop A sees Shop B's sale | Immediately; release stopped |
| Critical | Main function broken, no workaround | GRN cannot be saved | Before release |
| Major | Function broken with workaround, or wrong report | Report filter ignores branch | Before release or decision |
| Minor | Small functional or display issue | Wrong label text | Next release |
| Trivial | Cosmetic | Spacing | When convenient |

Every bug records: build, environment, shop type, role, steps, expected, actual, screenshot or video, requirement ID. A Blocker or Critical fix gets a regression test before it is closed.

## 10. Regression and reporting

- The full automated suite runs nightly on staging; failures are posted to the team channel in the morning.
- Before each release QA publishes a short report: build, cases run/passed/failed per area, open bugs by severity, performance numbers, go/no-go.
- After each production release: smoke test on the production demo shop (login, sell, return, report) within 15 minutes.
