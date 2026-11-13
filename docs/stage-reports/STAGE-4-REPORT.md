# Stage 4 report — Frontend B: daily-work screens

Date: 2026-11-09 · Status: **complete, waiting for your approval of the POS flow**

## Done

Every screen staff use each day now exists and is connected to the Stage 3 API.

**Point of sale** (`/pos`)
- Catalogue cached in memory and IndexedDB. The server's version string (ETag, 304 when unchanged) keeps it fresh every minute and after each sale. Search and scan never wait for the network.
- Scan or type a name, SKU, formula or part number. Pack barcodes (cartons) add the pack unit. `3*code` adds 3. Scanning an IMEI adds that exact phone. Scans made while products are still loading are kept and added when ready.
- Cart: quantities with decimals for weighed items, line discount (amount or `%`), price change, unit, batch choice (automatic = earliest expiry first), IMEI list.
- Bill discount (amount or `%`), tax on top or included.
- Totals follow exactly the same rules as the server (BigInt paisa, tested).
- Customer: search, or quick-add by name and phone (the server finds or creates them with the sale). Shows what the customer owes.
- Payment: split payments, quick-cash buttons, change from cash only. Anything short goes on the customer's account; it's blocked with a clear message when that isn't allowed.
- Hold and resume bills (shared per branch).
- Manager PIN dialog whenever the server says approval is needed (discount over the limit, price change, below cost, credit limit, write-off, return).
- Offline queue: if the server can't be reached, the sale is saved on the device with its Idempotency-Key and sent automatically; it's booked exactly once. A badge shows how many are waiting.
- Keyboard: F2 search, F4 customer, F7 held bills, F8 hold, F9 pay (or Enter on an empty search), Esc clears.
- **Fast till** button (super store): scan-only screen with a large input.
- Phone layout with the bill below and a large Pay button.
- Open the till with the opening float before the first sale.

**Lock screen**: locks after the shop's idle minutes, or with the lock button. The same user unlocks with their PIN; 5 wrong PINs lock the PIN. The lock survives a page reload.

**Receipts**: 80 mm, 58 mm and A4, in English, Urdu or both. They print from a hidden frame, so only the receipt prints. Reprint is marked DUPLICATE. Also PDF download and send by SMS or email. The cash drawer opens through the receipt printer's "open drawer on print" setting, which is how RJ11 drawers work with standard thermal printers.

**Till**: cash in and out, close with a note-by-note count (or a typed total), Z-report on screen and printed. Till history shows the difference for each shift (multi-till shift closing).

**Sales**: list with filters and totals. Sale detail shows the receipt preview, reprint, send, return (value clears what is owed first; refund by cash, account or credit note), exchange (items back plus new items, the difference paid or refunded) and cancel. Quotations: create, convert to a sale, cancel.

**Customers**: list, profile, ledger, unpaid bills, statement (PDF and email), receive payment (clears the oldest bills first), credit limit.

**Purchasing**:
- Suppliers with ledger, statement and payments.
- Purchase orders, with PDF, close and cancel.
- Receive goods from an order or without one: bonus quantity, line discount, new selling price, batch number and expiry, IMEIs, freight, tax, pay now.
- Goods received (GRN) detail, purchase returns from a bill or from stock, and the reorder screen that turns suggestions into orders in one click.

**Stock**:
- Stock list with values, plus a per-product view (branches, batches, in transit).
- Movements history.
- Adjustments (in or out, with approval), opening stock.
- Counts: scan to count, saves as you go, then post.
- Transfers: send, then receive at the other branch with the shortfall recorded.
- Alerts: low, out, near expiry, expired, not selling.

**Money and reports**: expenses (with a photo of the bill; void), daily cash, profit and loss by month, all 65 reports with filters, Excel and PDF, background export for big reports, and an Exports page with the full Excel backup.

**Home**:
- A dashboard that shows only what the user may see: sales, profit, cash in drawers, dues, alerts.
- A 14-day sales chart and top items.
- Onboarding checklist progress.

