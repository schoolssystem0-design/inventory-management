# Data Model

PostgreSQL. All tables are created by Flyway migrations in `backend/src/main/resources/db/migration`. This is the target model. Column lists show the important columns; every table also has the standard columns below.

## 1. Conventions

| Rule | Detail |
|---|---|
| Standard columns | `id bigint generated always as identity`, `created_at timestamptz default now()`, `created_by bigint`, `updated_at timestamptz`, `version int` (optimistic locking on editable master data) |
| Tenant column | Every shop table has `shop_id bigint not null references shop(id)`, an RLS policy, and `shop_id` as the **first** column of every index |
| Money | `numeric(14,2)` |
| Quantity | `numeric(14,3)` |
| Rates and percentages | `numeric(7,4)` |
| Time | `timestamptz` stored in UTC; business dates are `date` in the shop's time zone (`Asia/Karachi` by default) |
| Text keys | Unique within a shop: `unique (shop_id, sku)`, `unique (shop_id, code)` |
| Enums | `varchar` with a `check` constraint, values in UPPER_CASE (e.g. `TRIAL`, `EXCLUSIVE`, `MM80`); easy to extend by migration |
| Deletion | Master data uses `active boolean`; posted documents are never deleted (`status` and reversal documents instead) |
| Naming | `snake_case`, singular table names (`product`, `sale_line`) |

## 2. Platform (no `shop_id`, no RLS; platform principals only)

| Table | Key columns |
|---|---|
| `shop` | code (subdomain, unique), name, shop_type_id, plan_id, status (`trial`, `active`, `grace`, `read_only`, `suspended`, `closed`), status_reason, trial_ends_on, owner_user_id, city, phone, email, ntn, created_by_platform_user, closed_at, delete_after |
| `shop_domain` | shop_id, host (unique), verified, primary, verification_token |
| `shop_type` | key (`mobile`, `super_store`, `medical`, ...), name, description, icon, default_modules jsonb, template jsonb, active |
| `shop_module` | shop_id, module_key, enabled, source (`type`, `plan`, `override`), changed_by, changed_at. PK (shop_id, module_key) |
| `plan` | key, name, price_month, price_year, setup_fee, limits jsonb (users, branches, products, tills, sms_month, storage_mb), modules_rule, active |
| `subscription` | shop_id, plan_id, cycle (`month`, `year`), price, discount, starts_on, next_due_on, status |
| `subscription_addon` | subscription_id, addon_key, qty, price |
| `subscription_invoice` | shop_id, number, period_from, period_to, amount, due_on, status (`open`, `paid`, `void`) |
| `subscription_payment` | invoice_id, amount, method, reference, received_on, recorded_by |
| `platform_user` | email, name, role (`super_admin`, `support`, `billing`, `onboarding`, `reseller`), password_hash, totp_secret, active, last_login_at |
| `support_session` | platform_user_id, shop_id, started_at, ended_at, write_approved_by, reason |
| `lead` | name, shop_type, city, phone, status, assigned_to, notes, reseller_id |
| `reseller` | name, phone, city, commission_pct, active |
| `platform_audit_log` | platform_user_id, shop_id, action, entity, entity_id, before jsonb, after jsonb, at, ip |
| `announcement` | title, body, target (all / shop_type / shop), starts_at, ends_at |
| `support_ticket`, `support_ticket_message` | shop_id, raised_by, subject, status, priority; messages with attachments |

## 3. Shop setup and identity

| Table | Key columns |
|---|---|
| `shop_settings` | shop_id (PK), business_name, urdu_name, logo_file, address, phones, ntn, strn, tax_mode (`none`, `inclusive`, `exclusive`), tax_rate, allow_negative_stock, receipt_size (`a4`, `80mm`, `58mm`), receipt_language (`en`, `ur`, `both`), receipt_header, receipt_footer, return_policy, return_days, time_zone, closing_time, discount_limit_pct, approval_limits jsonb, session_idle_minutes |
| `settings_change` | shop_id, key, old_value, new_value, changed_by, changed_at |
| `branch` | shop_id, name, code, kind (`shop`, `godown`), address, phone, active |
| `till` | shop_id, branch_id, name, active |
| `numbering` | shop_id, branch_id, doc_type, prefix, next_no. Unique (shop_id, branch_id, doc_type) |
| `payment_method` | shop_id, key (`cash`, `card`, `bank`, `jazzcash`, `easypaisa`, `cheque`, `account`, `voucher`), label, active, account_id |

