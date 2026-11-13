# Requirements Register

Every requirement from *Multi-Shop Inventory Platform — Requirements* (PDF) with a stable ID. IMPLEMENTATION-PLAN.md work packages and QA-TEST-PLAN.md test cases refer to these IDs. Pull requests name the IDs they implement.

**Columns:** ID · requirement (short) · Phase (1, 2, 3) · PDF section. Wording in the PDF is the full statement; this register is the index.

**ID prefixes:** PLT platform/super admin · ACC access/roles · ONB onboarding/life cycle · SET shop setup · CAT catalogue · POS point of sale · SAL sales/returns · CUS customers · PUR purchasing · STK stock · CSH cash/expenses · RPT reports/dashboard · NTF notifications · MOB mobile shop · SUP super store · MED medical store · GAR garments · ELE electronics · HRD hardware · OTH other types · AGL accounting · STF staff · XSL selling extras · DST distribution · ONL online · BRN branches/franchise · INT integrations · HW hardware · OPS platform operations · NFR non-functional · BIL plans and billing

## Platform and super admin

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| PLT-01 | Separate platform portal with its own login; platform users never appear in shop user lists | 1 | 2, 4 |
| PLT-02 | Platform roles: super admin, support, billing, onboarding; reseller portal | 1 (reseller 2) | 2 |
| PLT-03 | Two-step login (TOTP) required for platform users | 1 | 2 |
| PLT-04 | Overview: shop counts by status/type/city; revenue (this month, MRR, overdue); expiring trials and subscriptions | 1 | 4.1 |
| PLT-05 | Shop health: no sales in 7 days, failed backups, storage near limit | 2 | 4.1 |
| PLT-06 | Cross-shop totals (counts only, no customer data) | 2 | 4.1 |
| PLT-07 | Shop list with search (name, code, owner phone, city) and filters (type, plan, status) | 1 | 4.2 |
| PLT-08 | 5-step shop creation wizard: details, type, plan, starting data, owner login | 1 | 3, 4.2 |
| PLT-09 | Shop code becomes the subdomain; uniqueness checked; suggestion from name | 1 | 3 |
| PLT-10 | Shop profile with modules, plan, limits and usage, branches, owner, subscription history, notes | 1 | 4.2 |
| PLT-11 | Switch modules on/off per shop on top of type defaults | 1 | 4.2 |
| PLT-12 | Change shop type with warning; data kept; removed modules hidden | 2 | 4.2 |
| PLT-13 | Owner access: resend setup link, reset owner login, change owner with reason | 1 | 4.2 |
| PLT-14 | Suspend with reason shown on login page; reactivate | 1 | 4.2 |
| PLT-15 | Delete shop: type code + reason, final export offered, removal after 90 days | 1 | 4.2 |
| PLT-16 | Custom domains: add, verify, set primary; subdomain always works | 2 | 4.2 |
| PLT-17 | Support mode: read-only by default, owner approval code for edits, banner, every action logged | 1 | 4.2 |
| PLT-18 | Import client data (products, customers, suppliers) during onboarding | 1 | 4.2 |
| PLT-19 | Shop type list with default modules, fields, categories, units | 1 | 4.3 |
| PLT-20 | Edit type defaults; push to existing shops optional | 2 | 4.3 |
| PLT-21 | Create a new shop type from existing modules without code | 2 | 4.3 |
| PLT-22 | Excel import template per shop type | 1 | 4.3 |
| PLT-23 | Platform users management | 1 | 4.5 |
| PLT-24 | Platform settings: company details, SMS gateway, email sender, trial days, grace days | 1 | 4.5 |
| PLT-25 | Announcements to all shops, a type or one shop | 2 | 4.5 |
| PLT-26 | Support tickets raised from inside a shop | 2 | 4.5, 14.2 |
| PLT-27 | Platform audit log: who, when, which shop, old/new values; read-only | 1 | 4.5 |
| PLT-28 | Backups view; restore one shop to a point in time | 2 | 4.5 |

