package com.selectcode.pos.reports.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shop.app.BranchAccess;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dashboard by role (RPT-01): today's sales, profit, invoices, cash in the drawers, what customers owe and what
 * the shop owes, stock alerts, a 14-day chart and top items. Each figure is shown only to roles allowed to see it;
 * global search across products, customers, suppliers and invoices (Ctrl K) is here too.
 */
@Service
public class DashboardService {

	public record DayPoint(LocalDate date, String sales, String profit, int invoices) {
	}

	public record TopItem(long productId, String name, String qty, String amount) {
	}

	public record Dashboard(LocalDate date, Long branchId, String salesToday, Integer invoicesToday,
			String averageBill, String profitToday, String returnsToday, String cashInDrawers, Integer openTills,
			String customerDues, String supplierDues, Long lowStock, Long outOfStock, Long expiringSoon,
			Long transfersInTransit, List<DayPoint> last14Days, List<TopItem> topItems) {
	}

	public record SearchHit(String type, long id, String title, String subtitle) {
	}

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	private final ShopTime time;

	DashboardService(JdbcTemplate jdbc, BranchAccess branches, ShopTime time) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.time = time;
	}

	@Transactional(readOnly = true)
	public Dashboard dashboard(Long branchId) {
		if (branchId != null) {
			branches.require(branchId);
		}
		boolean sales = CurrentActor.hasPermission(Permissions.SALES_VIEW)
				|| CurrentActor.hasPermission(Permissions.REPORTS_VIEW);
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		boolean cash = CurrentActor.hasPermission(Permissions.CASH_VIEW);
		boolean customers = CurrentActor.hasPermission(Permissions.CUSTOMERS_VIEW);
		boolean suppliers = CurrentActor.hasPermission(Permissions.PURCHASING_VIEW) && cost;
		boolean stock = CurrentActor.hasPermission(Permissions.STOCK_VIEW);
		LocalDate today = time.today();
		String b = branchId != null ? " and s.branch_id = " + branchId : branches.filter("s.branch_id");
		String bs = branchId != null ? " and branch_id = " + branchId : branches.filter("branch_id");

		String salesToday = null;
		Integer invoices = null;
		String average = null;
		String profit = null;
		String returns = null;
		List<DayPoint> days = List.of();
		List<TopItem> top = List.of();
		if (sales) {
			Map<String, Object> t = jdbc.queryForMap("""
					select count(*) as n, coalesce(sum(total), 0) as total, coalesce(sum(total - tax - cost_total), 0) as profit
					from sale s where s.status = 'POSTED' and s.business_date = ?""" + b, Date.valueOf(today));
			BigDecimal total = (BigDecimal) t.get("total");
			int n = ((Number) t.get("n")).intValue();
			salesToday = Amounts.text(total);
			invoices = n;
			average = Amounts.text(n == 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP));
			profit = cost ? Amounts.text((BigDecimal) t.get("profit")) : null;
			returns = Amounts.text(jdbc.queryForObject(
					"select coalesce(sum(value), 0) from sale_return where business_date = ?" + bs, BigDecimal.class,
					Date.valueOf(today)));
			days = new ArrayList<>();
			Map<LocalDate, Map<String, Object>> byDay = new java.util.HashMap<>();
			jdbc.query("""
					select s.business_date as d, count(*) as n, sum(s.total) as total, sum(s.total - s.tax - s.cost_total) as profit
					from sale s where s.status = 'POSTED' and s.business_date between ? and ?""" + b
					+ " group by s.business_date", rs -> {
						byDay.put(rs.getObject("d", LocalDate.class), Map.of("n", rs.getInt("n"), "total",
								rs.getBigDecimal("total"), "profit", rs.getBigDecimal("profit")));
					}, Date.valueOf(today.minusDays(13)), Date.valueOf(today));
			for (LocalDate d = today.minusDays(13); !d.isAfter(today); d = d.plusDays(1)) {
				Map<String, Object> v = byDay.get(d);
				days.add(new DayPoint(d, Amounts.text(v == null ? BigDecimal.ZERO : (BigDecimal) v.get("total")),
						cost ? Amounts.text(v == null ? BigDecimal.ZERO : (BigDecimal) v.get("profit")) : null,
						v == null ? 0 : (Integer) v.get("n")));
			}
			top = jdbc.query("""
					select l.product_id, l.product_name, sum(l.base_qty) as qty, sum(l.total) as amount
					from sale_line l join sale s on s.id = l.sale_id where s.status = 'POSTED' and s.business_date between ? and ?"""
					+ b + " group by l.product_id, l.product_name order by sum(l.total) desc limit 5",
					(rs, i) -> new TopItem(rs.getLong("product_id"), rs.getString("product_name"),
							Amounts.qtyText(rs.getBigDecimal("qty")), Amounts.text(rs.getBigDecimal("amount"))),
					Date.valueOf(today.minusDays(6)), Date.valueOf(today));
		}
		String drawers = null;
		Integer openTills = null;
		if (cash) {
			List<Long> open = jdbc.queryForList("select id from till_session where closed_at is null" + bs, Long.class);
			openTills = open.size();
			BigDecimal sum = BigDecimal.ZERO;
			for (Long id : open) {
				sum = sum.add(jdbc.queryForObject("""
						select s.opening_float
						  + coalesce((select sum(amount) from sale_payment p where p.till_session_id = s.id and p.method_key = 'CASH'), 0)
						  + coalesce((select sum(unallocated) from customer_payment c where c.till_session_id = s.id and c.method_key = 'CASH'), 0)
						  + coalesce((select sum(case when kind = 'IN' then amount else -amount end) from cash_movement c where c.till_session_id = s.id), 0)
						  - coalesce((select sum(amount) from expense e where e.till_session_id = s.id and e.status = 'POSTED'), 0)
						  - coalesce((select sum(price) from buyback b where b.till_session_id = s.id and b.method_key = 'CASH'), 0)
						from till_session s where s.id = ?""", BigDecimal.class, id));
			}
			drawers = Amounts.text(sum);
		}
		String customerDues = customers ? Amounts.text(jdbc.queryForObject(
				"select coalesce(sum(balance), 0) from customer where balance > 0", BigDecimal.class)) : null;
		String supplierDues = suppliers ? Amounts.text(jdbc.queryForObject(
				"select coalesce(sum(balance), 0) from supplier where balance > 0", BigDecimal.class)) : null;
		Long low = null;
		Long out = null;
		Long expiring = null;
		Long transit = null;
		if (stock) {
			String ab = branchId != null ? " and coalesce(branch_id, " + branchId + ") = " + branchId : "";
			low = jdbc.queryForObject("select count(*) from stock_alert where resolved_at is null and kind = 'LOW'" + ab,
					Long.class);
			out = jdbc.queryForObject("select count(*) from stock_alert where resolved_at is null and kind = 'OUT'" + ab,
					Long.class);
			expiring = jdbc.queryForObject(
					"select count(*) from stock_alert where resolved_at is null and kind in ('NEAR_EXPIRY', 'EXPIRED')" + ab,
					Long.class);
			transit = jdbc.queryForObject("select count(*) from transfer where status = 'DISPATCHED'", Long.class);
		}
		return new Dashboard(today, branchId, salesToday, invoices, average, profit, returns, drawers, openTills,
				customerDues, supplierDues, low, out, expiring, transit, days, top);
	}

	/** Search (Ctrl K): products by name/SKU/barcode/formula, customers, suppliers, invoices and IMEIs. */
	@Transactional(readOnly = true)
	public List<SearchHit> search(String q) {
		List<SearchHit> hits = new ArrayList<>();
		if (q == null || q.trim().length() < 2) {
			return hits;
		}
		String text = q.trim();
		String like = "%" + text.toLowerCase() + "%";
		if (CurrentActor.hasPermission(Permissions.CATALOG_VIEW)) {
			hits.addAll(jdbc.query("""
					select p.id, p.name, p.sku, p.price from product p left join product_pharma ph on ph.product_id = p.id
					where p.active and (lower(p.name) like ? or lower(p.sku) = ? or lower(coalesce(ph.formula, '')) like ?
					      or exists (select 1 from product_barcode b where b.product_id = p.id and b.barcode = ?))
					order by p.name limit 8""", (rs, i) -> new SearchHit("product", rs.getLong("id"), rs.getString("name"),
					rs.getString("sku") + " · Rs " + Amounts.text(rs.getBigDecimal("price"))), like, text.toLowerCase(), like,
					text));
		}
		if (CurrentActor.hasPermission(Permissions.CUSTOMERS_VIEW)) {
			hits.addAll(jdbc.query("""
					select id, name, phone, balance from customer where active and (lower(name) like ? or phone like ?)
					order by name limit 5""", (rs, i) -> new SearchHit("customer", rs.getLong("id"), rs.getString("name"),
					"Balance Rs " + Amounts.text(rs.getBigDecimal("balance"))), like, "%" + text + "%"));
		}
		if (CurrentActor.hasPermission(Permissions.PURCHASING_VIEW)) {
			hits.addAll(jdbc.query("""
					select id, name, company from supplier where active and (lower(name) like ? or lower(coalesce(company, '')) like ?)
					order by name limit 5""", (rs, i) -> new SearchHit("supplier", rs.getLong("id"), rs.getString("name"),
					rs.getString("company")), like, like));
		}
		if (CurrentActor.hasPermission(Permissions.SALES_VIEW)) {
			hits.addAll(jdbc.query("select id, number, total, business_date from sale where upper(number) like ?"
					+ branches.filter("branch_id") + " order by id desc limit 5",
					(rs, i) -> new SearchHit("sale", rs.getLong("id"), rs.getString("number"),
							rs.getObject("business_date", LocalDate.class) + " · Rs " + Amounts.text(rs.getBigDecimal("total"))),
					"%" + text.toUpperCase() + "%"));
		}
		if (CurrentActor.hasPermission(Permissions.STOCK_VIEW) || CurrentActor.hasPermission(Permissions.POS_SELL)) {
			hits.addAll(jdbc.query("""
					select u.id, u.serial, p.name, u.status from serial_unit u join product p on p.id = u.product_id
					where u.status <> 'VOID' and (upper(u.serial) like ? or upper(u.serial2) like ?) limit 5""",
					(rs, i) -> new SearchHit("serial", rs.getLong("id"), rs.getString("serial"),
							rs.getString("name") + " · " + rs.getString("status").toLowerCase().replace('_', ' ')),
					text.toUpperCase() + "%", text.toUpperCase() + "%"));
		}
		return hits;
	}
}
