package com.selectcode.pos.stock.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock on hand with cost and retail value (STK-01), stock history (STK-05), batches and IMEI search (MOB-02). Cost
 * values are null for users without {@code cost.view}.
 */
@Service
public class StockQueries {

	public record StockRow(long productId, String sku, String name, String categoryName, String brandName,
			String unitName, String kind, String qty, String reorderLevel, String costAvg, String price,
			String costValue, String retailValue, String status) {
	}

	public record StockPage(List<StockRow> items, int page, int size, long total, String totalQty,
			String totalCostValue, String totalRetailValue) {
	}

	public record BranchStock(long branchId, String branchName, String qty, String inTransit) {
	}

	public record BatchStock(long batchId, String batchNo, LocalDate expiry, long branchId, String branchName,
			String qty, String cost, boolean expired) {
	}

	public record ProductStockView(long productId, String name, String kind, String total, List<BranchStock> branches,
			List<BatchStock> batches, long serialsInStock) {
	}

	public record MovementRow(long id, Instant at, long productId, String productName, long branchId,
			String branchName, String batchNo, String serial, String type, String qty, String balanceAfter,
			String unitCost, String docType, long docId, String docNumber, String actorName, String note) {
	}

	public record SerialEvent(Instant at, String type, String description, String docNumber, String branchName,
			String actorName) {
	}

	public record SerialView(long id, String serial, String serial2, long productId, String productName,
			String colour, String storage, String condition, String ptaStatus, String status, long branchId,
			String branchName, String cost, String source, String supplierName, String purchaseNumber, Long saleId,
			String saleNumber, String customerName, Instant soldAt, LocalDate warrantyUntil, boolean underWarranty,
			List<SerialEvent> history) {
	}

	public record SerialListItem(long id, String serial, String serial2, String productName, String colour,
			String storage, String condition, String ptaStatus, String status, String branchName, String cost) {
	}

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	private final ShopTime time;