## Plans and billing

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| BIL-01 | Plans with monthly/yearly price and limits (users, branches, products, tills, SMS, storage) | 1 | 4.4, 9 |
| BIL-02 | Starter Rs. 2,500 / Business Rs. 5,500 / Enterprise Rs. 12,000 per month; yearly = 10 months; setup Rs. 5,000–20,000 (editable data) | 1 | 9, 16 |
| BIL-03 | Add-ons: extra user, branch, SMS pack, FBR, custom domain | 2 | 4.4, 9 |
| BIL-04 | Subscription per shop: plan, cycle, start, next due, price, discount, status | 1 | 4.4 |
| BIL-05 | Subscription invoices and payments (cash, bank, JazzCash, Easypaisa) with receipt | 1 | 4.4 |
| BIL-06 | Reminders 7, 3, 1 days before due and on overdue | 1 | 4.4 |
| BIL-07 | Automatic status Trial → Active → Grace (7 days) → Read-only; super admin override with reason | 1 | 3, 4.4 |
| BIL-08 | Plan limits enforced with upgrade message; nothing deleted on downgrade | 1 | 4.4, 9 |
| BIL-09 | Upgrades immediate (pro-rata); downgrades next cycle | 1 | 9 |
| BIL-10 | Free trial 14 days (setting) with Business features | 1 | 9 |
| BIL-11 | Online subscription payment by the owner | 3 | 4.4 |

## Onboarding and shop life cycle

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| ONB-01 | Owner setup link (48 h) by SMS/email; no password typed by us | 1 | 3 |
| ONB-02 | Owner first-login checklist: profile, users, products, opening stock, printer, test sale | 1 | 3 |
| ONB-03 | Opening stock posted as opening balance, not a purchase | 1 | 3 |
| ONB-04 | Shop statuses Trial, Active, Grace, Read-only, Suspended, Closed with defined access | 1 | 3 |
| ONB-05 | Read-only shops can view, print, export but not sell or change stock | 1 | 3 |
| ONB-06 | Closed shops: data kept 90 days, then deleted after a final export is offered | 1 | 3, 14.3 |
| ONB-07 | Starting data per type: default categories and units; branches created | 1 | 3 |

## Access and roles

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| ACC-01 | Shop roles: owner, partner (view), branch manager, cashier, salesman, stock keeper, purchaser, accountant, order booker, rider, pharmacist, technician | 1 (booker, rider 2) | 2 |
| ACC-02 | Custom roles with per-module view/add/edit/delete/approve/export | 2 | 2 |
| ACC-03 | Users tied to branches see only those branches | 1 | 2 |
| ACC-04 | Approval limits with manager PIN (discount, below-cost price, returns, write-offs, credit limit) | 1 | 2 |
| ACC-05 | Cost, profit and supplier prices hidden without cost permission in screens, prints, exports | 1 | 2 |
| ACC-06 | Login history; lock after 5 failed attempts | 1 | 2 |
| ACC-07 | Activity history with old/new values | 1 | 2 |
| ACC-08 | Password reset by code or temporary password; passwords never shown | 1 | 2 |
| ACC-09 | Session timeout setting; POS locks with PIN | 1 | 2 |
| ACC-10 | Optional two-step login for shop owners | 1 | 8.3 |

## Shop setup

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| SET-01 | Business profile, logo, NTN/STRN, receipt header/footer, return policy | 1 | 6.1 |
| SET-02 | Branches and warehouses with own stock, tills, users | 1 | 6.1 |
| SET-03 | Tax: none/inclusive/exclusive, per-product override | 1 | 6.1 |
| SET-04 | Stock rules: allow negative or not; weighted average costing; rounding | 1 | 6.1 |
| SET-05 | Receipts: A4, 80 mm, 58 mm; English, Urdu or both; show/hide options | 1 | 6.1 |
| SET-06 | Document numbering prefixes per branch | 1 | 6.1 |
| SET-07 | Payment methods on/off: cash, card, bank, JazzCash, Easypaisa, cheque, account | 1 | 6.1 |
| SET-08 | Settings change history | 1 | 6.1 |