## 4. Users and access

| Table | Key columns |
|---|---|
| `app_user` | shop_id, username (unique per shop), name, phone, email, role_id, password_hash, must_change_password, pin_hash, totp_secret, active, failed_logins, locked_until, last_login_at |
| `role` | shop_id, name, system_key (`owner`, `manager`, `cashier`, ...), permissions text[] |
| `user_branch` | shop_id, user_id, branch_id |
| `login_event` | shop_id, user_id, username_tried, result (`ok`, `bad_password`, `locked`, `disabled`), ip, user_agent, at |
| `audit_log` | shop_id, user_id, support_session_id, action, entity, entity_id, before jsonb, after jsonb, at |
| `setup_token` | shop_id, user_id, token_hash, purpose (`setup`, `reset`), expires_at, used_at |

## 5. Catalogue

| Table | Key columns |
|---|---|
| `category` | shop_id, parent_id, name, sort, active |
| `brand` | shop_id, name, active |
| `unit` | shop_id, name, decimals_allowed |
| `product` | shop_id, sku, name, urdu_name, category_id, brand_id, base_unit_id, cost_avg, price, wholesale_price, min_price, tax_rate (null = shop default), reorder_level, reorder_qty, image_file, kind (`standard`, `serial`, `batch`, `variant_parent`, `bundle`, `service`), weighed, active. Unique (shop_id, sku) |
| `product_barcode` | shop_id, product_id, unit_id, barcode. Unique (shop_id, barcode) |
| `product_unit` | shop_id, product_id, unit_id, factor, price |
| `product_variant` | shop_id, parent_id, product_id, attributes jsonb (size, colour, shade) |
| `price_level`, `product_price` | named levels; price per product per level |
| `bundle_item` | shop_id, bundle_product_id, item_product_id, qty |
| `product_pharma`, `product_part`, `product_book`, `product_jewel` | module tables (SHOP-TYPES-AND-MODULES.md §4) |
| `price_change` | shop_id, product_id, old_price, new_price, old_cost, new_cost, reason, user_id, at |

## 6. Stock

| Table | Key columns |
|---|---|
| `product_stock` | shop_id, product_id, branch_id, batch_id (nullable), qty, reserved_qty. Unique (shop_id, product_id, branch_id, coalesce(batch_id,0)) |
| `batch` | shop_id, product_id, batch_no, mfg_date, expiry_date, cost, retail_price. Unique (shop_id, product_id, batch_no) |
| `serial_unit` | shop_id, product_id, serial, serial2, attributes jsonb, condition, pta_status, branch_id, status, cost, purchase_doc, sale_doc. Unique (shop_id, serial) where status <> 'void' |
| `stock_movement` | shop_id, product_id, branch_id, batch_id, serial_id, type (`opening`, `purchase`, `sale`, `sale_return`, `purchase_return`, `adjust_in`, `adjust_out`, `count`, `transfer_out`, `transfer_in`, `production_in`, `production_out`, `cancel`), qty (signed), balance_after, unit_cost, doc_type, doc_id, user_id, at |
| `adjustment`, `adjustment_line` | number, branch_id, reason (`damaged`, `expired`, `lost`, `found`, `own_use`, `count`, `opening`, `other`), note, approved_by; lines: product, batch, serial, qty, unit_cost |
| `stock_count`, `stock_count_line` | number, branch_id, scope (category/shelf/all), status (`open`, `counting`, `review`, `posted`); lines: product, batch, system_qty (snapshot), counted_qty |
| `transfer`, `transfer_line` | number, from_branch, to_branch, status (`dispatched`, `received`, `cancelled`), dispatched_by/at, received_by/at; lines with qty_sent, qty_received |
| `stock_alert` | shop_id, kind (`low`, `out`, `expiry`, `dead`), product_id, branch_id, batch_id, created_at, seen_at |

## 7. Sales