	StockQueries(JdbcTemplate jdbc, BranchAccess branches, ShopTime time) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.time = time;
	}

	/**
	 * Stock on hand per product for one branch (or all allowed branches), with cost and retail value.
	 * @param filter all, low (at or below reorder level), out (zero or below), negative
	 */
	@Transactional(readOnly = true)
	public StockPage stock(Long branchId, String q, Long categoryId, Long brandId, Long supplierId, String filter,
			int page, int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		if (branchId != null) {
			branches.require(branchId);
		}
		List<Object> args = new ArrayList<>();
		String branchCond = branchId != null ? " and s.branch_id = " + branchId : branches.filter("s.branch_id");
		StringBuilder where = new StringBuilder(" where p.active and p.kind not in ('SERVICE', 'BUNDLE', 'VARIANT_PARENT')");
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(p.name) like ? or lower(p.sku) like ? or exists (select 1 from product_barcode b "
					+ "where b.product_id = p.id and b.barcode = ?))");
			String like = "%" + q.trim().toLowerCase() + "%";
			args.add(like);
			args.add(like);
			args.add(q.trim());
		}
		if (categoryId != null) {
			where.append(" and p.category_id in (with recursive c(id) as (select ?::bigint union all "
					+ "select x.id from category x join c on x.parent_id = c.id) select id from c)");
			args.add(categoryId);
		}
		if (brandId != null) {
			where.append(" and p.brand_id = ?");
			args.add(brandId);
		}
		if (supplierId != null) {
			where.append(" and p.supplier_id = ?");
			args.add(supplierId);
		}
		String base = """
				from product p left join category c on c.id = p.category_id left join brand br on br.id = p.brand_id
				join unit u on u.id = p.unit_id
				left join lateral (select coalesce(sum(s.qty), 0) as qty from product_stock s where s.product_id = p.id"""
				+ branchCond + ") st on true" + where;
		String having = switch (filter == null ? "all" : filter.toLowerCase()) {
			case "low" -> " and st.qty <= p.reorder_level and p.reorder_level > 0";
			case "out" -> " and st.qty <= 0";
			case "negative" -> " and st.qty < 0";
			case "instock" -> " and st.qty > 0";
			default -> "";
		};
		Map<String, Object> totals = jdbc.queryForMap("select count(*) as n, coalesce(sum(st.qty), 0) as q, "
				+ "coalesce(sum(st.qty * p.cost_avg), 0) as cv, coalesce(sum(st.qty * p.price), 0) as rv " + base
				+ having, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<StockRow> rows = jdbc.query("select p.*, c.name as cat, br.name as brand, u.name as unit, st.qty as on_hand "
				+ base + having + " order by p.name limit ? offset ?", (rs, i) -> {
			BigDecimal qty = rs.getBigDecimal("on_hand");
			BigDecimal reorder = rs.getBigDecimal("reorder_level");
			String status = qty.signum() <= 0 ? "OUT" : reorder.signum() > 0 && qty.compareTo(reorder) <= 0 ? "LOW" : "OK";
			return new StockRow(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("cat"),
					rs.getString("brand"), rs.getString("unit"), rs.getString("kind"), Amounts.qtyText(qty),
					Amounts.qtyText(reorder), cost ? Amounts.text(rs.getBigDecimal("cost_avg")) : null,
					Amounts.text(rs.getBigDecimal("price")),
					cost ? Amounts.text(qty.multiply(rs.getBigDecimal("cost_avg"))) : null,
					Amounts.text(qty.multiply(rs.getBigDecimal("price"))), status);
		}, pageArgs.toArray());
		return new StockPage(rows, page, size, ((Number) totals.get("n")).longValue(),
				Amounts.qtyText((BigDecimal) totals.get("q")), cost ? Amounts.text((BigDecimal) totals.get("cv")) : null,
				Amounts.text((BigDecimal) totals.get("rv")));
	}

	/** One product's stock per branch, per batch and in transit. */
	@Transactional(readOnly = true)
	public ProductStockView product(long productId) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> p = jdbc.queryForList("select id, name, kind from product where id = ?", productId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Product"));
		String filter = branches.filter("b.id");
		List<BranchStock> perBranch = jdbc.query("""
				select b.id, b.name,
				       coalesce((select sum(qty) from product_stock s where s.product_id = ? and s.branch_id = b.id), 0) as qty,
				       coalesce((select sum(l.qty_sent) from transfer_line l join transfer t on t.id = l.transfer_id
				                 where t.status = 'DISPATCHED' and t.to_branch_id = b.id and l.product_id = ?), 0) as transit
				from branch b where b.active""" + filter + " order by b.is_default desc, b.name",
				(rs, i) -> new BranchStock(rs.getLong("id"), rs.getString("name"), Amounts.qtyText(rs.getBigDecimal("qty")),
						Amounts.qtyText(rs.getBigDecimal("transit"))),
				productId, productId);
		LocalDate today = time.today();
		List<BatchStock> batches = jdbc.query("""
				select bt.id, bt.batch_no, bt.expiry_date, bt.cost, b.id as branch_id, b.name as branch_name, s.qty
				from product_stock s join batch bt on bt.id = s.batch_id join branch b on b.id = s.branch_id
				where s.product_id = ? and s.qty <> 0""" + filter + " order by bt.expiry_date nulls last, bt.batch_no",
				(rs, i) -> {
					LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
					return new BatchStock(rs.getLong("id"), rs.getString("batch_no"), expiry, rs.getLong("branch_id"),
							rs.getString("branch_name"), Amounts.qtyText(rs.getBigDecimal("qty")),
							cost ? Amounts.text(rs.getBigDecimal("cost")) : null, expiry != null && expiry.isBefore(today));
				}, productId);
		BigDecimal total = perBranch.stream().map(b -> new BigDecimal(b.qty())).reduce(BigDecimal.ZERO, BigDecimal::add);
		Long serials = jdbc.queryForObject(
				"select count(*) from serial_unit where product_id = ? and status = 'IN_STOCK'", Long.class, productId);
		return new ProductStockView(productId, (String) p.get("name"), (String) p.get("kind"), Amounts.qtyText(total),
				perBranch, batches, serials == null ? 0 : serials);
	}

	/** Stock history (STK-05): every movement with type, branch, balance after, user and document. */
	@Transactional(readOnly = true)
	public PageResponse<MovementRow> movements(Long productId, Long branchId, String type, LocalDate from,
			LocalDate to, int page, int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("m.branch_id"));
		List<Object> args = new ArrayList<>();
		if (productId != null) {
			where.append(" and m.product_id = ?");
			args.add(productId);
		}
		if (branchId != null) {
			where.append(" and m.branch_id = ?");
			args.add(branchId);
		}
		if (type != null && !type.isBlank()) {
			where.append(" and m.move_type = ?");
			args.add(type.trim().toUpperCase());
		}
		if (from != null) {
			where.append(" and m.at >= ?");
			args.add(java.sql.Timestamp.from(ShopTime.startOf(from)));
		}
		if (to != null) {
			where.append(" and m.at < ?");
			args.add(java.sql.Timestamp.from(ShopTime.startOf(to.plusDays(1))));
		}
		long total = jdbc.queryForObject("select count(*) from stock_movement m" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<MovementRow> rows = jdbc.query("""
				select m.*, p.name as product_name, b.name as branch_name, bt.batch_no, s.serial
				from stock_movement m join product p on p.id = m.product_id join branch b on b.id = m.branch_id
				left join batch bt on bt.id = m.batch_id left join serial_unit s on s.id = m.serial_id""" + where
				+ " order by m.at desc, m.id desc limit ? offset ?",
				(rs, i) -> new MovementRow(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getLong("product_id"),
						rs.getString("product_name"), rs.getLong("branch_id"), rs.getString("branch_name"),
						rs.getString("batch_no"), rs.getString("serial"), rs.getString("move_type"),
						Amounts.qtyText(rs.getBigDecimal("qty")), Amounts.qtyText(rs.getBigDecimal("balance_after")),
						cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null, rs.getString("doc_type"),
						rs.getLong("doc_id"), rs.getString("doc_number"), rs.getString("actor_name"), rs.getString("note")),
				pageArgs.toArray());
		return new PageResponse<>(rows, page, size, total);
	}

	/** Batches of a product (or all batches with stock), earliest expiry first. */
	@Transactional(readOnly = true)
	public List<BatchStock> batches(Long productId, Long branchId, boolean withStockOnly) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		LocalDate today = time.today();
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("s.branch_id"));
		List<Object> args = new ArrayList<>();
		if (productId != null) {
			where.append(" and bt.product_id = ?");
			args.add(productId);
		}
		if (branchId != null) {
			where.append(" and s.branch_id = ?");
			args.add(branchId);
		}
		if (withStockOnly) {
			where.append(" and s.qty > 0");
		}
		return jdbc.query("""
				select bt.id, bt.batch_no, bt.expiry_date, bt.cost, b.id as branch_id, b.name as branch_name, s.qty
				from batch bt join product_stock s on s.batch_id = bt.id join branch b on b.id = s.branch_id""" + where
				+ " order by bt.expiry_date nulls last, bt.batch_no limit 500", (rs, i) -> {
			LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
			return new BatchStock(rs.getLong("id"), rs.getString("batch_no"), expiry, rs.getLong("branch_id"),
					rs.getString("branch_name"), Amounts.qtyText(rs.getBigDecimal("qty")),
					cost ? Amounts.text(rs.getBigDecimal("cost")) : null, expiry != null && expiry.isBefore(today));
		}, args.toArray());
	}

	/** IMEI/serial search with full history (MOB-02): bought from whom, sold to whom, warranty, claims. */
	@Transactional(readOnly = true)
	public SerialView serial(String serial) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		String value = serial == null ? "" : serial.trim().toUpperCase();
		Map<String, Object> u = jdbc.queryForList("""
				select s.*, p.name as product_name, b.name as branch_name, sp.name as supplier_name, g.number as grn_number,
				       bb.number as buyback_number, sl.number as sale_number, c.name as customer_name
				from serial_unit s join product p on p.id = s.product_id join branch b on b.id = s.branch_id
				left join supplier sp on sp.id = s.supplier_id left join grn g on g.id = s.grn_id
				left join buyback bb on bb.id = s.buyback_id left join sale sl on sl.id = s.sale_id
				left join customer c on c.id = s.customer_id
				where upper(s.serial) = ? or upper(s.serial2) = ? order by (s.status = 'VOID'), s.id desc limit 1""",
				value, value).stream().findFirst().orElseThrow(() -> ApiException.notFound("IMEI/serial " + value));
		long id = ((Number) u.get("id")).longValue();
		List<SerialEvent> history = new ArrayList<>(jdbc.query("""
				select m.at, m.move_type, m.doc_number, b.name as branch_name, m.actor_name, m.note
				from stock_movement m join branch b on b.id = m.branch_id where m.serial_id = ? order by m.at, m.id""",
				(rs, i) -> new SerialEvent(rs.getTimestamp("at").toInstant(), rs.getString("move_type"),
						describe(rs.getString("move_type"), rs.getString("note")), rs.getString("doc_number"),
						rs.getString("branch_name"), rs.getString("actor_name")),
				id));
		history.addAll(jdbc.query("""
				select w.at, w.number, w.issue, w.status, w.actor_name from warranty_claim w where w.serial_unit_id = ?""",
				(rs, i) -> new SerialEvent(rs.getTimestamp("at").toInstant(), "WARRANTY_CLAIM",
						"Warranty claim: " + rs.getString("issue") + " (" + rs.getString("status").toLowerCase() + ")",
						rs.getString("number"), null, rs.getString("actor_name")),
				id));
		history.sort(java.util.Comparator.comparing(SerialEvent::at));
		LocalDate warranty = u.get("warranty_until") == null ? null : ((Date) u.get("warranty_until")).toLocalDate();
		boolean pii = CurrentActor.hasPermission(Permissions.CUSTOMERS_VIEW);
		return new SerialView(id, (String) u.get("serial"), (String) u.get("serial2"),
				((Number) u.get("product_id")).longValue(), (String) u.get("product_name"), (String) u.get("colour"),
				(String) u.get("storage"), (String) u.get("condition"), (String) u.get("pta_status"),
				(String) u.get("status"), ((Number) u.get("branch_id")).longValue(), (String) u.get("branch_name"),
				cost ? Amounts.text((BigDecimal) u.get("cost")) : null, (String) u.get("source"),
				cost || CurrentActor.hasPermission(Permissions.PURCHASING_VIEW) ? (String) u.get("supplier_name") : null,
				u.get("grn_number") != null ? (String) u.get("grn_number") : (String) u.get("buyback_number"),
				u.get("sale_id") == null ? null : ((Number) u.get("sale_id")).longValue(), (String) u.get("sale_number"),
				pii ? (String) u.get("customer_name") : null,
				u.get("sold_at") == null ? null : ((java.sql.Timestamp) u.get("sold_at")).toInstant(), warranty,
				warranty != null && !warranty.isBefore(time.today()), history);
	}

	/** IMEI stock list (mobile shop report): units with their details. */
	@Transactional(readOnly = true)
	public PageResponse<SerialListItem> serials(Long productId, Long branchId, String status, String q, int page,
			int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where s.status <> 'VOID'" + branches.filter("s.branch_id"));
		List<Object> args = new ArrayList<>();
		if (productId != null) {
			where.append(" and s.product_id = ?");
			args.add(productId);
		}
		if (branchId != null) {
			where.append(" and s.branch_id = ?");
			args.add(branchId);
		}
		// No status: in stock, unless searching (an IMEI search finds the phone whatever happened to it). ALL = any.
		boolean searching = q != null && !q.isBlank();
		String wanted = status == null || status.isBlank() ? (searching ? "ALL" : "IN_STOCK") : status.trim().toUpperCase();
		if (!"ALL".equals(wanted)) {
			where.append(" and s.status = ?");
			args.add(wanted);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (upper(s.serial) like ? or upper(s.serial2) like ? or lower(p.name) like ?)");
			args.add("%" + q.trim().toUpperCase() + "%");
			args.add("%" + q.trim().toUpperCase() + "%");
			args.add("%" + q.trim().toLowerCase() + "%");
		}
		String from = " from serial_unit s join product p on p.id = s.product_id join branch b on b.id = s.branch_id";
		long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<SerialListItem> items = jdbc.query("select s.*, p.name as product_name, b.name as branch_name" + from + where
				+ " order by p.name, s.serial limit ? offset ?",
				(rs, i) -> new SerialListItem(rs.getLong("id"), rs.getString("serial"), rs.getString("serial2"),
						rs.getString("product_name"), rs.getString("colour"), rs.getString("storage"),
						rs.getString("condition"), rs.getString("pta_status"), rs.getString("status"),
						rs.getString("branch_name"), cost ? Amounts.text(rs.getBigDecimal("cost")) : null),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	private static String describe(String type, String note) {
		String text = switch (type) {
			case "PURCHASE" -> "Bought from supplier";
			case "OPENING" -> "Opening stock";
			case "BUYBACK" -> "Bought used from a customer";
			case "SALE" -> "Sold";
			case "SALE_RETURN" -> "Returned by customer";
			case "PURCHASE_RETURN" -> "Returned to supplier";
			case "TRANSFER_OUT" -> "Sent to another branch";
			case "TRANSFER_IN" -> "Received from another branch";
			case "CANCEL" -> "Sale cancelled";
			case "ADJUST_OUT" -> "Written off";
			case "ADJUST_IN" -> "Added by adjustment";
			default -> type.toLowerCase().replace('_', ' ');
		};
		return note == null ? text : text + " (" + note + ")";
	}
}
