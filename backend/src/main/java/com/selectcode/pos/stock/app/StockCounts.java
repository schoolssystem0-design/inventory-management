package com.selectcode.pos.stock.app;

import com.selectcode.pos.iam.app.ApprovalService;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock counts (STK-03): count all or part of a branch's stock while selling continues. The system quantity of each
 * line is snapshotted when the count starts; each counted line remembers when it was counted, so sales and receipts
 * after the snapshot but before the count are accounted for: difference = counted − (snapshot + moves until counted).
 * Differences are posted as COUNT movements after approval; losses above the write-off limit need a manager.
 */
@Service
public class StockCounts {

	/** @param categoryId count only this category (and its sub-categories); null = all products */
	public record StartCount(Long branchId, Long categoryId, String scope, String note) {
	}

	public record CountEntry(Long productId, Long batchId, String countedQty) {
	}

	public record CountLineView(long id, long productId, String productName, String sku, String barcode, Long batchId,
			String batchNo, String systemQty, String countedQty, String expectedQty, String difference,
			String differenceValue) {
	}

	public record CountView(long id, String number, long branchId, String branchName, String scope, String status,
			String note, int lines, int counted, String lossValue, String gainValue, Instant startedAt,
			Instant postedAt, List<CountLineView> items) {
	}

	public record CountListItem(long id, String number, String branchName, String scope, String status, int lines,
			int counted, Instant startedAt, Instant postedAt) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final ApprovalService approvals;

	private final AuditService audit;

	private final ShopTime time;

	StockCounts(JdbcTemplate jdbc, StockService stock, BranchAccess branches, ShopConfigService numbering,
			ApprovalService approvals, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.branches = branches;
		this.numbering = numbering;
		this.approvals = approvals;
		this.audit = audit;
		this.time = time;
	}

