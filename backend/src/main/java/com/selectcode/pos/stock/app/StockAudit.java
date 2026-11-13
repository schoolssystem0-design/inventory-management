package com.selectcode.pos.stock.app;

import com.selectcode.pos.shared.jobs.Jobs;
import com.selectcode.pos.shared.money.Amounts;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock is rebuildable from its history (NFR-05, D1): for every product, branch and batch the stored quantity must
 * equal the sum of its movements, and every serial unit in stock must be counted. Runs at 03:00 every night
 * (docs/ARCHITECTURE.md §10) and on demand; any difference is logged as an error for the on-call developer.
 */
@Service
public class StockAudit {

	private static final Logger log = LoggerFactory.getLogger(StockAudit.class);

	public record Difference(long productId, String productName, long branchId, Long batchId, String stored,
			String fromHistory) {
	}

	private final JdbcTemplate jdbc;

	private final Jobs jobs;

	StockAudit(JdbcTemplate jdbc, Jobs jobs) {
		this.jdbc = jdbc;
		this.jobs = jobs;
	}

	/** Differences between stored stock and the sum of movements for the current shop (empty = all good). */
	@Transactional(readOnly = true)
	public List<Difference> verify() {
		return jdbc.query("""
				with moved as (
				    select product_id, branch_id, coalesce(batch_id, 0) as batch, sum(qty) as qty
				    from stock_movement group by product_id, branch_id, coalesce(batch_id, 0)),
				stored as (
				    select product_id, branch_id, coalesce(batch_id, 0) as batch, qty from product_stock)
				select coalesce(s.product_id, m.product_id) as product_id, coalesce(s.branch_id, m.branch_id) as branch_id,
				       coalesce(s.batch, m.batch) as batch, coalesce(s.qty, 0) as stored, coalesce(m.qty, 0) as moved,
				       p.name
				from stored s full join moved m on m.product_id = s.product_id and m.branch_id = s.branch_id and m.batch = s.batch
				join product p on p.id = coalesce(s.product_id, m.product_id)
				where coalesce(s.qty, 0) <> coalesce(m.qty, 0)
				order by 1, 2, 3""", (rs, i) -> new Difference(rs.getLong("product_id"), rs.getString("name"),
				rs.getLong("branch_id"), rs.getLong("batch") == 0 ? null : rs.getLong("batch"),
				Amounts.qtyText(rs.getBigDecimal("stored")), Amounts.qtyText(rs.getBigDecimal("moved"))));
	}

	/** Serial products whose stock differs from their units in stock (should always be empty). */
	@Transactional(readOnly = true)
	public List<Difference> verifySerials() {
		return jdbc.query("""
				select p.id, p.name, b.id as branch_id,
				       coalesce((select sum(qty) from product_stock s where s.product_id = p.id and s.branch_id = b.id), 0) as stored,
				       (select count(*) from serial_unit u where u.product_id = p.id and u.branch_id = b.id and u.status = 'IN_STOCK') as units
				from product p cross join branch b where p.kind = 'SERIAL'
				  and coalesce((select sum(qty) from product_stock s where s.product_id = p.id and s.branch_id = b.id), 0)
				      <> (select count(*) from serial_unit u where u.product_id = p.id and u.branch_id = b.id and u.status = 'IN_STOCK')""",
				(rs, i) -> new Difference(rs.getLong("id"), rs.getString("name"), rs.getLong("branch_id"), null,
						Amounts.qtyText(rs.getBigDecimal("stored")), Amounts.qtyText(new BigDecimal(rs.getLong("units")))));
	}

	@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Karachi")
	public void nightly() {
		if (!jobs.enabled()) {
			return;
		}
		jobs.exclusive("stock-audit", () -> jobs.forEachShop("stock-audit", jobs.shops(), shop -> {
			List<Difference> diffs = verify();
			if (!diffs.isEmpty()) {
				log.error("STOCK AUDIT: shop {} has {} stock rows that differ from their history: {}", shop, diffs.size(),
						diffs.subList(0, Math.min(5, diffs.size())));
			}
		}));
	}
}
