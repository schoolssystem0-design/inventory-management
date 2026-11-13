# Stage 4b report — Phase 2 shop-type modules

Date: 2026-11-13 · Status: **built and tested; screenshots waiting for a dev-server restart**

Added at your request after Stage 4, so that every one of the 10 shop types has all its default modules before deployment. Full testing of all types is in Stage 6.

## Done

All seven Phase 2 modules that are on by default for some shop type are now built: server, screens and demo data.

| Module | Shop types (on by default) | What it does |
|---|---|---|
| **Weighing-scale labels** (SUP-03, HW-05) | Super store | Each product can have a scale code. Scale labels (EAN-13 starting 20–29) scan at the till as that item with the weight, or with the price (shop setting). They also scan on other screens, such as stock counts. |
| **Offers and coupons** (SUP-05, XSL-02) | Super store, garments, cosmetics | Percent off, amount off, buy X get Y free, N for a price, and percent or amount off bills over a minimum. They can cover all items, categories or chosen products, with dates, days of the week and hours (happy hour). Coupons apply only with their code and can have a use limit. The till applies offers by itself and shows them on each line and on the receipt. |
| **Loyalty points** (CUS-06, SUP-06) | Super store, garments, cosmetics | Points are earned on what is paid and can pay part of a bill. They expire after N months without a purchase (nightly job). A return takes back the points earned on it; a cancel also gives back the points used. The customer page has a points tab with history and manual changes. |
| **Repair jobs** (MOB-07) | Mobile | Job card with device, IMEI, fault, accessories, condition, estimate and advance; a technician, a promised date, statuses and history; parts and labour. The customer gets an SMS when the device is ready. Handing it back makes a normal invoice: parts leave stock and the advance counts as paid. The job card prints. |
| **Installments** (MOB-08, ELE-03) | Electronics | A plan is made from an invoice on account: months, markup %, grace days, late fee per day, and a required guarantor. The schedule, due and late lists, and taking an installment (a normal customer payment that also marks the schedule) are all built. The plan prints for signatures. Markup and late fees show as other income in profit and loss. |
| **Prescriptions and controlled register** (MED-08, MED-09) | Pharmacy | Rx medicines need a prescription at the till (patient, doctor, photo; shop setting). Controlled medicines always need one plus the pharmacist's PIN, and every sale and return is written into the register, which can be printed. |
| **Deliveries and challans** (ELE-04, HRD-04) | Electronics, hardware | Any invoice or challan can get a delivery (address, date, driver, vehicle, installation), tracked from pending through on the way to delivered, installed or failed. The customer gets an SMS when it is on the way. Goods on a challan leave stock at once; the challan is later billed at its prices (stock does not move twice) or cancelled (stock back). The challan prints. |

**Also:**
- Two more local demo shops: **demo-electronics** and **demo-hardware**.
- Demo data for every module: offers, a coupon (WELCOME10), points, scale codes, five repair jobs in different states, a current and a late installment plan, prescriptions and a controlled sale, deliveries and challans.
- The Pharmacist role can now approve with their PIN. They can only approve medicine rules, because approval also needs the matching permission.
- New permissions: offers, loyalty, installments, deliveries. The owner and manager roles get them.
- "Shop-type rules" settings tab (scale label value, Rx prescription rule, loyalty rates).

## Decisions I made (change any you disagree with)

1. **What you type always wins.** An offer only applies to a line sold in its base unit, at the list price, with no discount typed by the cashier. Only the best offer applies to each line, and two offers never stack.
2. **Offers never need a manager's PIN** and do not count towards the discount limit.
3. **Scale labels with a price** use whole rupees (up to Rs 99,999), not paisa, because local per-kg prices often pass Rs 999.
4. **Repair advance** taken in cash goes into the till at once; on hand-back it counts as paid on the invoice. Labour is billed with a "Repair labour" service item made automatically.
5. **Delivery charges** are not a field on the delivery; charge them as a line on the invoice.
6. **Late fees** are charged to the account when the next installment is taken (days late after the grace days × fee per day). The due list shows what they would be now.
7. **Returns** take back the points earned on what was returned. Points used on the bill are only given back when the whole bill is cancelled; a part return is refunded in the chosen way.

## Tests

| Check | Result |
|---|---|
| Server: offers, coupons and scale labels against shared numbers (`docs/test-vectors/promotions.json`) | 5 test groups pass |
| Till: the same shared numbers (Vitest) | 28 pass |
| Till: cart totals with offers and coupons | 4 new tests pass (46 POS tests in all) |
| Server: one test per module through the API (`Phase2ModulesIT`) | 8 / 8 pass |
| Shop isolation test, now with every new record type | passes (no Shop B record visible to Shop A, including the new endpoints) |
| Full trading day for all 10 shop types | passes |
| Full backend suite | **179 / 179 pass** |
| Typecheck, lint, production build | clean |
| Playwright: Stage 4b screens in all 5 demo shops, a repair job, an offer on the bill at the till | **all pass** |
| Playwright: Stage 4 suite again (pages, journeys, IMEI search) | all pass. The IMEI search journey now passes with the new API. The pharmacy sale journey needed a prescription step, now added; its re-run was cut off when the dev server broke (see below). |

## Needs from you

1. **Restart the dev server on :3000.** Its build workers crashed, so every page answers 500 ("Jest worker encountered 2 child process exceptions"). My attempt to stop it was blocked. Stop it (Ctrl+C in its window, or `taskkill /PID 9424 /F`), then run `cd frontend && npm run dev`. The local API on :8080 is fine and already runs the new code with five demo shops: demo-mart, demo-mobile, demo-pharmacy, demo-electronics, demo-hardware. After the restart I re-run the pharmacy journey and take the screenshots (`node scripts/stage4b-screens.mjs`).
2. Look over the seven decisions above.

## Known limits

- Happy hours use the till's clock on screen and the server's clock when the sale is booked. A bill paid just after happy hour ends is booked at the full price; if the cash given no longer covers it, the till says how much more to collect.
- An installment paid through the normal "Receive payment" screen (not from the plan) reduces what the customer owes but does not mark the schedule. Take installments from the plan page.
- Challans cannot carry IMEI items or bundles; sell those on an invoice with a delivery.
- Gift vouchers, price levels and the other Phase 2 modules not on by default for any type are still Phase 2.
