# Shop Types and Modules

Source: requirements PDF, sections 5, 7, 10 and 11. This file turns them into keys the code uses.

## 1. How it works

1. The super admin picks a **shop type** in the creation wizard.
2. The type's `default_modules` are copied into `shop_module` with `source = 'type'`.
3. The **plan** can block modules. Starter gets only the type's Phase 1 modules (`plan_module_rules`).
4. The super admin (any module) or owner (only modules marked `owner_can_toggle`) can override, which writes `source = 'override'`.
5. Switching a module **off** hides its screens and blocks its API. Its data stays. Switching it back on shows the data again.
6. Changing a shop's type adds the new type's defaults and never removes modules that hold data.

Core modules (`core.*`) are always on and cannot be switched off.

## 2. Module keys

| Key | Module | Owner can toggle | Phase | Depends on |
|---|---|---|---|---|
| `core.catalog` | Products, categories, brands, units, barcodes, labels, import | — (always on) | 1 | |
| `core.pos` | POS, invoices, payments, holds, tills | — | 1 | core.catalog |
| `core.returns` | Returns, exchanges, credit notes | — | 1 | core.pos |
| `core.customers` | Customers, ledger, credit | — | 1 | |
| `core.purchasing` | Suppliers, POs, GRN, purchase returns, supplier payments, reorder | — | 1 | |
| `core.stock` | Branches, adjustments, counts, transfers, history, alerts | — | 1 | |
| `core.cash` | Expenses, daily cash summary, P&L | — | 1 | |
| `core.reports` | Dashboard and reports | — | 1 | |
| `serials` | IMEI / serial numbers per unit | Yes | 1 | |
| `used_buyback` | Buy used items from walk-in sellers | Yes | 1 | serials |
| `warranty` | Warranty cards and claims | Yes | 1 | |
| `repairs` | Repair job cards | Yes | 2 | |
| `installments` | Installment plans and recovery | Yes | 2 | core.customers |
| `variants` | Size/colour/shade grids | Yes | 1 | |
| `batches` | Batch numbers, expiry, FEFO | No (super admin only: it changes how stock is kept) | 1 | |
| `pack_units` | Units and pack sizes (carton/piece, box/strip/tablet) | Yes | 1 | |
| `formula` | Generic/formula search, alternatives | Yes | 1 | |
| `prescriptions` | Prescriptions, controlled medicines register | Yes | 2 | |
| `weighed` | Weighed items (decimal qty by kg) | Yes | 1 | |
| `scale_barcodes` | Price/weight-embedded barcodes | Yes | 2 | weighed |
| `promotions` | Offers, coupons | Yes | 2 | |
| `loyalty` | Loyalty points | Yes | 2 | core.customers |
| `gift_vouchers` | Gift vouchers | Yes | 2 | |
| `multi_till` | Several tills, shift closing per till | Yes | 1 | |
| `quotations` | Quotations and sales orders | Yes | 1 | |
| `delivery` | Delivery and challans | Yes | 2 | |
| `bundles` | Bundles/kits (book sets) | Yes | 1 | |
| `part_numbers` | OEM/alternate part numbers | Yes | 1 | |
| `vehicle_fit` | Make/model/year fitment | Yes | 2 | part_numbers |
| `layaway` | Advance booking / reservation | Yes | 2 | |
| `special_orders` | Customer special orders | Yes | 2 | core.purchasing |
| `consignment` | Sale-or-return stock | Yes | 3 | |
| `recipes` | Production from ingredients (bakery, café) | Yes | 2 | |
| `distribution` | Routes, order booking, load sheets, recovery, schemes | No | 2 | quotations |
| `accounting` | Chart of accounts, journals, cheques, statements | No (plan add-on) | 2 | |
| `staff` | Commission, attendance, salaries, targets | Yes | 2–3 | |
| `online_store` | Online store page, online orders, sync | No (plan) | 3 | |
| `fbr` | FBR POS integration | No (add-on) | 2 | |
| `sms` | SMS receipts and reminders | Yes | 1 | |
| `whatsapp` | WhatsApp links / API | Yes | 2 | |
| `price_levels` | Wholesale and special prices | Yes | 2 | |
| `jewellery` | Weight, karat, gold rate, making charges | No | 3 | serials |
| `optical` | Eye prescriptions, lens orders | No | 3 | repairs |

**Built early (Stage 4b, 2026-11-13):** `repairs`, `installments`, `prescriptions`, `scale_barcodes`, `promotions` (offers and coupons), `loyalty` and `delivery` (deliveries and challans) are built ahead of Phase 2, so every default module of the 10 shop types works. Rules: docs/IMPLEMENTATION-STAGES.md, Stage 4b.