## Catalogue

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| CAT-01 | Product record with Urdu name, SKU, barcodes, category, brand, unit, cost, prices, min price, tax, reorder level, image, type fields | 1 | 6.2 |
| CAT-02 | Multiple barcodes per product | 1 | 6.2 |
| CAT-03 | Category tree and brands | 1 | 6.2 |
| CAT-04 | Price levels (retail, wholesale, special) | 2 | 6.2 |
| CAT-05 | Bulk price update by % or amount with preview | 1 | 6.2 |
| CAT-06 | Excel import with per-row errors; export catalogue | 1 | 6.2 |
| CAT-07 | Barcode labels in 3 sizes; generate barcodes for items without one | 1 | 6.2 |
| CAT-08 | Product history: movements, price changes, sales | 1 | 6.2 |
| CAT-09 | Used products deactivated, not deleted | 1 | 6.2 |

## POS

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| POS-01 | Search/scan, qty, price (if allowed), line discount; keyboard shortcuts; touch and phone friendly | 1 | 6.3 |
| POS-02 | Cash with change, card, wallets, split payment, on account, partial payment | 1 | 6.3 |
| POS-03 | Bill discount within user's limit; above needs manager PIN | 1 | 6.3 |
| POS-04 | Hold and resume bills | 1 | 6.3 |
| POS-05 | Customer on bill (pick or quick-add by phone) with balance and points | 1 | 6.3 |
| POS-06 | Print, reprint, SMS/WhatsApp receipt link | 1 (SMS 2) | 6.3 |
| POS-07 | Till sessions: open with float, close with count, difference, Z-report | 1 | 6.3 |
| POS-08 | Cash in/out with reason | 1 | 6.3 |
| POS-09 | Cached catalogue and retry queue for short internet drops | 1 | 16 |
| POS-10 | Full offline selling with sync | 2 | 6.3, 16 |
| POS-11 | Customer display | 3 | 6.3 |

## Sales and returns

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| SAL-01 | Sales list with filters (date, branch, cashier, customer, method, status) | 1 | 6.4 |
| SAL-02 | Full/partial return against invoice; restock or damaged; refund cash/account/credit note | 1 | 6.4 |
| SAL-03 | Exchange in one step with difference | 1 | 6.4 |
| SAL-04 | Return without invoice with manager approval | 2 | 6.4 |
| SAL-05 | Same-day cancellation by manager with reason; never deleted | 1 | 6.4 |
| SAL-06 | Quotations and sales orders converting to invoices | 1 (module) | 6.4, 7.6 |

## Customers

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| CUS-01 | Customer record with type, credit limit | 1 | 6.5 |
| CUS-02 | Ledger and statement (print/WhatsApp) | 1 | 6.5 |
| CUS-03 | Receive payment; oldest invoices first; receipt | 1 | 6.5 |
| CUS-04 | Credit limit check with manager PIN | 1 | 6.5 |
| CUS-05 | Overdue list and reminders | 2 | 6.5 |
| CUS-06 | Loyalty points earn/redeem/expire | 2 | 6.5, 7.2 |

## Purchasing

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| PUR-01 | Supplier record with terms and opening balance | 1 | 6.6 |
| PUR-02 | Purchase orders with statuses; PDF/WhatsApp | 1 | 6.6 |
| PUR-03 | GRN: full/partial, with or without PO, bill number, bonus items | 1 | 6.6 |
| PUR-04 | Weighted average cost on receipt | 1 | 6.6 |
| PUR-05 | Purchase returns to supplier | 1 | 6.6 |
| PUR-06 | Supplier payments oldest first; ledger and statement | 1 | 6.6 |
| PUR-07 | Low stock to PO with suggested quantity (on order + 30-day sales) | 1 | 6.6 |
| PUR-08 | Landed cost (freight spread) | 2 | 6.6 |

## Stock

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| STK-01 | Stock per branch with cost and retail value | 1 | 6.7 |
| STK-02 | Adjustments with reasons and value written off | 1 | 6.7 |
| STK-03 | Stock count (all/part), scan counts, differences posted after approval | 1 | 6.7 |
| STK-04 | Transfers with dispatch and receive (in transit) | 1 | 6.7 |
| STK-05 | Stock history per unit with balance after, user, document | 1 | 6.7 |
| STK-06 | Alerts: low, out, near expiry, dead stock | 1 | 6.7 |

