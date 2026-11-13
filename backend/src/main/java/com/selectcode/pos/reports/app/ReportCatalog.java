package com.selectcode.pos.reports.app;

import com.selectcode.pos.shared.features.ModuleKey;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The Phase 1 reports (requirements §13, RPT-02). Each report is one SQL query over the current shop (row-level
 * security keeps other shops out), filtered by period and branch, with typed columns. Columns marked {@code cost} are
 * removed for users without {@code cost.view}; reports that are about profit or cost are hidden from them entirely
 * (RPT-04). Module reports appear only when the module is on.
 */
final class ReportCatalog {

	/** @param kind TEXT, DATE, INT, QTY, MONEY, PERCENT; {@code total} = summed in the totals row */
	record Col(String key, String label, String kind, boolean cost, boolean total) {
	}

	/** A report's SQL and its arguments for one run. */
	record Sql(String sql, List<Object> args) {
	}

	/** Filters of one run. {@link #branch(String)} limits a column to the chosen (and allowed) branches. */
	record Ctx(LocalDate from, LocalDate to, Long branchId, String branchFilterSql, Map<String, String> extra) {

		Date fromDate() {
			return Date.valueOf(from);
		}

		Date toDate() {
			return Date.valueOf(to);
		}

		Timestamp fromTs() {
			return Timestamp.from(from.atStartOfDay(ZONE).toInstant());
		}

		Timestamp toTs() {
			return Timestamp.from(to.plusDays(1).atStartOfDay(ZONE).toInstant());
		}

		String branch(String column) {
			return branchFilterSql.replace("$col", column);
		}

		/** A whole-number filter, kept between 1 and 5,000 (rows) or 3,650 (days) so a report cannot ask for millions. */
		int intParam(String name, int fallback) {
			int max = "days".equals(name) ? 3650 : 5000;
			try {
				int v = extra.containsKey(name) ? Integer.parseInt(extra.get(name).trim()) : fallback;
				return Math.max(1, Math.min(v, max));
			}
			catch (NumberFormatException e) {
				return fallback;
			}
		}