| Table | Key columns |
|---|---|
| `till_session` | shop_id, till_id, user_id, opened_at, opening_float, closed_at, counted_cash, expected_cash, difference, note |
| `cash_movement` | shop_id, till_session_id, kind (`in`, `out`), amount, reason, user_id |
| `sale` | shop_id, number, branch_id, till_session_id, customer_id, salesman_id, status (`posted`, `cancelled`), subtotal, line_discount, bill_discount, tax, total, returned, paid, due (generated: total − returned − paid), cost_total, approval_by, fbr_invoice_no, fbr_status, idempotency_key (unique per shop), note, at |
| `sale_line` | shop_id, sale_id, product_id, unit_id, unit_factor, batch_id, serial_id, qty, price, discount, tax, net, unit_cost, qty_returned |
| `sale_payment` | shop_id, sale_id, method_key, amount, reference, tendered, change |
| `sale_return`, `sale_return_line` | number, sale_id (nullable for no-receipt returns), reason, restock (bool per line), refund_method, refund_amount, credit_note_id, approved_by |
| `credit_note` | shop_id, number, customer_id, amount, balance, expires_on |
| `held_sale` | shop_id, branch_id, user_id, label, cart jsonb, created_at |
| `quotation`, `quotation_line` | number, customer, valid_until, status (`open`, `converted`, `expired`), converted_sale_id |

## 8. Customers

| Table | Key columns |
|---|---|
| `customer` | shop_id, code, name, phone, cnic, address, kind (`retail`, `wholesale`, `contractor`, `workshop`), price_level_id, credit_limit, balance, loyalty_points, route_id, active. Unique (shop_id, phone) where phone is not null |
| `customer_ledger` | shop_id, customer_id, doc_type, doc_id, debit, credit, balance_after, at |
| `customer_payment` | shop_id, number, customer_id, amount, method_key, reference, allocations jsonb (invoice → amount), received_by |
| `loyalty_txn` | shop_id, customer_id, sale_id, points, kind (`earn`, `redeem`, `expire`) |

## 9. Purchasing

| Table | Key columns |
|---|---|
| `supplier` | shop_id, code, name, company, phone, ntn, address, terms_days, opening_balance, balance, active |
| `purchase_order`, `po_line` | number, supplier_id, branch_id, status (`ordered`, `partial`, `received`, `closed`, `cancelled`), expected_on; lines: product, qty_ordered, qty_received, unit_cost |
| `grn`, `grn_line` | number, supplier_id, po_id (nullable), branch_id, supplier_bill_no, bill_date, freight, total, paid; lines: product, unit, batch (no, expiry), serials, qty, bonus_qty, unit_cost, discount, landed_cost |
| `purchase_return`, `purchase_return_line` | number, supplier_id, grn_id (nullable), reason, total; lines with batch/serial |
| `supplier_ledger`, `supplier_payment` | same shape as the customer equivalents |

## 10. Cash, expenses and accounting

| Table | Key columns |
|---|---|
| `money_account` | shop_id, name, kind (`cash`, `bank`, `wallet`), branch_id, balance |
| `money_transfer` | shop_id, from_account, to_account, amount, note |
| `expense_category`, `expense` | category, amount, account_id, branch_id, date, note, attachment_file |
| `gl_account`, `journal`, `journal_line` | (accounting module) code, name, type; journal date, source doc; lines debit/credit per account |
| `cheque` | shop_id, direction (`received`, `issued`), party, bank, number, amount, cheque_date, status (`pending`, `cleared`, `bounced`, `cancelled`) |

## 11. Module tables (Phase 2+)

`repair_job`, `repair_part`; `installment_plan`, `installment_due`, `installment_receipt`, `guarantor`; `buyback`; `warranty_claim`; `promotion`, `promotion_rule`, `coupon`, `gift_voucher`; `prescription`, `controlled_register`; `route`, `order_booking`, `load_sheet`, `recovery`; `recipe`, `recipe_line`, `production`; `layaway`; `special_order`; `staff_commission_rule`, `commission`; `notification` (outbox: channel, to, template, payload, status, attempts); `fbr_submission`.

## 12. Rules the data must always keep