	/** Starts a count and snapshots the system quantity of every product (and batch) in scope. */
	@Transactional
	public CountView start(StartCount in) {
		var branch = branches.requireActive(in.branchId());
		Integer open = jdbc.queryForObject("select count(*) from stock_count where branch_id = ? and status = 'OPEN'",
				Integer.class, branch.id());
		if (open != null && open > 0) {
			throw ApiException.rule("count_open", "A stock count is already open at " + branch.name()
					+ ". Post or cancel it first.");
		}
		String scope = in.scope() == null || in.scope().isBlank() ? "All products" : in.scope().trim();
		if (in.categoryId() != null) {
			String name = jdbc.queryForList("select name from category where id = ?", String.class, in.categoryId())
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Category"));
			scope = in.scope() == null || in.scope().isBlank() ? "Category: " + name : scope;
		}
		String number = numbering.nextNumber("STOCK_COUNT", branch.id());
		long id = jdbc.queryForObject("""
				insert into stock_count (shop_id, number, branch_id, scope, category_id, note, started_by)
				values (?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), number,
				branch.id(), trimTo(scope, 200), in.categoryId(), StockAdjustments.trim(in.note(), 500),
				StockAdjustments.userId());
		String category = in.categoryId() == null ? ""
				: " and p.category_id in (with recursive c(id) as (select " + in.categoryId()
						+ "::bigint union all select x.id from category x join c on x.parent_id = c.id) select id from c)";
		// Non-batch products: one line each; batch products: one line per batch with stock or recently seen.
		jdbc.update("""
				insert into stock_count_line (shop_id, count_id, product_id, batch_id, system_qty, unit_cost)
				select ?, ?, p.id, null, coalesce(s.qty, 0), p.cost_avg from product p
				left join product_stock s on s.product_id = p.id and s.branch_id = ? and s.batch_id is null
				where p.active and p.kind in ('STANDARD', 'SERIAL')""" + category, TenantContext.requireShopId(), id,
				branch.id());
		jdbc.update("""
				insert into stock_count_line (shop_id, count_id, product_id, batch_id, system_qty, unit_cost)
				select ?, ?, s.product_id, s.batch_id, s.qty, coalesce(b.cost, p.cost_avg) from product_stock s
				join product p on p.id = s.product_id join batch b on b.id = s.batch_id
				where s.branch_id = ? and s.batch_id is not null and p.active""" + category,
				TenantContext.requireShopId(), id, branch.id());
		audit.record("STOCK_COUNT_STARTED", "stock_count", id, null, Map.of("number", number, "scope", scope));
		return get(id);
	}

	/** Enters or scans counted quantities. A product (or batch) not in the snapshot is added with system qty 0. */
	@Transactional
	public CountView enter(long id, List<CountEntry> entries) {
		Map<String, Object> count = requireOpen(id);
		long branchId = ((Number) count.get("branch_id")).longValue();
		if (entries == null || entries.isEmpty()) {
			throw ApiException.field("entries", "required", "Enter at least one counted quantity");
		}
		for (int i = 0; i < entries.size(); i++) {
			CountEntry entry = entries.get(i);
			String f = "entries[" + i + "]";
			if (entry.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			BigDecimal counted = Amounts.parseQty(entry.countedQty(), f + ".countedQty");
			if (counted == null) {
				throw ApiException.field(f + ".countedQty", "required", "Enter the counted quantity");
			}
			Map<String, Object> product = jdbc.queryForList("select id, kind, cost_avg from product where id = ?",
					entry.productId()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Product"));
			if ("BATCH".equals(product.get("kind")) && entry.batchId() == null) {
				throw ApiException.field(f + ".batchId", "required", "Choose the batch");
			}
			if (entry.batchId() != null) {
				Integer ok = jdbc.queryForObject("select count(*) from batch where id = ? and product_id = ?",
						Integer.class, entry.batchId(), entry.productId());
				if (ok == null || ok == 0) {
					throw ApiException.notFound("Batch");
				}
			}
			int updated = jdbc.update("""
					update stock_count_line set counted_qty = ?, counted_at = now()
					where count_id = ? and product_id = ? and coalesce(batch_id, 0) = ?""", counted, id,
					entry.productId(), entry.batchId() == null ? 0L : entry.batchId());
			if (updated == 0) {
				// Not in the snapshot (e.g. a product created after the count started): its system qty then was what
				// was on hand at the start, which we reconstruct from movements.
				BigDecimal atStart = jdbc.queryForObject("""
						select coalesce((select qty from product_stock where product_id = ? and branch_id = ?
						                 and coalesce(batch_id, 0) = ?), 0)
						     - coalesce((select sum(qty) from stock_movement where product_id = ? and branch_id = ?
						                 and coalesce(batch_id, 0) = ? and at > ?), 0)""", BigDecimal.class,
						entry.productId(), branchId, entry.batchId() == null ? 0L : entry.batchId(), entry.productId(),
						branchId, entry.batchId() == null ? 0L : entry.batchId(), count.get("started_at"));
				jdbc.update("""
						insert into stock_count_line (shop_id, count_id, product_id, batch_id, system_qty, counted_qty,
						                              counted_at, unit_cost)
						values (?, ?, ?, ?, ?, ?, now(), ?)""", TenantContext.requireShopId(), id, entry.productId(),
						entry.batchId(), atStart, counted, product.get("cost_avg"));
			}
		}
		return get(id);
	}

	/**
	 * Posts the differences of counted lines (uncounted lines are left as they are). Losses above the write-off limit
	 * need a manager's approval.
	 */
	@Transactional
	public CountView post(long id, ApprovalRequest approval) {
		Map<String, Object> count = requireOpen(id);
		long branchId = ((Number) count.get("branch_id")).longValue();
		branches.requireActive(branchId);
		List<CountLineView> lines = lines(id, branchId, (Timestamp) count.get("started_at"), true);
		List<CountLineView> changed = lines.stream()
			.filter(l -> l.difference() != null && new BigDecimal(l.difference()).signum() != 0)
			.toList();
		if (lines.stream().noneMatch(l -> l.countedQty() != null)) {
			throw ApiException.rule("nothing_counted", "Nothing has been counted yet");
		}
		List<String> serialProducts = changed.stream()
			.filter(l -> "SERIAL".equals(jdbc.queryForObject("select kind from product where id = ?", String.class,
					l.productId())))
			.map(CountLineView::productName)
			.toList();
		if (!serialProducts.isEmpty()) {
			throw ApiException.rule("serial_count_difference", "Counts of IMEI/serial items differ ("
					+ String.join(", ", serialProducts) + "). Add or write off those units by IMEI with an adjustment.");
		}
		BigDecimal loss = BigDecimal.ZERO;
		for (CountLineView line : changed) {
			BigDecimal diff = new BigDecimal(line.difference());
			if (diff.signum() < 0) {
				loss = loss.add(new BigDecimal(line.differenceValue()).abs());
			}
		}
		BigDecimal limit = jdbc.queryForObject("select writeoff_limit from shop_settings where shop_id = ?",
				BigDecimal.class, TenantContext.requireShopId());
		Long approvedBy = null;
		if (loss.compareTo(limit) > 0) {
			approvedBy = approvals.require(approval, Permissions.STOCK_ADJUST,
					"Stock count loss of Rs " + loss.toPlainString(), "count loss above Rs " + limit.toPlainString());
		}
		DocRef doc = new DocRef("STOCK_COUNT", id, (String) count.get("number"));
		List<Move> moves = new ArrayList<>();
		for (CountLineView line : changed) {
			BigDecimal diff = new BigDecimal(line.difference());
			BigDecimal cost = jdbc.queryForObject("select unit_cost from stock_count_line where id = ?",
					BigDecimal.class, line.id());
			moves.add(new Move(line.productId(), branchId, line.batchId(), null, diff, cost,
					"Counted " + line.countedQty()));
		}
		List<Move> outs = moves.stream().filter(m -> m.qty().signum() < 0).toList();
		List<Move> ins = moves.stream().filter(m -> m.qty().signum() > 0).toList();
		stock.apply(MoveType.COUNT, doc, outs);
		stock.apply(MoveType.COUNT, doc, ins);
		jdbc.update("""
				update stock_count set status = 'POSTED', posted_by = ?, approved_by = ?, posted_at = now(),
				       version = version + 1 where id = ?""", StockAdjustments.userId(), approvedBy, id);
		audit.record("STOCK_COUNT_POSTED", "stock_count", id, null,
				Map.of("number", count.get("number"), "changed", changed.size(), "loss", loss.toPlainString()));
		return get(id);
	}

	@Transactional
	public CountView cancel(long id) {
		requireOpen(id);
		jdbc.update("update stock_count set status = 'CANCELLED', version = version + 1 where id = ?", id);
		audit.record("STOCK_COUNT_CANCELLED", "stock_count", id, null, null);
		return get(id);
	}

	@Transactional(readOnly = true)
	public CountView get(long id) {
		Map<String, Object> count = row(id);
		long branchId = ((Number) count.get("branch_id")).longValue();
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		boolean open = "OPEN".equals(count.get("status"));
		List<CountLineView> lines = lines(id, branchId, (Timestamp) count.get("started_at"), open);
		BigDecimal loss = BigDecimal.ZERO;
		BigDecimal gain = BigDecimal.ZERO;
		for (CountLineView l : lines) {
			if (l.differenceValue() != null) {
				BigDecimal v = new BigDecimal(l.differenceValue());
				if (v.signum() < 0) {
					loss = loss.add(v.abs());
				}
				else {
					gain = gain.add(v);
				}
			}
		}
		List<CountLineView> shown = cost ? lines
				: lines.stream()
					.map(l -> new CountLineView(l.id(), l.productId(), l.productName(), l.sku(), l.barcode(),
							l.batchId(), l.batchNo(), l.systemQty(), l.countedQty(), l.expectedQty(), l.difference(), null))
					.toList();
		return new CountView(id, (String) count.get("number"), branchId, (String) count.get("branch_name"),
				(String) count.get("scope"), (String) count.get("status"), (String) count.get("note"), lines.size(),
				(int) lines.stream().filter(l -> l.countedQty() != null).count(), cost ? Amounts.text(loss) : null,
				cost ? Amounts.text(gain) : null, ((Timestamp) count.get("started_at")).toInstant(),
				count.get("posted_at") == null ? null : ((Timestamp) count.get("posted_at")).toInstant(), shown);
	}

	@Transactional(readOnly = true)
	public PageResponse<CountListItem> list(Long branchId, String status, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("c.branch_id"));
		List<Object> args = new ArrayList<>();
		if (branchId != null) {
			where.append(" and c.branch_id = ?");
			args.add(branchId);
		}
		if (status != null && !status.isBlank()) {
			where.append(" and c.status = ?");
			args.add(status.trim().toUpperCase());
		}
		long total = jdbc.queryForObject("select count(*) from stock_count c" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<CountListItem> items = jdbc.query("""
				select c.*, b.name as branch_name,
				       (select count(*) from stock_count_line l where l.count_id = c.id) as n,
				       (select count(*) from stock_count_line l where l.count_id = c.id and l.counted_qty is not null) as k
				from stock_count c join branch b on b.id = c.branch_id""" + where
				+ " order by c.id desc limit ? offset ?",
				(rs, i) -> new CountListItem(rs.getLong("id"), rs.getString("number"), rs.getString("branch_name"),
						rs.getString("scope"), rs.getString("status"), rs.getInt("n"), rs.getInt("k"),
						rs.getTimestamp("started_at").toInstant(),
						rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant()),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	/**
	 * Lines with their expected quantity at the time they were counted. For posted counts the stored difference is
	 * recomputed the same way (movements are immutable, so the answer never changes).
	 */
	private List<CountLineView> lines(long countId, long branchId, Timestamp startedAt, boolean live) {
		return jdbc.query("""
				select l.*, p.name, p.sku, bt.batch_no,
				       (select min(barcode) from product_barcode pb where pb.product_id = p.id) as barcode,
				       l.system_qty + coalesce((select sum(m.qty) from stock_movement m
				           where m.product_id = l.product_id and m.branch_id = ? and coalesce(m.batch_id, 0) = coalesce(l.batch_id, 0)
				             and m.at > ? and m.at <= coalesce(l.counted_at, now())
				             and not (m.doc_type = 'STOCK_COUNT' and m.doc_id = l.count_id)), 0) as expected
				from stock_count_line l join product p on p.id = l.product_id left join batch bt on bt.id = l.batch_id
				where l.count_id = ? order by p.name, bt.expiry_date nulls last""", (rs, i) -> {
			BigDecimal counted = rs.getBigDecimal("counted_qty");
			BigDecimal expected = rs.getBigDecimal("expected");
			BigDecimal diff = counted == null ? null : counted.subtract(expected);
			BigDecimal cost = rs.getBigDecimal("unit_cost");
			return new CountLineView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
					rs.getString("sku"), rs.getString("barcode"), (Long) rs.getObject("batch_id"),
					rs.getString("batch_no"), Amounts.qtyText(rs.getBigDecimal("system_qty")), Amounts.qtyText(counted),
					Amounts.qtyText(expected), Amounts.qtyText(diff),
					diff == null || cost == null ? null : Amounts.text(diff.multiply(cost)));
		}, branchId, startedAt, countId);
	}

	private Map<String, Object> row(long id) {
		return jdbc.queryForList("""
				select c.*, b.name as branch_name from stock_count c join branch b on b.id = c.branch_id where c.id = ?"""
				+ branches.filter("c.branch_id"), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Stock count"));
	}

	private Map<String, Object> requireOpen(long id) {
		Map<String, Object> count = jdbc.queryForList("select * from stock_count where id = ? for update", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Stock count"));
		if (!branches.allowed(((Number) count.get("branch_id")).longValue())) {
			throw ApiException.notFound("Stock count");
		}
		if (!"OPEN".equals(count.get("status"))) {
			throw ApiException.rule("count_closed", "This stock count is " + count.get("status").toString().toLowerCase());
		}
		return count;
	}

	private static String trimTo(String value, int max) {
		return value.length() > max ? value.substring(0, max) : value;
	}
}