		Long longParam(String name) {
			try {
				return extra.containsKey(name) && !extra.get(name).isBlank() ? Long.valueOf(extra.get(name).trim())
						: null;
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
	}

	record Def(String key, String group, String title, String description, ModuleKey module, boolean costOnly,
			boolean pii, List<Col> columns, List<String> params, Function<Ctx, Sql> query) {
	}

	static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	static final Map<String, Def> REPORTS = new LinkedHashMap<>();

	private ReportCatalog() {
	}

	// ------------------------------------------------------------------ helpers

	static Col text(String key, String label) {
		return new Col(key, label, "TEXT", false, false);
	}

	static Col date(String key, String label) {
		return new Col(key, label, "DATE", false, false);
	}

	static Col count(String key, String label) {
		return new Col(key, label, "INT", false, true);
	}

	static Col qty(String key, String label) {
		return new Col(key, label, "QTY", false, true);
	}

	static Col money(String key, String label) {
		return new Col(key, label, "MONEY", false, true);
	}

	static Col cost(String key, String label) {
		return new Col(key, label, "MONEY", true, true);
	}

	static Col percent(String key, String label, boolean cost) {
		return new Col(key, label, "PERCENT", cost, false);
	}

	private static void add(String key, String group, String title, String description, ModuleKey module,
			boolean costOnly, List<Col> cols, List<String> params, Function<Ctx, Sql> query) {
		REPORTS.put(key, new Def(key, group, title, description, module, costOnly, false, cols, params, query));
	}

	private static void addPii(String key, String group, String title, String description, ModuleKey module,
			List<Col> cols, Function<Ctx, Sql> query) {
		REPORTS.put(key, new Def(key, group, title, description, module, false, true, cols, List.of(), query));
	}

	private static Sql sql(String sql, Object... args) {
		return new Sql(sql, new ArrayList<>(List.of(args)));
	}

	/** Posted sales of the period (and branch). */
	private static String sales(Ctx c) {
		return " s.status = 'POSTED' and s.business_date between ? and ?" + c.branch("s.branch_id");
	}

	/** Revenue of a line without sales tax and net of returned quantity. */
	private static final String LINE_REVENUE = "(l.total - l.tax) * (l.qty - l.qty_returned) / l.qty";

	private static final String LINE_COST = "l.cost_total * (l.qty - l.qty_returned) / l.qty";

	private static final List<Col> SALES_COLS = List.of(count("invoices", "Invoices"), money("gross", "Gross sales"),
			money("discounts", "Discounts"), money("tax", "Tax"), money("total", "Total"), money("returned", "Returned"),
			cost("cost", "Cost"), cost("profit", "Profit"));

	private static final String SALES_SUMS = """
			count(*) as invoices, sum(s.subtotal) as gross, sum(s.line_discount + s.bill_discount) as discounts,
			sum(s.tax) as tax, sum(s.total) as total, sum(s.returned) as returned, sum(s.cost_total) as cost,
			sum(s.total - s.tax - s.cost_total) as profit""";

	private static List<Col> withFirst(Col first, List<Col> rest) {
		List<Col> all = new ArrayList<>();
		all.add(first);
		all.addAll(rest);
		return all;
	}

	static {
		// ------------------------------------------------------------ sales
		add("sales-daily", "Sales", "Daily sales summary", "Invoices, discounts, tax and totals per day", null, false,
				withFirst(date("date", "Date"), SALES_COLS), List.of(),
				c -> sql("select s.business_date as date, " + SALES_SUMS + " from sale s where" + sales(c)
						+ " group by s.business_date order by s.business_date", c.fromDate(), c.toDate()));
		add("sales-by-hour", "Sales", "Sales by hour", "Busy hours of the day", null, false,
				List.of(text("hour", "Hour"), count("invoices", "Invoices"), money("total", "Total")), List.of(),
				c -> sql("""
						select lpad(extract(hour from s.at at time zone 'Asia/Karachi')::int::text, 2, '0') || ':00' as hour,
						       count(*) as invoices, sum(s.total) as total from sale s where""" + sales(c)
						+ " group by 1 order by 1", c.fromDate(), c.toDate()));
		add("sales-by-month", "Sales", "Sales by month", "Monthly totals", null, false,
				withFirst(text("month", "Month"), SALES_COLS), List.of(),
				c -> sql("select to_char(s.business_date, 'YYYY-MM') as month, " + SALES_SUMS + " from sale s where"
						+ sales(c) + " group by 1 order by 1", c.fromDate(), c.toDate()));
		add("sales-by-branch", "Sales", "Sales by branch", "Consolidated branch sales (BRN-03)", null, false,
				withFirst(text("branch", "Branch"), SALES_COLS), List.of(),
				c -> sql("select b.name as branch, " + SALES_SUMS + " from sale s join branch b on b.id = s.branch_id where"
						+ sales(c) + " group by b.name order by sum(s.total) desc", c.fromDate(), c.toDate()));
		add("sales-by-till", "Sales", "Sales by till", "Each till's takings", null, false,
				withFirst(text("till", "Till"), SALES_COLS), List.of(),
				c -> sql("""
						select b.name || ' · ' || t.name as till, """ + SALES_SUMS + """
						 from sale s join till_session ts on ts.id = s.till_session_id join till t on t.id = ts.till_id
						join branch b on b.id = s.branch_id where""" + sales(c) + " group by 1 order by 1", c.fromDate(),
						c.toDate()));
		add("sales-by-cashier", "Sales", "Sales by cashier", "What each cashier sold", null, false,
				withFirst(text("cashier", "Cashier"), SALES_COLS), List.of(),
				c -> sql("select coalesce(s.actor_name, '—') as cashier, " + SALES_SUMS + " from sale s where" + sales(c)
						+ " group by 1 order by sum(s.total) desc", c.fromDate(), c.toDate()));
		add("sales-by-salesman", "Sales", "Sales by salesman", "Bills credited to floor salesmen", null, false,
				withFirst(text("salesman", "Salesman"), SALES_COLS), List.of(),
				c -> sql("select coalesce(u.display_name, 'No salesman') as salesman, " + SALES_SUMS
						+ " from sale s left join app_user u on u.id = s.salesman_id where" + sales(c)
						+ " group by 1 order by sum(s.total) desc", c.fromDate(), c.toDate()));
		add("sales-by-customer", "Sales", "Sales by customer", "Customers' purchases", null, false,
				List.of(text("customer", "Customer"), count("invoices", "Invoices"), money("total", "Total"),
						money("due", "Still owed"), cost("profit", "Profit")),
				List.of(), c -> sql("""
						select coalesce(cu.name, 'Walk-in') as customer, count(*) as invoices, sum(s.total) as total,
						       sum(s.due) as due, sum(s.total - s.tax - s.cost_total) as profit
						from sale s left join customer cu on cu.id = s.customer_id where""" + sales(c)
						+ " group by 1 order by sum(s.total) desc", c.fromDate(), c.toDate()));
		add("sales-by-method", "Sales", "Sales by payment method", "Cash, card, wallets, credit notes (net of refunds)",
				null, false, List.of(text("method", "Method"), count("payments", "Payments"), money("amount", "Amount")),
				List.of(), c -> sql("""
						select p.method_key as method, count(*) as payments, sum(p.amount) as amount from sale_payment p
						where p.business_date between ? and ?""" + c.branch("p.branch_id")
						+ " group by 1 order by sum(p.amount) desc", c.fromDate(), c.toDate()));
		add("invoice-register", "Sales", "Invoice register", "Every invoice of the period", null, false,
				List.of(text("number", "Invoice"), date("date", "Date"), text("customer", "Customer"),
						text("cashier", "Cashier"), text("status", "Status"), money("total", "Total"),
						money("paid", "Paid"), money("due", "Due")),
				List.of(), c -> sql("""
						select s.number, s.business_date as date, coalesce(s.customer_name, 'Walk-in') as customer,
						       s.actor_name as cashier, s.status, s.total, s.paid, s.due from sale s
						where s.business_date between ? and ?""" + c.branch("s.branch_id") + " order by s.id",
						c.fromDate(), c.toDate()));
		add("discounts-given", "Sales", "Discounts given", "Invoices with discounts and who approved them", null, false,
				List.of(text("number", "Invoice"), date("date", "Date"), text("cashier", "Cashier"),
						money("subtotal", "Before discount"), money("discount", "Discount"),
						percent("pct", "Discount %", false), text("approval", "Approval")),
				List.of(), c -> sql("""
						select s.number, s.business_date as date, s.actor_name as cashier, s.subtotal,
						       s.line_discount + s.bill_discount as discount,
						       round((s.line_discount + s.bill_discount) * 100 / nullif(s.subtotal, 0), 2) as pct,
						       coalesce(u.display_name || ': ' || s.approval_note, s.approval_note) as approval
						from sale s left join app_user u on u.id = s.approved_by
						where s.line_discount + s.bill_discount > 0 and""" + sales(c) + " order by s.id",
						c.fromDate(), c.toDate()));
		add("cancelled-invoices", "Sales", "Cancelled invoices", "Invoices cancelled with their reason", null, false,
				List.of(text("number", "Invoice"), date("date", "Date"), text("cashier", "Cashier"),
						money("total", "Total"), text("reason", "Reason"), text("by", "Cancelled by")),
				List.of(), c -> sql("""
						select s.number, s.business_date as date, s.actor_name as cashier, s.total, s.cancel_reason as reason,
						       u.display_name as by from sale s left join app_user u on u.id = s.cancelled_by
						where s.status = 'CANCELLED' and s.business_date between ? and ?""" + c.branch("s.branch_id")
						+ " order by s.id", c.fromDate(), c.toDate()));
		add("held-bills", "Sales", "Held bills left open", "Bills parked at the till and not resumed", null, false,
				List.of(text("label", "Bill"), text("branch", "Branch"), text("by", "Held by"), text("at", "Held at")),
				List.of(), c -> sql("""
						select h.label, b.name as branch, h.actor_name as by,
						       to_char(h.created_at at time zone 'Asia/Karachi', 'DD Mon YYYY HH24:MI') as at
						from held_sale h join branch b on b.id = h.branch_id where 1=1""" + c.branch("h.branch_id")
						+ " order by h.created_at"));
		add("sales-per-till-hour", "Sales", "Sales per till per hour", "Super store rush hours by till",
				ModuleKey.MULTI_TILL, false,
				List.of(text("till", "Till"), text("hour", "Hour"), count("invoices", "Invoices"), money("total", "Total")),
				List.of(), c -> sql("""
						select t.name as till, lpad(extract(hour from s.at at time zone 'Asia/Karachi')::int::text, 2, '0') || ':00' as hour,
						       count(*) as invoices, sum(s.total) as total
						from sale s join till_session ts on ts.id = s.till_session_id join till t on t.id = ts.till_id
						where""" + sales(c) + " group by 1, 2 order by 1, 2", c.fromDate(), c.toDate()));
		add("weighed-sales", "Sales", "Weighed item sales", "Loose items sold by weight", ModuleKey.WEIGHED, false,
				List.of(text("product", "Item"), qty("qty", "Qty sold"), money("amount", "Amount")), List.of(),
				c -> sql("""
						select p.name as product, sum(l.base_qty) as qty, sum(l.total) as amount
						from sale_line l join sale s on s.id = l.sale_id join product p on p.id = l.product_id
						where p.weighed and""" + sales(c) + " group by p.name order by sum(l.total) desc", c.fromDate(),
						c.toDate()));

		// ------------------------------------------------------------ profit
		List<Col> profitCols = List.of(qty("qty", "Qty"), money("revenue", "Sales (no tax)"), cost("cost", "Cost"),
				cost("profit", "Profit"), percent("margin", "Margin %", true));
		String profitSums = "sum(" + LINE_REVENUE + ") as revenue, sum(" + LINE_COST + ") as cost, sum(" + LINE_REVENUE
				+ " - " + LINE_COST + ") as profit, round(sum(" + LINE_REVENUE + " - " + LINE_COST + ") * 100 / nullif(sum("
				+ LINE_REVENUE + "), 0), 2) as margin";
		add("profit-by-invoice", "Profit", "Gross profit by invoice", "Profit of each invoice after returns", null,
				true,
				List.of(text("number", "Invoice"), date("date", "Date"), money("revenue", "Sales (no tax)"),
						cost("cost", "Cost"), cost("profit", "Profit"), percent("margin", "Margin %", true)),
				List.of(), c -> sql("select s.number, s.business_date as date, " + profitSums
						+ " from sale_line l join sale s on s.id = l.sale_id where" + sales(c)
						+ " group by s.id, s.number, s.business_date order by s.id", c.fromDate(), c.toDate()));
		for (String[] by : new String[][] { { "product", "Product", "p.name", "" },
				{ "category", "Category", "coalesce(cat.name, 'No category')", "left join category cat on cat.id = p.category_id" },
				{ "brand", "Brand", "coalesce(br.name, 'No brand')", "left join brand br on br.id = p.brand_id" },
				{ "branch", "Branch", "b.name", "join branch b on b.id = s.branch_id" },
				{ "cashier", "Cashier", "coalesce(s.actor_name, '—')", "" } }) {
			add("profit-by-" + by[0], "Profit", "Gross profit by " + by[1].toLowerCase(), "After returns, without tax",
					null, true, withFirst(text("name", by[1]), profitCols), List.of(),
					c -> sql("select " + by[2] + " as name, sum(l.base_qty * (l.qty - l.qty_returned) / l.qty) as qty, "
							+ profitSums + " from sale_line l join sale s on s.id = l.sale_id join product p on p.id = l.product_id "
							+ by[3] + " where" + sales(c) + " group by 1 order by sum(" + LINE_REVENUE + " - " + LINE_COST
							+ ") desc", c.fromDate(), c.toDate()));
		}

		// ------------------------------------------------------------ products
		add("best-sellers", "Products", "Best sellers", "Top items by sales amount", null, false,
				List.of(text("product", "Item"), text("sku", "SKU"), qty("qty", "Qty sold"), money("amount", "Amount"),
						cost("profit", "Profit")),
				List.of("limit"), c -> sql("""
						select p.name as product, p.sku, sum(l.base_qty) as qty, sum(l.total) as amount,
						       sum(""" + LINE_REVENUE + " - " + LINE_COST + """
						) as profit from sale_line l join sale s on s.id = l.sale_id join product p on p.id = l.product_id
						where""" + sales(c) + " group by p.name, p.sku order by sum(l.total) desc limit ?", c.fromDate(),
						c.toDate(), c.intParam("limit", 50)));
		add("slow-movers", "Products", "Slow movers", "Items in stock that sold least in the period", null, false,
				List.of(text("product", "Item"), qty("stock", "In stock"), qty("sold", "Sold in period"),
						cost("value", "Stock value")),
				List.of("limit"), c -> sql("""
						select p.name as product, st.qty as stock, coalesce(sold.qty, 0) as sold, st.qty * p.cost_avg as value
						from product p
						join (select product_id, sum(qty) as qty from product_stock s where 1=1""" + c.branch("s.branch_id") + """
						      group by product_id having sum(qty) > 0) st on st.product_id = p.id
						left join (select l.product_id, sum(l.base_qty) as qty from sale_line l join sale s on s.id = l.sale_id
						           where""" + sales(c) + """
						           group by l.product_id) sold on sold.product_id = p.id
						where p.active order by coalesce(sold.qty, 0), st.qty * p.cost_avg desc limit ?""", c.fromDate(),
						c.toDate(), c.intParam("limit", 100)));
		add("dead-stock", "Products", "Dead stock", "In stock but not sold in the last 30, 60 or 90 days", null, false,
				List.of(text("product", "Item"), qty("stock", "In stock"), cost("value", "Value at cost"),
						money("retail", "Value at price"), text("lastSold", "Last sold")),
				List.of("days"), c -> sql("""
						select p.name as product, st.qty as stock, st.qty * p.cost_avg as value, st.qty * p.price as retail,
						       coalesce(to_char((select max(m.at) from stock_movement m where m.product_id = p.id
						                and m.move_type = 'SALE') at time zone 'Asia/Karachi', 'DD Mon YYYY'), 'Never') as "lastSold"
						from product p join (select product_id, sum(qty) as qty from product_stock s where 1=1"""
						+ c.branch("s.branch_id") + """
						      group by product_id having sum(qty) > 0) st on st.product_id = p.id
						where p.active and p.created_at < ? and not exists (select 1 from stock_movement m where m.product_id = p.id
						      and m.move_type = 'SALE' and m.at >= ?)
						order by st.qty * p.cost_avg desc""", Timestamp.from(LocalDate.now(ZONE)
							.minusDays(c.intParam("days", 90)).atStartOfDay(ZONE).toInstant()),
						Timestamp.from(LocalDate.now(ZONE).minusDays(c.intParam("days", 90)).atStartOfDay(ZONE).toInstant())));
		add("price-changes", "Products", "Price change history", "Every price (and cost) change with who and why",
				null, false,
				List.of(text("at", "When"), text("product", "Item"), money("oldPrice", "Old price"),
						money("newPrice", "New price"), cost("oldCost", "Old cost"), cost("newCost", "New cost"),
						text("reason", "Reason"), text("by", "By")),
				List.of(), c -> sql("""
						select to_char(pc.at at time zone 'Asia/Karachi', 'DD Mon YYYY HH24:MI') as at, p.name as product,
						       pc.old_price as "oldPrice", pc.new_price as "newPrice", pc.old_cost as "oldCost",
						       pc.new_cost as "newCost", pc.reason, pc.actor_name as by
						from price_change pc join product p on p.id = pc.product_id where pc.at >= ? and pc.at < ?
						order by pc.at""", c.fromTs(), c.toTs()));
		add("products-incomplete", "Products", "Products missing details", "Without barcode, cost or category", null,
				false, List.of(text("product", "Item"), text("sku", "SKU"), text("missing", "Missing")), List.of(),
				c -> sql("""
						select p.name as product, p.sku, concat_ws(', ',
						         case when not exists (select 1 from product_barcode b where b.product_id = p.id) then 'barcode' end,
						         case when p.cost_avg = 0 then 'cost' end, case when p.category_id is null then 'category' end) as missing
						from product p where p.active and p.kind <> 'VARIANT_PARENT' and (p.cost_avg = 0 or p.category_id is null
						      or not exists (select 1 from product_barcode b where b.product_id = p.id))
						order by p.name"""));

		// ------------------------------------------------------------ stock
		add("stock-on-hand", "Stock", "Stock on hand", "By branch, with value at cost and at retail price", null,
				false,
				List.of(text("product", "Item"), text("category", "Category"), text("brand", "Brand"),
						text("supplier", "Supplier"), qty("qty", "Qty"), cost("costValue", "Value at cost"),
						money("retailValue", "Value at price")),
				List.of("categoryId", "brandId", "supplierId"), c -> {
					Sql q = sql("""
							select p.name as product, cat.name as category, br.name as brand, sp.name as supplier, st.qty,
							       st.qty * p.cost_avg as "costValue", st.qty * p.price as "retailValue"
							from product p left join category cat on cat.id = p.category_id left join brand br on br.id = p.brand_id
							left join supplier sp on sp.id = p.supplier_id
							join (select product_id, sum(qty) as qty from product_stock s where 1=1""" + c.branch("s.branch_id")
							+ """
							      group by product_id) st on st.product_id = p.id
							where p.active and st.qty <> 0""");
					return filtered(q, c, " order by p.name");
				});
		add("stock-by-branch", "Stock", "Stock by branch", "Consolidated stock value per branch (BRN-03)", null, false,
				List.of(text("branch", "Branch"), count("items", "Items"), qty("qty", "Qty"),
						cost("costValue", "Value at cost"), money("retailValue", "Value at price")),
				List.of(), c -> sql("""
						select b.name as branch, count(distinct s.product_id) filter (where s.qty > 0) as items, sum(s.qty) as qty,
						       sum(s.qty * p.cost_avg) as "costValue", sum(s.qty * p.price) as "retailValue"
						from product_stock s join product p on p.id = s.product_id join branch b on b.id = s.branch_id
						where 1=1""" + c.branch("s.branch_id") + " group by b.name order by b.name"));
		add("stock-value-by-category", "Stock", "Stock value by category", "Cost and retail value per category", null,
				false,
				List.of(text("category", "Category"), qty("qty", "Qty"), cost("costValue", "Value at cost"),
						money("retailValue", "Value at price")),
				List.of(), c -> sql("""
						select coalesce(cat.name, 'No category') as category, sum(s.qty) as qty,
						       sum(s.qty * p.cost_avg) as "costValue", sum(s.qty * p.price) as "retailValue"
						from product_stock s join product p on p.id = s.product_id left join category cat on cat.id = p.category_id
						where 1=1""" + c.branch("s.branch_id") + " group by 1 order by sum(s.qty * p.price) desc"));
		add("stock-movement-summary", "Stock", "Stock movement summary", "Opening, in, out and closing per item", null,
				false,
				List.of(text("product", "Item"), qty("opening", "Opening"), qty("qtyIn", "In"), qty("qtyOut", "Out"),
						qty("closing", "Closing")),
				List.of(), c -> sql("""
						select p.name as product,
						       coalesce(sum(m.qty) filter (where m.at < ?), 0) as opening,
						       coalesce(sum(m.qty) filter (where m.at >= ? and m.at < ? and m.qty > 0), 0) as "qtyIn",
						       coalesce(-sum(m.qty) filter (where m.at >= ? and m.at < ? and m.qty < 0), 0) as "qtyOut",
						       coalesce(sum(m.qty) filter (where m.at < ?), 0) as closing
						from stock_movement m join product p on p.id = m.product_id where m.at < ?""" + c.branch("m.branch_id")
						+ " group by p.name having bool_or(m.at >= ?) order by p.name", c.fromTs(), c.fromTs(), c.toTs(),
						c.fromTs(), c.toTs(), c.toTs(), c.toTs(), c.fromTs()));
		add("negative-stock", "Stock", "Negative stock", "Items sold beyond what the system had", null, false,
				List.of(text("product", "Item"), text("branch", "Branch"), qty("qty", "Qty")), List.of(),
				c -> sql("""
						select p.name as product, b.name as branch, s.qty from product_stock s join product p on p.id = s.product_id
						join branch b on b.id = s.branch_id where s.qty < 0""" + c.branch("s.branch_id") + " order by s.qty"));
		add("reorder-list", "Stock", "Reorder list", "At or below reorder level", null, false,
				List.of(text("product", "Item"), text("supplier", "Supplier"), qty("onHand", "In stock"),
						qty("reorderLevel", "Reorder level")),
				List.of(), c -> sql("""
						select p.name as product, sp.name as supplier, coalesce(st.qty, 0) as "onHand", p.reorder_level as "reorderLevel"
						from product p left join supplier sp on sp.id = p.supplier_id
						left join (select product_id, sum(qty) as qty from product_stock s where 1=1""" + c.branch("s.branch_id")
						+ """
						           group by product_id) st on st.product_id = p.id
						where p.active and p.reorder_level > 0 and coalesce(st.qty, 0) <= p.reorder_level order by sp.name, p.name"""));
		add("transfers-in-transit", "Stock", "Transfers in transit", "Dispatched and not yet received", null, false,
				List.of(text("number", "Transfer"), text("from", "From"), text("to", "To"), text("product", "Item"),
						qty("qty", "Qty"), text("dispatched", "Dispatched")),
				List.of(), c -> sql("""
						select t.number, f.name as "from", d.name as "to", p.name as product, l.qty_sent as qty,
						       to_char(t.dispatched_at at time zone 'Asia/Karachi', 'DD Mon YYYY') as dispatched
						from transfer t join transfer_line l on l.transfer_id = t.id join product p on p.id = l.product_id
						join branch f on f.id = t.from_branch_id join branch d on d.id = t.to_branch_id
						where t.status = 'DISPATCHED'""" + c.branch("t.to_branch_id") + " order by t.dispatched_at"));

		// ------------------------------------------------------------ adjustments and counts
		add("adjustments-by-reason", "Adjustments", "Adjustments by reason and user", "Stock written off or added",
				null, false,
				List.of(text("reason", "Reason"), text("user", "User"), count("documents", "Documents"),
						cost("valueOut", "Written off"), cost("valueIn", "Added")),
				List.of(), c -> sql("""
						select a.reason, coalesce(a.actor_name, '—') as user, count(*) as documents, sum(a.value_out) as "valueOut",
						       sum(a.value_in) as "valueIn" from adjustment a where a.reason <> 'OPENING'
						and a.business_date between ? and ?""" + c.branch("a.branch_id") + " group by 1, 2 order by 1, 2",
						c.fromDate(), c.toDate()));
		add("count-differences", "Adjustments", "Stock count differences", "What each posted count changed", null,
				false,
				List.of(text("count", "Count"), text("product", "Item"), qty("difference", "Difference"),
						cost("value", "Value")),
				List.of(), c -> sql("""
						select m.doc_number as count, p.name as product, m.qty as difference, m.qty * m.unit_cost as value
						from stock_movement m join product p on p.id = m.product_id where m.move_type = 'COUNT'
						and m.at >= ? and m.at < ?""" + c.branch("m.branch_id") + " order by m.at", c.fromTs(), c.toTs()));
		add("shrinkage-by-category", "Adjustments", "Shrinkage by category", "Stock lost as a share of stock value",
				null, true,
				List.of(text("category", "Category"), cost("lost", "Lost (cost)"), cost("stockValue", "Stock value"),
						percent("pct", "Shrinkage %", true)),
				List.of(), c -> sql("""
						select coalesce(cat.name, 'No category') as category, coalesce(sum(-m.qty * m.unit_cost), 0) as lost,
						       (select sum(s.qty * p2.cost_avg) from product_stock s join product p2 on p2.id = s.product_id
						        where coalesce(p2.category_id, 0) = coalesce(cat.id, 0)) as "stockValue",
						       round(coalesce(sum(-m.qty * m.unit_cost), 0) * 100 / nullif((select sum(s.qty * p2.cost_avg)
						        from product_stock s join product p2 on p2.id = s.product_id
						        where coalesce(p2.category_id, 0) = coalesce(cat.id, 0)), 0), 2) as pct
						from stock_movement m join product p on p.id = m.product_id left join category cat on cat.id = p.category_id
						where m.qty < 0 and m.move_type in ('ADJUST_OUT', 'COUNT') and m.at >= ? and m.at < ?"""
						+ c.branch("m.branch_id") + " group by cat.id, cat.name order by 2 desc", c.fromTs(), c.toTs()));

		// ------------------------------------------------------------ returns
		add("returns-by-reason", "Returns", "Sales returns by reason", "Why customers bring things back", null, false,
				List.of(text("reason", "Reason"), count("returns", "Returns"), money("value", "Value"),
						money("refunded", "Refunded")),
				List.of(), c -> sql("""
						select r.reason, count(*) as returns, sum(r.value) as value, sum(r.refund_amount) as refunded
						from sale_return r where r.business_date between ? and ?""" + c.branch("r.branch_id")
						+ " group by 1 order by sum(r.value) desc", c.fromDate(), c.toDate()));
		add("returns-by-product", "Returns", "Sales returns by product", "Items returned most", null, false,
				List.of(text("product", "Item"), qty("qty", "Qty"), money("value", "Value"), qty("damaged", "Damaged")),
				List.of(), c -> sql("""
						select p.name as product, sum(rl.base_qty) as qty, sum(rl.value) as value,
						       sum(rl.base_qty) filter (where not rl.restock) as damaged
						from sale_return_line rl join sale_return r on r.id = rl.return_id join product p on p.id = rl.product_id
						where r.business_date between ? and ?""" + c.branch("r.branch_id")
						+ " group by 1 order by sum(rl.value) desc", c.fromDate(), c.toDate()));
		add("returns-by-cashier", "Returns", "Sales returns by cashier", "Returns taken by each user", null, false,
				List.of(text("cashier", "Cashier"), count("returns", "Returns"), money("value", "Value")), List.of(),
				c -> sql("""
						select coalesce(r.actor_name, '—') as cashier, count(*) as returns, sum(r.value) as value
						from sale_return r where r.business_date between ? and ?""" + c.branch("r.branch_id")
						+ " group by 1 order by 3 desc", c.fromDate(), c.toDate()));
		add("purchase-returns-by-supplier", "Returns", "Purchase returns by supplier", "Goods sent back to suppliers",
				null, false,
				List.of(text("supplier", "Supplier"), count("returns", "Returns"), cost("value", "Value")), List.of(),
				c -> sql("""
						select s.name as supplier, count(*) as returns, sum(r.total) as value from purchase_return r
						join supplier s on s.id = r.supplier_id where r.business_date between ? and ?"""
						+ c.branch("r.branch_id") + " group by 1 order by 3 desc", c.fromDate(), c.toDate()));

		// ------------------------------------------------------------ purchases
		add("purchases-by-supplier", "Purchases", "Purchases by supplier", "Bills received, paid and owed", null, true,
				List.of(text("supplier", "Supplier"), count("receipts", "Receipts"), cost("total", "Total"),
						cost("paid", "Paid"), cost("due", "Owed")),
				List.of(), c -> sql("""
						select s.name as supplier, count(*) as receipts, sum(g.total) as total, sum(g.paid) as paid,
						       sum(g.due) as due from grn g join supplier s on s.id = g.supplier_id
						where g.business_date between ? and ?""" + c.branch("g.branch_id") + " group by 1 order by 3 desc",
						c.fromDate(), c.toDate()));
		add("purchases-by-product", "Purchases", "Purchases by product", "Quantities and cost bought", null, true,
				List.of(text("product", "Item"), qty("qty", "Qty (base units)"), qty("bonus", "Bonus"),
						cost("value", "Cost")),
				List.of(), c -> sql("""
						select p.name as product, sum(l.base_qty) as qty, sum(l.bonus_qty * l.unit_factor) as bonus, sum(l.net) as value
						from grn_line l join grn g on g.id = l.grn_id join product p on p.id = l.product_id
						where g.business_date between ? and ?""" + c.branch("g.branch_id") + " group by 1 order by 4 desc",
						c.fromDate(), c.toDate()));
		add("purchases-by-month", "Purchases", "Purchases by month", "Monthly purchase totals", null, true,
				List.of(text("month", "Month"), count("receipts", "Receipts"), cost("total", "Total")), List.of(),
				c -> sql("""
						select to_char(g.business_date, 'YYYY-MM') as month, count(*) as receipts, sum(g.total) as total
						from grn g where g.business_date between ? and ?""" + c.branch("g.branch_id")
						+ " group by 1 order by 1", c.fromDate(), c.toDate()));
		add("open-purchase-orders", "Purchases", "Open purchase orders", "Ordered or partly received", null, false,
				List.of(text("number", "Order"), text("supplier", "Supplier"), date("date", "Date"),
						date("expected", "Expected"), text("status", "Status"), qty("open", "Qty still to come")),
				List.of(), c -> sql("""
						select o.number, s.name as supplier, o.business_date as date, o.expected_on as expected, o.status,
						       (select sum(greatest(l.qty_ordered - l.qty_received, 0) * l.unit_factor) from po_line l where l.po_id = o.id) as open
						from purchase_order o join supplier s on s.id = o.supplier_id where o.status in ('ORDERED', 'PARTIAL')"""
						+ c.branch("o.branch_id") + " order by o.business_date"));
		add("short-received", "Purchases", "Short-received items", "Ordered but not delivered in full", null, false,
				List.of(text("order", "Order"), text("supplier", "Supplier"), text("product", "Item"),
						qty("ordered", "Ordered"), qty("received", "Received"), qty("short", "Short")),
				List.of(), c -> sql("""
						select o.number as order, s.name as supplier, p.name as product, l.qty_ordered as ordered,
						       l.qty_received as received, l.qty_ordered - l.qty_received as short
						from po_line l join purchase_order o on o.id = l.po_id join supplier s on s.id = o.supplier_id
						join product p on p.id = l.product_id where o.status in ('PARTIAL', 'CLOSED') and l.qty_received < l.qty_ordered
						and o.business_date between ? and ?""" + c.branch("o.branch_id") + " order by o.number", c.fromDate(),
						c.toDate()));
		add("bonus-received", "Purchases", "Bonus received", "Free items received with purchases (10+1)", null, false,
				List.of(text("grn", "Receipt"), text("supplier", "Supplier"), text("product", "Item"), qty("qty", "Billed"),
						qty("bonus", "Bonus")),
				List.of(), c -> sql("""
						select g.number as grn, s.name as supplier, p.name as product, l.qty, l.bonus_qty as bonus
						from grn_line l join grn g on g.id = l.grn_id join supplier s on s.id = g.supplier_id
						join product p on p.id = l.product_id where l.bonus_qty > 0 and g.business_date between ? and ?"""
						+ c.branch("g.branch_id") + " order by g.id", c.fromDate(), c.toDate()));

		// ------------------------------------------------------------ customers and suppliers
		String ageing = """
				sum(x.due) as total,
				sum(x.due) filter (where x.age <= 30) as d30, sum(x.due) filter (where x.age between 31 and 60) as d60,
				sum(x.due) filter (where x.age between 61 and 90) as d90, sum(x.due) filter (where x.age > 90) as d90plus""";
		List<Col> ageCols = List.of(money("total", "Owed"), money("d30", "0–30 days"), money("d60", "31–60 days"),
				money("d90", "61–90 days"), money("d90plus", "Over 90 days"));
		add("receivables-ageing", "Customers", "Receivables ageing", "What customers owe, by age of invoice", null,
				false, withFirst(text("customer", "Customer"), ageCols), List.of(), c -> sql("select x.name as customer, "
						+ ageing + " " + """
						 from (select cu.name, s.due, current_date - s.business_date as age from sale s join customer cu on cu.id = s.customer_id
						       where s.status = 'POSTED' and s.due > 0""" + c.branch("s.branch_id") + """
						      ) x group by x.name order by sum(x.due) desc"""));
		add("top-customers", "Customers", "Top customers", "Customers by purchases in the period", null, false,
				List.of(text("customer", "Customer"), text("phone", "Phone"), count("invoices", "Invoices"),
						money("total", "Bought"), money("balance", "Balance now")),
				List.of("limit"), c -> sql("""
						select cu.name as customer, cu.phone, count(*) as invoices, sum(s.total) as total, cu.balance
						from sale s join customer cu on cu.id = s.customer_id where""" + sales(c)
						+ " group by cu.id, cu.name, cu.phone, cu.balance order by sum(s.total) desc limit ?", c.fromDate(),
						c.toDate(), c.intParam("limit", 50)));
		add("credit-limit-breaches", "Customers", "Credit limit breaches", "Customers owing more than their limit",
				null, false,
				List.of(text("customer", "Customer"), money("limit", "Credit limit"), money("balance", "Owed"),
						money("over", "Over the limit")),
				List.of(), c -> sql("""
						select name as customer, credit_limit as limit, balance, balance - credit_limit as over from customer
						where balance > credit_limit order by balance - credit_limit desc"""));
		add("payables-ageing", "Suppliers", "Payables ageing", "What the shop owes suppliers, by bill age", null,
				true, withFirst(text("supplier", "Supplier"), ageCols.stream().map(col -> new Col(col.key(), col.label(),
						col.kind(), true, true)).toList()), List.of(), c -> sql("select x.name as supplier, " + ageing + " " + """
						 from (select sp.name, g.due, current_date - coalesce(g.bill_date, g.business_date) as age
						       from grn g join supplier sp on sp.id = g.supplier_id where g.due > 0""" + c.branch("g.branch_id")
						+ """
						      ) x group by x.name order by sum(x.due) desc"""));
		add("supplier-payments", "Suppliers", "Payments made", "Money paid to suppliers", null, true,
				List.of(text("number", "Payment"), date("date", "Date"), text("supplier", "Supplier"),
						text("method", "Method"), cost("amount", "Amount")),
				List.of(), c -> sql("""
						select p.number, p.business_date as date, s.name as supplier, p.method_key as method, p.amount
						from supplier_payment p join supplier s on s.id = p.supplier_id where p.business_date between ? and ?
						order by p.id""", c.fromDate(), c.toDate()));

		// ------------------------------------------------------------ cash
		add("till-closings", "Cash", "Till closing (Z-report)", "Each till session with short or over", null, false,
				List.of(text("till", "Till"), text("cashier", "Cashier"), text("opened", "Opened"),
						text("closed", "Closed"), money("expected", "Expected cash"), money("counted", "Counted"),
						money("difference", "Short / over")),
				List.of(), c -> sql("""
						select t.name as till, ts.actor_name as cashier,
						       to_char(ts.opened_at at time zone 'Asia/Karachi', 'DD Mon HH24:MI') as opened,
						       to_char(ts.closed_at at time zone 'Asia/Karachi', 'DD Mon HH24:MI') as closed,
						       ts.expected_cash as expected, ts.counted_cash as counted, ts.difference
						from till_session ts join till t on t.id = ts.till_id where ts.business_date between ? and ?"""
						+ c.branch("ts.branch_id") + " order by ts.opened_at", c.fromDate(), c.toDate()));
		add("cash-in-out", "Cash", "Cash in and out", "Petty cash taken from or added to tills", null, false,
				List.of(text("at", "When"), text("till", "Till"), text("kind", "In / out"), money("amount", "Amount"),
						text("reason", "Reason"), text("by", "By")),
				List.of(), c -> sql("""
						select to_char(m.at at time zone 'Asia/Karachi', 'DD Mon YYYY HH24:MI') as at, t.name as till, m.kind,
						       m.amount, m.reason, m.actor_name as by from cash_movement m join till_session ts on ts.id = m.till_session_id
						join till t on t.id = ts.till_id where ts.business_date between ? and ?""" + c.branch("ts.branch_id")
						+ " order by m.at", c.fromDate(), c.toDate()));
		add("expenses-by-category", "Cash", "Expenses by category", "Where the money went", null, false,
				List.of(text("category", "Category"), count("entries", "Entries"), money("amount", "Amount")), List.of(),
				c -> sql("""
						select c.name as category, count(*) as entries, sum(e.amount) as amount from expense e
						join expense_category c on c.id = e.category_id where e.status = 'POSTED'
						and e.business_date between ? and ?""" + c.branch("e.branch_id") + " group by 1 order by 3 desc",
						c.fromDate(), c.toDate()));

		// ------------------------------------------------------------ tax
		add("tax-collected", "Tax", "Sales tax collected", "Taxable sales and tax by month (GST return figures)", null,
				false,
				List.of(text("month", "Month"), money("taxable", "Taxable sales"), money("tax", "Sales tax")), List.of(),
				c -> sql("""
						select to_char(s.business_date, 'YYYY-MM') as month, sum(s.total - s.tax) as taxable, sum(s.tax) as tax
						from sale s where""" + sales(c) + " group by 1 order by 1", c.fromDate(), c.toDate()));
		add("tax-by-rate", "Tax", "Tax by rate", "Sales and tax per tax rate", null, false,
				List.of(text("rate", "Rate %"), money("taxable", "Taxable sales"), money("tax", "Tax")), List.of(),
				c -> sql("""
						select trim(trailing '.' from trim(trailing '0' from l.tax_rate::text)) as rate,
						       sum(l.total - l.tax) as taxable, sum(l.tax) as tax
						from sale_line l join sale s on s.id = l.sale_id where""" + sales(c) + " group by l.tax_rate order by l.tax_rate",
						c.fromDate(), c.toDate()));

		// ------------------------------------------------------------ mobile shop
		add("imei-stock", "Mobile shop", "IMEI stock list", "Every phone in stock with its details", ModuleKey.SERIALS,
				false,
				List.of(text("product", "Model"), text("imei", "IMEI"), text("imei2", "IMEI 2"), text("colour", "Colour"),
						text("storage", "Storage"), text("condition", "Condition"), text("pta", "PTA"),
						text("branch", "Branch"), cost("cost", "Cost")),
				List.of(), c -> sql("""
						select p.name as product, u.serial as imei, u.serial2 as imei2, u.colour, u.storage, u.condition,
						       u.pta_status as pta, b.name as branch, u.cost from serial_unit u join product p on p.id = u.product_id
						join branch b on b.id = u.branch_id where u.status = 'IN_STOCK'""" + c.branch("u.branch_id")
						+ " order by p.name, u.serial"));
		add("imei-register", "Mobile shop", "IMEI purchase and sale register", "Every phone in and out", ModuleKey.SERIALS,
				false,
				List.of(text("at", "When"), text("type", "Type"), text("imei", "IMEI"), text("product", "Model"),
						text("document", "Document"), text("by", "By")),
				List.of(), c -> sql("""
						select to_char(m.at at time zone 'Asia/Karachi', 'DD Mon YYYY HH24:MI') as at, m.move_type as type,
						       u.serial as imei, p.name as product, m.doc_number as document, m.actor_name as by
						from stock_movement m join serial_unit u on u.id = m.serial_id join product p on p.id = m.product_id
						where m.at >= ? and m.at < ?""" + c.branch("m.branch_id") + " order by m.at", c.fromTs(), c.toTs()));
		addPii("buyback-register", "Mobile shop", "Used phones bought", "Purchases from walk-in sellers with CNIC",
				ModuleKey.USED_BUYBACK,
				List.of(text("number", "Slip"), date("date", "Date"), text("seller", "Seller"), text("cnic", "CNIC"),
						text("phone", "Phone"), text("imei", "IMEI"), text("product", "Model"), money("price", "Paid")),
				c -> sql("""
						select bb.number, bb.business_date as date, bb.seller_name as seller, bb.seller_cnic as cnic,
						       bb.seller_phone as phone, u.serial as imei, p.name as product, bb.price
						from buyback bb join product p on p.id = bb.product_id left join serial_unit u on u.buyback_id = bb.id
						where bb.business_date between ? and ?""" + c.branch("bb.branch_id") + " order by bb.id",
						c.fromDate(), c.toDate()));
		add("warranty-claims", "Mobile shop", "Warranty claims", "Claims registered and their status",
				ModuleKey.WARRANTY, false,
				List.of(text("number", "Claim"), text("at", "When"), text("imei", "IMEI"), text("product", "Model"),
						text("customer", "Customer"), text("issue", "Fault"), text("status", "Status")),
				List.of(), c -> sql("""
						select w.number, to_char(w.at at time zone 'Asia/Karachi', 'DD Mon YYYY') as at, u.serial as imei,
						       p.name as product, w.customer_name as customer, w.issue, w.status
						from warranty_claim w join product p on p.id = w.product_id left join serial_unit u on u.id = w.serial_unit_id
						where w.at >= ? and w.at < ? order by w.id""", c.fromTs(), c.toTs()));

		// ------------------------------------------------------------ medical store
		add("near-expiry", "Medical store", "Near-expiry stock", "Batches expiring in 30, 60 or 90 days, by distributor",
				ModuleKey.BATCHES, false,
				List.of(text("product", "Item"), text("batch", "Batch"), date("expiry", "Expiry"),
						count("daysLeft", "Days left"), qty("qty", "Qty"), cost("value", "Value at cost"),
						text("distributor", "Distributor")),
				List.of("days"), c -> sql("""
						select p.name as product, bt.batch_no as batch, bt.expiry_date as expiry, bt.expiry_date - current_date as "daysLeft",
						       sum(s.qty) as qty, sum(s.qty) * coalesce(bt.cost, p.cost_avg) as value,
						       (select sp.name from grn_line gl join grn g on g.id = gl.grn_id join supplier sp on sp.id = g.supplier_id
						        where gl.batch_id = bt.id order by gl.id desc limit 1) as distributor
						from batch bt join product_stock s on s.batch_id = bt.id join product p on p.id = bt.product_id
						where s.qty > 0 and bt.expiry_date between current_date and current_date + ?""" + c.branch("s.branch_id")
						+ " group by p.name, bt.id, bt.batch_no, bt.expiry_date, bt.cost, p.cost_avg order by bt.expiry_date",
						c.intParam("days", 90)));
		add("expired-stock", "Medical store", "Expired stock", "Expired batches still on the shelf, for return or write-off",
				ModuleKey.BATCHES, false,
				List.of(text("product", "Item"), text("batch", "Batch"), date("expiry", "Expired on"), qty("qty", "Qty"),
						cost("value", "Value at cost"), text("distributor", "Distributor")),
				List.of(), c -> sql("""
						select p.name as product, bt.batch_no as batch, bt.expiry_date as expiry, sum(s.qty) as qty,
						       sum(s.qty) * coalesce(bt.cost, p.cost_avg) as value,
						       (select sp.name from grn_line gl join grn g on g.id = gl.grn_id join supplier sp on sp.id = g.supplier_id
						        where gl.batch_id = bt.id order by gl.id desc limit 1) as distributor
						from batch bt join product_stock s on s.batch_id = bt.id join product p on p.id = bt.product_id
						where s.qty > 0 and bt.expiry_date < current_date""" + c.branch("s.branch_id")
						+ " group by p.name, bt.id, bt.batch_no, bt.expiry_date, bt.cost, p.cost_avg order by bt.expiry_date"));
		add("batch-stock", "Medical store", "Batch-wise stock", "Stock per batch and branch", ModuleKey.BATCHES, false,
				List.of(text("product", "Item"), text("batch", "Batch"), date("expiry", "Expiry"), text("branch", "Branch"),
						qty("qty", "Qty")),
				List.of(), c -> sql("""
						select p.name as product, bt.batch_no as batch, bt.expiry_date as expiry, b.name as branch, s.qty
						from product_stock s join batch bt on bt.id = s.batch_id join product p on p.id = s.product_id
						join branch b on b.id = s.branch_id where s.qty <> 0""" + c.branch("s.branch_id")
						+ " order by p.name, bt.expiry_date"));
		add("sales-by-formula", "Medical store", "Sales by formula", "Generic names sold", ModuleKey.FORMULA, false,
				List.of(text("formula", "Formula"), qty("qty", "Qty"), money("amount", "Amount")), List.of(),
				c -> sql("""
						select coalesce(ph.formula, 'No formula') as formula, sum(l.base_qty) as qty, sum(l.total) as amount
						from sale_line l join sale s on s.id = l.sale_id left join product_pharma ph on ph.product_id = l.product_id
						where""" + sales(c) + " group by 1 order by 3 desc", c.fromDate(), c.toDate()));

		// ------------------------------------------------------------ garments
		add("variant-stock-sales", "Garments", "Stock and sales by size and colour", "Each variant of each design",
				ModuleKey.VARIANTS, false,
				List.of(text("design", "Design"), text("size", "Size"), text("colour", "Colour"), qty("stock", "In stock"),
						qty("sold", "Sold in period")),
				List.of(), c -> sql("""
						select parent.name as design, v.size, coalesce(v.colour, v.shade) as colour,
						       coalesce((select sum(s.qty) from product_stock s where s.product_id = v.product_id"""
						+ c.branch("s.branch_id") + """
						       ), 0) as stock,
						       coalesce((select sum(l.base_qty) from sale_line l join sale s on s.id = l.sale_id
						                 where l.product_id = v.product_id and""" + sales(c) + """
						       ), 0) as sold
						from product_variant v join product parent on parent.id = v.parent_id order by parent.name, v.size, v.colour""",
						c.fromDate(), c.toDate()));
	}

	/** Adds the optional category / brand / supplier filters of the stock report. */
	private static Sql filtered(Sql base, Ctx c, String orderBy) {
		StringBuilder sql = new StringBuilder(base.sql());
		List<Object> args = new ArrayList<>(base.args());
		if (c.longParam("categoryId") != null) {
			sql.append(" and p.category_id = ?");
			args.add(c.longParam("categoryId"));
		}
		if (c.longParam("brandId") != null) {
			sql.append(" and p.brand_id = ?");
			args.add(c.longParam("brandId"));
		}
		if (c.longParam("supplierId") != null) {
			sql.append(" and p.supplier_id = ?");
			args.add(c.longParam("supplierId"));
		}
		sql.append(orderBy);
		return new Sql(sql.toString(), args);
	}
}