## Cash and expenses

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| CSH-01 | Expenses with category, account, attachment | 1 | 6.8 |
| CSH-02 | Cash/bank/wallet accounts and transfers | 2 | 6.8 |
| CSH-03 | Daily cash summary per branch | 1 | 6.8 |
| CSH-04 | Profit and loss by month and branch | 1 | 6.8 |

## Dashboard, reports, notifications

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| RPT-01 | Dashboard by role: sales, profit, invoices, cash, dues, low stock, 14-day chart, top items | 1 | 6.9 |
| RPT-02 | Every report in section 13 of the PDF, filtered by date and branch, with Excel/PDF export | 1–3 per row | 13 |
| RPT-03 | Owner daily summary by SMS/WhatsApp/email | 2 | 6.9, 13 |
| RPT-04 | Reports hide cost/profit without permission | 1 | 13 |
| NTF-01 | SMS via Android gateway, credits per plan | 1 | 6.10, 12.1 |
| NTF-02 | Paid SMS API with branded sender | 2 | 12.1 |
| NTF-03 | WhatsApp click-to-send; Business API later | 2 / 3 | 12.1 |
| NTF-04 | In-app alerts bell | 1 | 6.10 |
| NTF-05 | Email: receipts, statements, resets, invoices | 1 | 12.1 |

## Shop-type modules

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| MOB-01 | IMEI tracking per unit with IMEI2, colour, storage, condition, PTA status; scan at purchase and sale | 1 | 7.1 |
| MOB-02 | IMEI search with full history | 1 | 7.1 |
| MOB-03 | Duplicate IMEI blocked | 1 | 7.1 |
| MOB-04 | Buy used phones with seller CNIC, photos, slip | 1 | 7.1 |
| MOB-05 | Accessories sold with phones on one bill | 1 | 7.1 |
| MOB-06 | Shop warranty card and claim log | 1 | 7.1 |
| MOB-07 | Repair job cards with statuses, parts, SMS when ready | 2 | 7.1 |
| MOB-08 | Installments with guarantor, schedule, due list, late fee | 2 | 7.1 |
| MOB-09 | Mobile load/bill payment tracking | 3 | 7.1 |
| SUP-01 | Fast scan-only till mode | 1 | 7.2 |
| SUP-02 | Weighed items with decimal quantity | 1 | 7.2 |
| SUP-03 | Scale barcodes (prefix 20–29) | 2 | 7.2 |
| SUP-04 | Carton/piece units with conversion and carton barcode | 1 | 7.2 |
| SUP-05 | Offers: buy X get Y, %/amount, bundle price, happy hour | 2 | 7.2 |
| SUP-06 | Loyalty points | 2 | 7.2 |
| SUP-07 | Shift closing per till with short/over | 1 | 7.2 |
| SUP-08 | Shelf labels on price change | 2 | 7.2 |
| SUP-09 | Optional expiry on food | 2 | 7.2 |
| MED-01 | Medicine record: formula, strength, form, company, pack, schedule, rack, registration | 1 | 7.3 |
| MED-02 | Batches and expiry per receipt; stock per batch | 1 | 7.3 |
| MED-03 | FEFO picking with override | 1 | 7.3 |
| MED-04 | Expired batches blocked from sale | 1 | 7.3 |
| MED-05 | Near-expiry report 30/60/90 days with return list | 1 | 7.3 |
| MED-06 | Sell by box/strip/tablet | 1 | 7.3 |
| MED-07 | Formula search for alternatives | 1 | 7.3 |
| MED-08 | Prescriptions linked to invoice; required for Rx items (setting) | 2 | 7.3 |
| MED-09 | Controlled medicines: pharmacist approval, register, printout | 2 | 7.3 |
| MED-10 | Distributor terms: bonus, trade price, discount | 1 | 7.3 |
| MED-11 | Drug licence on receipts; expiry reminder | 2 | 7.3 |
| GAR-01 | Variants grid size × colour with own barcode and stock | 1 | 7.4 |
| GAR-02 | Size sets | 1 | 7.4 |
| GAR-03 | Season and collection tags; clearance | 2 | 7.4 |
| GAR-04 | Exchange-first returns; credit note setting | 1 | 7.4 |
| GAR-05 | Variant stock report | 1 | 7.4 |
| ELE-01 | Serial numbers at purchase and sale | 1 | 7.5 |
| ELE-02 | Company warranty claims | 2 | 7.5 |
| ELE-03 | Installments | 2 | 7.5 |
| ELE-04 | Delivery and installation | 2 | 7.5 |
| HRD-01 | Multiple units with conversion | 1 | 7.6 |
| HRD-02 | Quotations | 1 | 7.6 |
| HRD-03 | Contractor accounts, site-wise bills | 2 | 7.6 |
| HRD-04 | Delivery challans converted to invoices | 2 | 7.6 |
| HRD-05 | Paint tinting | 3 | 7.6 |
| OTH-01 | Cosmetics: shades and expiry | 1 | 7.7 |
| OTH-02 | Books: ISBN fields; class book sets; school orders | 1 (orders 2) | 7.7 |
| OTH-03 | Auto parts: part numbers, alternates; vehicle fit; workshop accounts | 1 (fit 2) | 7.7 |
| OTH-04 | Bakery and sweets: recipes, production, advance orders | 2 | 10 |
| OTH-05 | Wholesale/distribution type | 2 | 10 |
| OTH-06 | Grocery wholesale: carton/dozen/piece prices; daily rates | 2 | 10 |
| OTH-07 | Jewellery: weight, karat, gold rate, making, old-gold exchange | 3 | 10 |
| OTH-08 | Furniture: made-to-order, delivery, showroom/godown | 3 | 10 |
| OTH-09 | Opticals: eye prescription, lens orders, lab status | 3 | 10 |
| OTH-10 | Agri store: season credit, controlled fertiliser prices, schemes | 3 | 10 |
| OTH-11 | Pet/gift/toy/sports templates | 2 | 10 |
| OTH-12 | Restaurant/café light: menu from recipes, kitchen slip | 3 | 10 |