## 3. Defaults per shop type

**D** = on at creation. **O** = offered (owner or super admin switches it on). Blank = not offered (super admin can still force it). Core modules are always on and not listed.

| Module | Mobile | Super store | Medical | Garments | Electronics | Hardware | Cosmetics | Books | Auto parts | General |
|---|---|---|---|---|---|---|---|---|---|---|
| serials | D | O | | | D | O | | | O | O |
| repairs | D | | | | O | | | | | O |
| used_buyback | D | | | | O | | | O | | |
| installments | O | O | | O | D | O | | | O | O |
| warranty | D | | | | D | O | | | O | O |
| variants | O | O | | D | O | O | D | O | O | O |
| batches | O | O | D | | | O | D | O | O | O |
| pack_units | O | D | D | O | O | D | O | O | O | O |
| formula | | | D | | | | | | | |
| prescriptions | | | D | | | | | | | |
| weighed / scale_barcodes | | D | O | | | O | | | | O |
| promotions, loyalty | O | D | O | D | O | O | D | O | O | O |
| multi_till | O | D | D | O | O | O | O | O | O | O |
| quotations | O | O | O | O | D | D | O | D | D | O |
| delivery | | O | O | O | D | D | | O | O | O |
| bundles | O | O | O | O | O | O | O | D | O | O |
| part_numbers | | | | | | | | | D | |
| sms | O | O | O | O | O | O | O | O | O | O |

| Additional type | Default modules |
|---|---|
| Bakery and sweets | weighed, batches, recipes, promotions, multi_till |
| Wholesale and distribution | pack_units, price_levels, quotations, delivery, distribution |
| Grocery wholesale | pack_units, price_levels, weighed, quotations |
| Jewellery | jewellery, serials, installments |
| Furniture | serials, quotations, delivery, installments, layaway |
| Opticals | optical, repairs, variants |
| Agri store | batches, pack_units, price_levels, installments |
| Pet / gift / toy / sports | variants, promotions, batches (pet food: O) |
| Restaurant / café (light) | recipes, multi_till, promotions, delivery |

## 4. Extra product fields per module

Each module adds its own table, not loose JSON, so it can be indexed and reported.

| Module | Table | Fields |
|---|---|---|
| serials | `serial_unit` | serial / imei1, imei2, colour, storage, condition (new, used, box_open), pta_status (approved, non_pta, patched), supplier, cost, status |
| used_buyback | `buyback` | seller name, CNIC, phone, CNIC photo, device photo, price paid, slip number |
| variants | `product_variant` | parent product, attribute values (size, colour, shade), own SKU and barcode |
| batches | `batch` | batch_no, mfg date, expiry date, cost, trade price, retail price |
| pack_units | `product_unit` | unit name, factor to base unit, own barcode, own price |
| formula | `product_pharma` | formula, strength, form, company, schedule (otc, rx, controlled), rack, registration no |
| part_numbers | `product_part` | OEM number, alternate numbers (array), vehicle fit rows |
| books (bundles) | `product_book` | ISBN, author, publisher, edition, class |
| jewellery | `product_jewel` | weight (gram, tola), karat, making charge, stone weight and price |

## 5. Type templates (seed data)

Each shop type ships with default categories, units and an Excel import template:

| Type | Default categories | Default units |
|---|---|---|
| Mobile shop | Smartphones, Feature phones, Tablets, Accessories (covers, chargers, cables, handsfree, protectors), Parts | piece |
| Super store | Grocery, Dairy, Beverages, Snacks, Personal care, Household, Baby care, Frozen, Bakery, Fruits & vegetables | piece, kg, gram, litre, pack, carton, dozen |
| Medical store | Tablets, Capsules, Syrups, Injections, Drops, Ointments, Surgical, Baby care, Supplements | tablet, strip, box, bottle, vial, tube |
| Garments | Men, Women, Kids, Unstitched, Shoes, Accessories | piece, suit, metre, pair |
| Electronics | TV, Refrigerators, Air conditioners, Washing machines, Laptops, Small appliances | piece |
| Hardware | Sanitary, Electrical, Paint, Tools, Pipes, Building material | piece, box, foot, metre, kg, bag, coil |
| Cosmetics | Skin care, Hair care, Makeup, Fragrance | piece |
| Books | Textbooks by class, Stationery, Story books, Exam guides | piece, set, dozen, ream |
| Auto parts | Engine, Brakes, Suspension, Electrical, Body, Oils & lubricants | piece, set, litre |
| General | General | piece |

Templates live in `backend/src/main/resources/shop-types/<type>.json` and are loaded into `platform.shop_type` by a Flyway Java migration. The super admin can edit them later (Phase 2).
