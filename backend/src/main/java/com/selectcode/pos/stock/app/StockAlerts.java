package com.selectcode.pos.stock.app;

import com.selectcode.pos.shared.jobs.Jobs;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock alerts (STK-06, NTF-04): low, out of stock, near expiry, expired and dead stock (not sold in the shop's
 * dead-stock days). Refreshed every morning and on demand; an alert closes itself when the condition is gone.
 */
@Service
public class StockAlerts {

	public record AlertView(long id, String kind, long productId, String productName, Long branchId,
			String branchName, Long batchId, String batchNo, String message, Instant createdAt, Instant seenAt) {
	}

	public record AlertSummary(Map<String, Long> open, long unseen) {
	}

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	private final ShopTime time;

	private final Jobs jobs;

	private final com.selectcode.pos.notifications.app.Notifier notifier;

	StockAlerts(JdbcTemplate jdbc, BranchAccess branches, ShopTime time, Jobs jobs,
			com.selectcode.pos.notifications.app.Notifier notifier) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.time = time;
		this.jobs = jobs;
		this.notifier = notifier;
	}

	/** Recomputes the current shop's alerts. */
	@Transactional
	public AlertSummary refresh() {
		long shop = TenantContext.requireShopId();
		LocalDate today = time.today();
		Map<String, Object> s = jdbc.queryForMap(
				"select near_expiry_days, dead_stock_days from shop_settings where shop_id = ?", shop);
		int nearDays = ((Number) s.get("near_expiry_days")).intValue();
		int deadDays = ((Number) s.get("dead_stock_days")).intValue();
		Date todayDate = Date.valueOf(today);
		Date nearLimit = Date.valueOf(today.plusDays(nearDays));
		java.sql.Timestamp deadSince = java.sql.Timestamp.from(ShopTime.startOf(today.minusDays(deadDays)));

		String stockPerBranch = """
				select p.id as product_id, b.id as branch_id, p.name as pname, b.name as bname, p.reorder_level,
				       coalesce((select sum(qty) from product_stock s where s.product_id = p.id and s.branch_id = b.id), 0) as qty
				from product p cross join branch b
				where p.active and b.active and b.kind = 'SHOP' and p.kind in ('STANDARD', 'SERIAL', 'BATCH') and p.reorder_level > 0""";
		jdbc.update("""
				insert into stock_alert (shop_id, kind, product_id, branch_id, message)
				select ?, 'LOW', x.product_id, x.branch_id,
				       x.pname || ': ' || trim(trailing '.' from trim(trailing '0' from x.qty::text)) || ' left at ' || x.bname
				from (""" + stockPerBranch + ") x where x.qty > 0 and x.qty <= x.reorder_level " + """
				on conflict (shop_id, kind, product_id, coalesce(branch_id, 0), coalesce(batch_id, 0)) where resolved_at is null
				do nothing""", shop);
		jdbc.update("""
				insert into stock_alert (shop_id, kind, product_id, branch_id, message)
				select ?, 'OUT', x.product_id, x.branch_id, x.pname || ' is out of stock at ' || x.bname
				from (""" + stockPerBranch + ") x where x.qty <= 0 " + """
				on conflict (shop_id, kind, product_id, coalesce(branch_id, 0), coalesce(batch_id, 0)) where resolved_at is null
				do nothing""", shop);
		jdbc.update("update stock_alert a set resolved_at = now() where a.resolved_at is null and a.kind = 'LOW' "
				+ "and not exists (select 1 from (" + stockPerBranch + ") x where x.product_id = a.product_id "
				+ "and x.branch_id = a.branch_id and x.qty > 0 and x.qty <= x.reorder_level)");
		jdbc.update("update stock_alert a set resolved_at = now() where a.resolved_at is null and a.kind = 'OUT' "
				+ "and not exists (select 1 from (" + stockPerBranch + ") x where x.product_id = a.product_id "
				+ "and x.branch_id = a.branch_id and x.qty <= 0)");

		// Batches: near expiry and expired, per branch with stock.
		jdbc.update("""
				insert into stock_alert (shop_id, kind, product_id, branch_id, batch_id, message)
				select ?, case when bt.expiry_date < ? then 'EXPIRED' else 'NEAR_EXPIRY' end, bt.product_id, s.branch_id, bt.id,
				       p.name || ' batch ' || bt.batch_no || case when bt.expiry_date < ? then ' expired on ' else ' expires on ' end
				       || to_char(bt.expiry_date, 'DD Mon YYYY') || ' (' || trim(trailing '.' from trim(trailing '0' from s.qty::text))
				       || ' at ' || b.name || ')'
				from batch bt join product_stock s on s.batch_id = bt.id join product p on p.id = bt.product_id
				join branch b on b.id = s.branch_id
				where s.qty > 0 and bt.expiry_date is not null and bt.expiry_date <= ?
				on conflict (shop_id, kind, product_id, coalesce(branch_id, 0), coalesce(batch_id, 0)) where resolved_at is null
				do nothing""", shop, todayDate, todayDate, nearLimit);
		jdbc.update("""
				update stock_alert a set resolved_at = now() where a.resolved_at is null and a.kind in ('NEAR_EXPIRY', 'EXPIRED')
				and not exists (select 1 from product_stock s join batch bt on bt.id = s.batch_id
				                where s.batch_id = a.batch_id and s.branch_id = a.branch_id and s.qty > 0
				                and (case when bt.expiry_date < ? then 'EXPIRED' else 'NEAR_EXPIRY' end) = a.kind
				                and bt.expiry_date <= ?)""", todayDate, nearLimit);

		// Dead stock: in stock (any branch) but not sold for the shop's dead-stock days.
		String dead = """
				select p.id from product p
				where p.active and p.created_at < ? and p.kind in ('STANDARD', 'SERIAL', 'BATCH')
				  and (select coalesce(sum(qty), 0) from product_stock s where s.product_id = p.id) > 0
				  and not exists (select 1 from stock_movement m where m.product_id = p.id and m.move_type = 'SALE' and m.at >= ?)""";
		String deadMessage = "' has not sold in " + deadDays + " days'";
		jdbc.update("insert into stock_alert (shop_id, kind, product_id, message) select ?, 'DEAD', p.id, p.name || "
				+ deadMessage + " from product p where p.id in (" + dead + ") on conflict (shop_id, kind, product_id, "
				+ "coalesce(branch_id, 0), coalesce(batch_id, 0)) where resolved_at is null do nothing", shop, deadSince,
				deadSince);
		jdbc.update("update stock_alert a set resolved_at = now() where a.resolved_at is null and a.kind = 'DEAD' "
				+ "and a.product_id not in (" + dead + ")", deadSince, deadSince);
		notifyNew(today);
		return summary();
	}

	/** One bell message a day for stock staff when new low, out-of-stock or expiry alerts appeared (NTF-04). */
	private void notifyNew(LocalDate today) {
		Map<String, Object> fresh = jdbc.queryForMap("""
				select count(*) filter (where kind in ('LOW', 'OUT')) as stock, count(*) filter (where kind in ('NEAR_EXPIRY', 'EXPIRED')) as expiry
				from stock_alert where resolved_at is null and created_at >= ?""",
				java.sql.Timestamp.from(ShopTime.startOf(today)));
		long stock = ((Number) fresh.get("stock")).longValue();
		long expiry = ((Number) fresh.get("expiry")).longValue();
		if (stock + expiry == 0) {
			return;
		}
		List<String> parts = new ArrayList<>();
		if (stock > 0) {
			parts.add(stock + " item" + (stock == 1 ? "" : "s") + " low or out of stock");
		}
		if (expiry > 0) {
			parts.add(expiry + " batch" + (expiry == 1 ? "" : "es") + " expiring or expired");
		}
		notifier.inAppFor(com.selectcode.pos.shared.security.Permissions.STOCK_VIEW, "stock_alert", "Stock alerts",
				String.join(", ", parts), "/stock/alerts", "stock-alerts:" + today);
	}

	@Transactional(readOnly = true)
	public AlertSummary summary() {
		Map<String, Long> open = new LinkedHashMap<>();
		for (String kind : List.of("LOW", "OUT", "NEAR_EXPIRY", "EXPIRED", "DEAD")) {
			open.put(kind, 0L);
		}
		String filter = branches.filter("coalesce(a.branch_id, -1)").replace("in (", "in (-1,");
		jdbc.query("select kind, count(*) as n from stock_alert a where resolved_at is null" + filter + " group by kind",
				rs -> {
					open.put(rs.getString("kind"), rs.getLong("n"));
				});
		Long unseen = jdbc.queryForObject(
				"select count(*) from stock_alert a where resolved_at is null and seen_at is null" + filter, Long.class);
		return new AlertSummary(open, unseen == null ? 0 : unseen);
	}

	@Transactional(readOnly = true)
	public PageResponse<AlertView> list(String kind, Long branchId, int page, int size) {
		String filter = branches.filter("coalesce(a.branch_id, -1)").replace("in (", "in (-1,");
		StringBuilder where = new StringBuilder(" where a.resolved_at is null" + filter);
		List<Object> args = new ArrayList<>();
		if (kind != null && !kind.isBlank()) {
			where.append(" and a.kind = ?");
			args.add(kind.trim().toUpperCase());
		}
		if (branchId != null) {
			where.append(" and a.branch_id = ?");
			args.add(branchId);
		}
		long total = jdbc.queryForObject("select count(*) from stock_alert a" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<AlertView> items = jdbc.query("""
				select a.*, p.name as product_name, b.name as branch_name, bt.batch_no from stock_alert a
				join product p on p.id = a.product_id left join branch b on b.id = a.branch_id
				left join batch bt on bt.id = a.batch_id""" + where
				+ " order by case a.kind when 'EXPIRED' then 0 when 'OUT' then 1 when 'NEAR_EXPIRY' then 2 when 'LOW' then 3 else 4 end,"
				+ " a.created_at desc limit ? offset ?",
				(rs, i) -> new AlertView(rs.getLong("id"), rs.getString("kind"), rs.getLong("product_id"),
						rs.getString("product_name"), (Long) rs.getObject("branch_id"), rs.getString("branch_name"),
						(Long) rs.getObject("batch_id"), rs.getString("batch_no"), rs.getString("message"),
						rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("seen_at") == null ? null : rs.getTimestamp("seen_at").toInstant()),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	@Transactional
	public void seen(long id) {
		int n = jdbc.update("update stock_alert set seen_at = coalesce(seen_at, now()) where id = ?", id);
		if (n == 0) {
			throw ApiException.notFound("Alert");
		}
	}

	/** 06:00 every day: refresh alerts for every open shop. */
	@Scheduled(cron = "0 0 6 * * *", zone = "Asia/Karachi")
	public void nightly() {
		if (!jobs.enabled()) {
			return;
		}
		jobs.exclusive("stock-alerts", () -> jobs.forEachShop("stock-alerts",
				jobs.shops("TRIAL", "ACTIVE", "GRACE", "READ_ONLY"), shop -> refresh()));
	}
}