**Shop-type screens**:
- IMEI search with each phone's full history.
- Used-phone buyback with CNIC and phone photos (camera on phones).
- Warranty claims, checked against the sale.
- Near-expiry (alerts), and formula search at the till (pharmacy).
- Variant grid (sizes × colours, size sets) and bundle editor on the product page.

**Subscription**: plan usage, invoices, read-only notice. Banners show in the shell.

**Shared pieces built once, used everywhere**: a document line editor (products, units, cost and price, batch new or pick, IMEI new or existing), a list page with filters and paging, customer and supplier pickers, approval handling, and status badges.

## Tests

| Check | Result |
|---|---|
| Typecheck, lint | clean |
| Unit tests (Vitest) | 44 pass. These include 14 new cart tests (weighed rounding, discount spread, inclusive and exclusive tax, batch merge, IMEIs, change rules, sale body), plus the existing `money.json` vectors. |
| POS search speed | **50,000 products: search plus code lookup under 100 ms** (automated test, passes). |
| Production build | passes, every route |
| Playwright, page check | every Stage 4 page opens with no errors and no server errors in **demo-mart, demo-mobile and demo-pharmacy** |
| Playwright, journeys | **sale → return, receive goods, stock count by scanning → post, till close with Z-report: all pass for each of the 3 demo types** (12 journeys) |
| Playwright, Stage 2 suite | all pass (shop lifecycle, demo shop, phone, TOTP) |
| Backend | `SalesIT` 21/21 including the new IMEI search check |

One journey, "sell a phone by IMEI, then find it in IMEI search", passes the sale part. The search part needs the API restarted (see below).

Screenshots: [stage-4-screens/](stage-4-screens/) (28 images, desktop and phone).
Recording of a sale: [stage-4-screens/sale-recording.webm](stage-4-screens/sale-recording.webm). It shows search, scan, `3*code`, a 5 % bill discount, split payment, cash with change, and the sale done.

## Bugs found and fixed while testing

1. **Open till did nothing.** The till choice was set before the till list loaded, so the button silently did nothing. Fixed.
2. **First scan lost.** A scan made before the catalogue had loaded was dropped. Now it waits and is added.
3. **Count posted before saving.** "Post" right after a scan said "Nothing has been counted". Posting now saves pending counts first, and the screen shows "Saving…" until they are stored.
4. **Pharmacy double scan made two lines.** Batch items now merge while the batch is automatic.
5. **Sale status.** The API has only Posted or Cancelled. The screens now show Completed, Part returned or Returned from the amounts.
6. **IMEI search couldn't find a sold phone.** The API listed in-stock phones only. It now searches every status when you type an IMEI, or when `status=ALL` (backend change plus test).
7. **POS had no page heading when a till was open.** Added one for screen readers.

## Needs from you

1. **Approve the POS flow** (screenshots 01–04, 28 and the recording). Things to look at:
   - F-keys and Enter-to-pay.
   - Auto-print after each sale (default on, can be turned off on each till).
   - Change shown large after the sale.
   - The "Fast till" button for super stores.
2. **Restart the local API once** (`cd backend && ./mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"`) to load the IMEI search fix. The demo data is re-seeded on restart. My attempt to stop the running API was blocked, so I left it running.
3. A receipt printer to test on, when you have one (80 mm or 58 mm). Set the driver's "open cash drawer" option to "on print".

## Known limits

- Printing uses the browser's print dialog. Silent printing needs Chrome's kiosk-printing flag on the shop PC, which belongs to the Stage 5 setup guide.
- The offline queue covers sales only. Other screens (purchases, counts) need the connection.
- On the exchange screen the "Customer pays" figure is before tax. The server books the exact amount including tax, and anything short goes on the customer's account.
- The demo items have no barcodes, so the tests scan SKUs. Real barcodes work the same way.

## How to run

```
cd backend && ./mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"
cd frontend && npm run dev
# open http://demo-mart.localhost:3000 (or demo-mobile / demo-pharmacy), user owner / Demo-Shop-2026
npx playwright test                      # all journeys
node scripts/stage4-screens.mjs          # screenshots and the sale recording
```