## Extra modules

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| AGL-01 | Chart of accounts | 2 | 11.1 |
| AGL-02 | Automatic double-entry posting from every document | 2 | 11.1 |
| AGL-03 | Journal vouchers with approval | 2 | 11.1 |
| AGL-04 | Cheques: post-dated, cleared, bounced | 2 | 11.1 |
| AGL-05 | Bank reconciliation | 3 | 11.1 |
| AGL-06 | Trial balance, P&L, balance sheet, cash flow | 3 | 11.1 |
| AGL-07 | Year closing and period lock | 3 | 11.1 |
| STF-01 | Sales commission | 2 | 11.2 |
| STF-02 | Simple staff attendance | 3 | 11.2 |
| STF-03 | Salaries and advances | 3 | 11.2 |
| STF-04 | Sales targets | 3 | 11.2 |
| XSL-01 | Gift vouchers and credit notes | 2 | 11.3 |
| XSL-02 | Coupons | 2 | 11.3 |
| XSL-03 | Advance booking / layaway with reserved stock | 2 | 11.3 |
| XSL-04 | Special orders linked to PO | 2 | 11.3 |
| XSL-05 | Consignment stock | 3 | 11.3 |
| XSL-06 | Price checker | 3 | 11.3 |
| XSL-07 | Customer feedback link | 3 | 11.3 |
| XSL-08 | Product recall by batch/serial | 2 | 11.3 |
| DST-01 | Routes and areas | 2 | 11.4 |
| DST-02 | Order booking on phone (offline) | 2 | 11.4 |
| DST-03 | Load sheets and delivery | 2 | 11.4 |
| DST-04 | Recovery sheets | 2 | 11.4 |
| DST-05 | Trade schemes and claims | 2 | 11.4 |
| ONL-01 | Online store page | 3 | 11.5 |
| ONL-02 | Online orders list | 3 | 11.5 |
| ONL-03 | WooCommerce/Shopify sync | 3 | 11.5 |
| ONL-04 | Marketplace order import | 3 | 11.5 |
| ONL-05 | WhatsApp catalogue share | 2 | 11.5 |
| BRN-01 | Central catalogue with branch overrides | 2 | 11.6 |
| BRN-02 | Branch stock requests | 2 | 11.6 |
| BRN-03 | Consolidated branch reports | 1 | 11.6 |
| BRN-04 | Franchise mode | 3 | 11.6 |

