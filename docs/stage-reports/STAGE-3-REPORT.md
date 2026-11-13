# Stage 3 report — Backend B: business engine

Date: 2026-11-08
Status: **complete — waiting for approval**

## Done

| Area | What was built | Requirement IDs |
|---|---|---|
| Stock engine | The only code that changes stock: row locks in a fixed order (no deadlocks), signed movements with balance after, refuses to go below zero unless the shop allows it, weighted average cost on receipts across branches, batches with earliest-expiry-first picking, one row per IMEI/serial unit locked before it moves. Nightly check that stock equals the sum of its history | STK, NFR-03/04/05, D1–D3 |
| Stock control | Stock list per branch with cost and retail value, stock history, opening stock (posted as opening, not a purchase), adjustments with reasons and write-off value, stock counts while selling continues (snapshot plus movements until each line was counted), transfers with dispatch → in transit → receive (short receipts kept), alerts (low, out, near expiry, expired, dead stock) with a daily bell message | STK-01..06, ONB-03 |
| Purchasing | Suppliers with terms and opening balance, ledger and statement PDF; purchase orders (ordered → partly received → received / closed short / cancelled) with PDF; goods received with or without a PO, supplier bill number, bonus items (10+1 lowers the unit cost), cartons and other pack units, batches with expiry, IMEIs scanned per unit; purchase returns (expired batches allowed); supplier payments clearing the oldest bills first; reorder list (larger of 30 days' sales or twice the reorder level, less stock and orders) → one PO per supplier | PUR-01..07, MED-02, MED-10, MOB-01, SUP-04, HRD-01 |
| Selling | Till sessions with float, cash in/out with reason, close with count, short/over and Z-report, each till separate; compact POS catalogue with ETag; sales with line and bill discounts (spread with the remainder on the last line), sales tax inclusive or exclusive, split payment with change in cash only, credit notes as payment, on-account sales for customers with credit limits, cost captured on every line, `Idempotency-Key` so a retry never doubles a sale; held bills; quotations converted at the quoted prices | POS-01..05, 07..09, SAL-06, HRD-02 |
| Returns | Returns against the invoice: value per unit from the line total, clears what is owed first, then refund in cash / to account / as a credit note, the rest kept as a return charge; restock or damaged (damaged cost shows as a loss); exchange in one step; same-day cancellation by a manager (stock and money reversed, invoice kept); garment shops can be set to credit-note-only returns | SAL-02, 03, 05, GAR-04 |
| Customers | Customer record with type and credit limit, phone lookup and quick-add at the till, ledger with running balance, statement (screen, PDF, email), payments clearing the oldest invoices first, CNIC and phone masked without permission | CUS-01..04, POS-05, NFR-09 |
| Approvals | Manager PIN for discounts over the limit (or without discount permission), price changes, below-cost and below-minimum prices, credit over the limit, late or large returns, write-offs and count losses over the limit; the approver is recorded on the document | ACC-04 |
| Cash | Expenses with category, paid from the till or elsewhere, attachment, void with reason; daily cash summary per branch (opening + sales + receipts − refunds − payments − expenses = closing, with counted and difference); profit and loss by month and branch | CSH-01, 03, 04 |
| Reports | Role-based dashboard (sales, profit, invoices, cash in drawers, dues, stock alerts, 14-day chart, top items); 65 Phase 1 reports from requirements §13 (sales, profit, products, stock, adjustments, returns, purchases, customers, suppliers, cash, tax, mobile, medical, garments), each filtered by period and branch with the filters in the heading; Excel and PDF download, background exports, owner's full Excel backup; cost reports and columns hidden without cost permission; Ctrl K search | RPT-01, 02, 04, BRN-03, OPS-13, OPS-14, NFR-13 |
| Shop-type modules | IMEI tracking, IMEI search with full history, duplicate IMEI blocked; used phones bought with CNIC and photos required and a slip; shop warranty (days per product, warranty date on each sold IMEI, claims); batches, FEFO, expiry block, near-expiry and expired lists by distributor, formula search and sales by formula; box/strip/tablet and carton/piece units; weighed items; multiple tills; size × colour (or shade) variants grid and size sets; book sets that take stock from each book; part numbers in the POS catalogue | MOB-01..06, MED-01..07, 10, SUP-01, 02, 04, 07, GAR-01, 02, 05, ELE-01, HRD-01, 02, OTH-01..03 |
| Notifications | Outbox for email, SMS (Android gateway, SMS per plan per month) and in-app bell messages, sent after commit with 3 retries; receipts and statements by email; stock and subscription alerts in the bell | NTF-01, 04, 05 |
| Billing | Renewal invoices a week before the due date; reminders 7, 3 and 1 days before and when overdue, once each; daily status job Trial / Active → Grace → Read-only (read-only shops get 402 on every write); payments recorded by SelectCode make the shop Active and move the next due date; upgrades now with a pro-rata invoice, downgrades at the next cycle; super admin status override with reason | BIL-05..10, ONB-04, 05 |
| Operations | Owner checklist (profile, logo, staff, products, opening stock, printer, test sale) for the shop and the platform; terms acceptance stored with version and time; closed shops deleted after 90 days (billing and platform audit kept), activity history after 2 years; 600 API calls a minute per user, 5 exports an hour per shop; demo shop per type rebuilt every night with sample data | ONB-02, 06, OPS-01, 05, 13..16 |

The API grew from 98 to 245 operations. The Stage 3 list is in [API-CONVENTIONS.md §4b](../architecture/API-CONVENTIONS.md); data model notes are in [DATA-MODEL.md §13](../architecture/DATA-MODEL.md).

## Tests

`./mvnw verify`: **136 tests, 0 failures** (about 3 minutes on this PC).

| Suite | Tests | What it proves |
|---|---|---|
| `SaleMathTest` | 8 | Every case in `docs/test-vectors/money.json` (average cost, discount spread, return refund, reorder, weighed item), tax both ways, returns in parts add up exactly |
| `StockPurchasingIT` | 11 | Average cost 1,850 → 1,870 (TC-PUR-01), PO 50 with 48 + 2 bonus closed short (TC-PUR-02), expired batch returned (TC-PUR-03), reorder → one PO per supplier (TC-PUR-04), opening stock is not a purchase (TC-ONB-02), write-off needs a manager's PIN (TC-STK-04), count during trading (TC-STK-05), transfer in transit (TC-STK-06), history with balances (TC-STK-07), cartons and coils (TC-SUP-02, TC-HRD-01), duplicate IMEI refused (TC-MOB-01), supplier payments oldest first |
| `SalesIT` | 21 | Split payment and change (TC-POS-02), on account and credit limit PIN (TC-POS-03, TC-CUS-02), bill discount spread (TC-POS-04), hold and resume (TC-POS-05), same key once (TC-POS-06), till short by 200 (TC-POS-08), not enough stock (TC-POS-09), discounted partial return and damaged (TC-SAL-01, 03), return clears what is owed (TC-SAL-02), more than sold (TC-SAL-04), exchange (TC-SAL-05), same-day cancel only (TC-SAL-06), quotation to invoice (TC-SAL-07), payments oldest first and statement (TC-CUS-01, 03), FEFO and expired batch refused, strip from a box (TC-MED-01, 03), 1.25 kg at 180 (TC-SUP-01), used phone bought, sold, returned by IMEI (TC-MOB-03, 04), book set (TC-OTH-01), discount PIN (TC-ACC-03), posted amounts cannot change (D7), earlier profit unchanged after cost moves |
| `ConcurrencyIT` | 3 | Two tills, last unit: one sale, one `insufficient_stock` (TC-STK-02); same IMEI: sold once (TC-STK-03); same key at the same moment: one sale |
| `RandomDocumentsIT` | 1 | 400 random documents of every kind, then stock equals history for every product, branch and batch (TC-STK-01), and every customer and supplier balance equals its ledger and its documents to the paisa (TC-MNY-01) |
| `BillingIT` | 6 | Grace then read-only on schedule with 402 on every write, payment makes it active (TC-BIL-01, 04); reminders 7/3/1 once each (TC-BIL-05); upgrade now, downgrade next cycle, plan limit after (TC-BIL-03); switched-off modules answer `module_disabled` (TC-SEC-02); onboarding and terms; closed shop purged, billing kept |
| `CashReportsIT` | 7 | Daily cash adds up and the till closes exactly (TC-CSH-01); profit and loss; every report runs and totals match the database (TC-RPT-01); cost reports and columns hidden (RPT-04); downloads, background export, owner backup (TC-RPT-02); dashboard and search; bell, email outbox and SMS retried 3 times then failed (TC-NTF-01) |
| `TradingDayIT` | 10 | Every shop type's demo shop is seeded and runs a full day: open till, receive, sell, return, count, close, reports; stock and balances exact |
| `TenantIsolationIT` | 13 | Now with Shop B holding one of every trading document: **every endpoint called as Shop A with Shop B's ids answers 404 and changes nothing** (over 80 endpoints), every list free of Shop B (TC-SEC-01) |
| `CostHidingIT` | 3 | Every GET endpoint as a cashier, including the new ones: no cost or profit anywhere (TC-SEC-04) |
| `ArchitectureTest` | 8 | Adds: only the stock engine writes stock tables |
| Stage 1 suites | 45 | Unchanged and green |

Frontend after regenerating the API types: typecheck, lint, 15 unit tests and the production build all pass. The local API starts with the three demo shops now holding suppliers, customers, staff, stock and sales.

## Bugs found and fixed while testing

1. **Returns in parts lost a paisa.** Returning a 3-for-Rs 100 line one unit at a time refunded 33.33 three times (99.99). Return values are now differences of running totals, so parts always add up exactly (unit test added).
2. Several SQL strings lost a space where Java text blocks were joined (`p.unit_id` + `where`, `d90plus` + `from`). All are fixed and every report now runs in a test.
3. A Java `Long`/`long` mix in warranty claims unboxed a null (500 error). Fixed, and the same pattern was checked everywhere.
4. Open invoices of another shop's customer returned an empty list instead of 404. Fixed; the isolation sweep covers it.
5. The demo seeder left a till open on mobile shops. Fixed.

## Business rules I decided — please confirm or change

| # | Rule as built | Where |
|---|---|---|
| 1 | When a refund is smaller than allowed, the rest is a **return charge** the shop keeps (counted as income in P&L) | Returns |
| 2 | Returns after the shop's return days (default 7) and returns over Rs 5,000 need a manager's PIN; they are not refused | Settings `return_days`, `return_limit` |
| 3 | Owners and users with "approve with PIN" approve their own over-limit actions; others need a PIN for any discount without discount permission, and for discounts above the shop's limit (default 10%) | Selling |
| 4 | Credit limit 0 means no credit without a manager's PIN; walk-in sales cannot leave money owing | Customers |
| 5 | Bonus items lower the unit cost (10 billed + 1 free → cost spread over 11) | Purchasing |
| 6 | Freight on a purchase bill is added to what is owed but not yet to item cost (landed cost is Phase 2, PUR-08) | Purchasing |
| 7 | A transfer received short keeps the missing quantity as "short" on the transfer; it does not go back to the sending branch | Transfers |
| 8 | Count differences for IMEI items are not posted; they must be added or written off by IMEI | Stock counts |
| 9 | An exchange where the returned items are worth more than the new ones is refused (take a return with a refund instead) | Exchanges |
| 10 | Credit notes are valid 90 days (setting) | Returns |
| 11 | Read-only shops cannot record customer payments either (every write is blocked, as ONB-05 says "not change anything") | Billing |
| 12 | A shop created without a trial gets its first invoice due on its start date and goes to Grace the next day if unpaid | Billing |
| 13 | SMS beyond the plan's monthly SMS are not sent (no automatic top-up) | Notifications |
| 14 | Average cost is one figure per product across all branches | Stock |

## Not in this stage (as planned) and known limits

- Screens for all of this are Stage 4 (including the scan-only fast till, the lock screen with PIN unlock moved from Stage 2, and the POS retry queue on the till).
- PDFs are in English; Urdu shaping in PDFs is not supported by the PDF library. Urdu receipts print from the browser in Stage 4.
- SMS gateway request format is assumed to be `POST {to, message}` with a bearer token. **I need the school system gateway's request format** to match it exactly.
- Daily database dumps to R2 and monitoring are Stage 5; load tests (TC-PRF-01) are Stage 6.
- Phase 2 items stay out: ABC analysis, loyalty, promotions, prescriptions, repairs, installments, landed cost, FBR.

## How to run

The local API is running now with seeded demo shops (`demo-mobile`, `demo-mart`, `demo-pharmacy`, user `owner`, password `Demo-Shop-2026`). To start it again:

```bash
cd backend
./mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"
```

## Needs from you

1. Confirm or change the business rules above (especially 1, 3, 9 and 11).
2. The SMS gateway request format from the school system.
3. Approval to start **Stage 4 — Frontend B: daily-work screens**, after which you will check the UI.