| # | Rule | How it is enforced |
|---|---|---|
| D1 | `product_stock.qty` = sum of `stock_movement.qty` for that product, branch and batch | Only `StockService` writes both, in one transaction; nightly `StockAudit` |
| D2 | Stock never goes below zero unless `allow_negative_stock` | `StockService` check after row lock |
| D3 | One IMEI/serial is in stock or sold once at a time | Partial unique index plus row lock |
| D4 | `sale.due` = total − returned − paid ≥ 0 (unless overpaid into credit) | Generated column; check constraint |
| D5 | `customer.balance` = sum of ledger debits − credits | Ledger and balance written together; nightly check |
| D6 | Returned quantity on a line ≤ sold quantity | `check (qty_returned <= qty)` plus service check |
| D7 | Posted documents are never updated in their money or quantity fields | No update endpoints; DB trigger blocks updates to posted `sale`, `grn`, `adjustment` totals |
| D8 | Every row of a shop table belongs to exactly one shop and is invisible to others | `@TenantId` plus RLS plus TenantIsolationIT |
| D9 | Expired batches are not sold | `BatchPicker` and a sale-line check against `batch.expiry_date` |
| D10 | Each sale line stores `unit_cost` at the time of sale | Set by `SalesService` from `cost_avg` (or batch/serial cost) |

## 13. As built in Stage 3 (migration V004)

The tables above are built as listed, with these details that the code relies on:

| Area | Detail |
|---|---|
| Sales | `sale.due` is a generated column (`total − returned − paid`) with a check `≥ 0`. Payments live in `sale_payment` with a `kind`: `SALE` (at the till), `RECEIPT` (a later customer payment allocated to the invoice), `REFUND` and `CANCEL` (negative). `paid` is their sum. Methods include `EXCHANGE` (value of goods returned in an exchange) and `CREDIT_NOTE` |
| Sale lines | `net` = qty × price − line discount − share of the bill discount; `tax` per line; `total` = net + tax when tax is added on top. `unit_cost` and `cost_total` are fixed when sold (D10). A line taken from two batches is stored as two lines |
| Returns | `sale_return.value` = `applied_to_due` + `refund_amount` + `return_charge` (check constraint). `cost_restocked` and `cost_damaged` feed profit and loss |
| Stock | `product_stock` has one row per product, branch and batch; `stock_movement` is insert-only with `balance_after`. Movement types add `BUYBACK`. Stock counts snapshot `system_qty` at the start and keep `counted_at` per line, so sales during the count are accounted for |
| Serials | `serial_unit.status`: `IN_STOCK`, `SOLD`, `IN_TRANSIT`, `DEFECTIVE`, `RETURNED_TO_SUPPLIER`, `WRITTEN_OFF`, `VOID`; unique active serial and second serial (IMEI 2) per shop |
| Purchasing | Quantities on PO and GRN lines are in the line's unit with `unit_factor`; `grn_line.base_qty` and `base_unit_cost` are per base unit after bonus and discounts. `grn.due` is generated like `sale.due` |
| Ledgers | `customer_ledger` (debit = owes more) and `supplier_ledger` (credit = shop owes more) carry `balance_after` and are insert-only. Payments clear the oldest documents first through `customer_payment_allocation` / `supplier_payment_allocation`; the rest is `unallocated` (an advance) |
| Requests | `idempotency_key (shop_id, scope, key, doc_id)` makes `POST /sales`, `/grns`, `/customer-payments`, `/supplier-payments`, `/exchanges` and returns safe to retry |
| Notifications | `notification` is the outbox (`EMAIL`, `SMS`, `IN_APP`) with retries and a `dedupe_key`; `export_job` tracks background exports and backups |
| Billing | `subscription_invoice`, `subscription_payment`, `subscription_reminder` (one row per invoice and reminder kind) are platform tables without RLS. `subscription.pending_plan_id` holds a booked downgrade. `shop.purged_at` marks a closed shop whose data was deleted; `shop.is_demo` marks demo shops |
| Immutability | Trigger `block_posted_changes` refuses changes to the amounts of posted sales, sale lines, GRNs, GRN lines and to adjustments, stock movements and ledger rows (D7) |
| Retention | `purge_shop(id)`, `purge_old_audit(before)` and `purge_demo_shop(id)` are security-definer functions; the shop record, its billing and the platform audit log are kept |