## Integrations and hardware

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| INT-01 | FBR POS real-time invoices, QR, retry queue | 2 (1 for Tier-1 client) | 12.1, 16 |
| INT-02 | Raast/QR and wallet references at POS | 2 | 12.1 |
| INT-03 | Accounting export | 3 | 12.1 |
| INT-04 | Public API and webhooks | 3 | 12.1 |
| INT-05 | Import from other software (mapping screen) | 1 | 12.1 |
| HW-01 | USB/Bluetooth scanners everywhere; camera scanning on HTTPS | 1 | 12.2 |
| HW-02 | Thermal printers 80/58 mm; print helper for silent printing | 1 / 2 | 12.2 |
| HW-03 | Label printers and A4 sheets | 1 | 12.2 |
| HW-04 | Cash drawer via printer | 1 | 12.2 |
| HW-05 | Scale barcodes; direct scale connection | 2 / 3 | 12.2 |
| HW-06 | Works on PC, Android, iPhone from 360 px | 1 | 12.2 |
| HW-07 | Installable app (PWA) | 1 | 12.2 |

## Platform operations

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| OPS-01 | Demo shop per type, reset nightly | 1 | 14.1 |
| OPS-02 | Public sign-up/demo request form → leads | 2 | 14.1 |
| OPS-03 | Leads pipeline | 2 | 14.1 |
| OPS-04 | Copy shop setup to a new shop | 2 | 14.1 |
| OPS-05 | Onboarding checklist per shop | 1 | 14.1 |
| OPS-06 | Reseller programme with commission | 2 | 14.1 |
| OPS-07 | Referral reward (one month free) | 2 | 14.1 |
| OPS-08 | In-app help and videos; first-time tour; what's new | 2 | 14.2 |
| OPS-09 | Support response times by plan | 1 | 14.2 |
| OPS-10 | Staging before production; feature flags | 1 / 2 | 14.3 |
| OPS-11 | Releases outside shop hours; backward-compatible migrations | 1 | 14.3 |
| OPS-12 | Monitoring, error tracking, uptime alerts | 1 | 14.3 |
| OPS-13 | Rate limits; background exports | 1 | 14.3 |
| OPS-14 | Owner data export any time | 1 | 14.3 |
| OPS-15 | Retention: closed shops 90 days, backups 30 days, audit 2 years | 1 | 14.3 |
| OPS-16 | Terms and privacy acceptance stored; data ownership | 1 | 14.4 |

## Non-functional

| ID | Requirement | Phase | PDF |
|---|---|---|---|
| NFR-01 | Shop data separation: shop_id on every record, automatic filter, server-side module checks, isolation tests | 1 | 8.1 |
| NFR-02 | Exact money (numeric/BigDecimal); quantities 3 decimals | 1 | 8.2 |
| NFR-03 | Document, stock, payment and balance saved in one transaction | 1 | 8.2 |
| NFR-04 | Stock row locked; no overselling, no double-sold serial | 1 | 8.2 |
| NFR-05 | Stock rebuildable from history | 1 | 8.2 |
| NFR-06 | Sale stores cost at time of sale | 1 | 8.2 |
| NFR-07 | Cancel/reverse, never delete; deactivate used master data | 1 | 8.2 |
| NFR-08 | Add-only Flyway migrations | 1 | 8.2 |
| NFR-09 | Hashed passwords, lockout, secure cookies, manager PIN, private uploads, personal data masking | 1 | 8.3 |
| NFR-10 | POS search < 100 ms; save sale < 1 s; page < 1.5 s on 4G | 1 | 8.4 |
| NFR-11 | 50,000 products and 5,000 invoices/day per shop | 1 | 8.4 |
| NFR-12 | 99.5% uptime; health checks and alerts | 1 | 8.4 |
| NFR-13 | Daily dump to R2 (30 days), Railway backups, monthly restore test, owner Excel backup | 1 | 8.4 |
| NFR-14 | Urdu product names and receipts; Urdu interface Phase 3 | 1 / 3 | 8.4 |
