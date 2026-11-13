package com.selectcode.pos.purchasing.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.catalog.app.ProductCatalog.UnitRef;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.docs.TableDoc;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders (PUR-02): ordered → partly received → received, or closed short / cancelled. Supplier prices are
 * hidden from users without {@code cost.view}.
 */
@Service
public class PurchaseOrders {

	public record PoLineInput(Long productId, Long unitId, String qty, String unitCost) {
	}

	public record PoInput(Long supplierId, Long branchId, LocalDate expectedOn, String note, List<PoLineInput> lines) {
	}

	public record PoLineView(long id, long productId, String productName, String sku, long unitId, String unitName,
			String unitFactor, String qtyOrdered, String qtyReceived, String qtyOpen, String unitCost, String amount) {
	}

	public record PoView(long id, String number, long supplierId, String supplierName, long branchId,
			String branchName, String status, LocalDate expectedOn, String note, String total, String actorName,
			LocalDate date, Instant at, String closedReason, List<PoLineView> lines) {
	}

	public record PoListItem(long id, String number, String supplierName, String branchName, String status,
			LocalDate expectedOn, String total, int lines, LocalDate date) {
	}

	private final JdbcTemplate jdbc;

	private final ProductCatalog catalog;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	PurchaseOrders(JdbcTemplate jdbc, ProductCatalog catalog, BranchAccess branches, ShopConfigService numbering,
			AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.branches = branches;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public PoView create(PoInput in) {
		if (in.supplierId() == null) {
			throw ApiException.field("supplierId", "required", "Choose a supplier");
		}
		Map<String, Object> supplier = jdbc.queryForList("select id, name, active from supplier where id = ?",
				in.supplierId()).stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier"));
		if (!(Boolean) supplier.get("active")) {
			throw ApiException.rule("supplier_inactive", supplier.get("name") + " is switched off");
		}
		long branchId = in.branchId() == null ? branches.defaultBranchId() : in.branchId();
		branches.requireActive(branchId);
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		String number = numbering.nextNumber("PURCHASE_ORDER", branchId);
		long id = jdbc.queryForObject("""
				insert into purchase_order (shop_id, number, supplier_id, branch_id, expected_on, note, created_by,
				                            actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), number,
				in.supplierId(), branchId, in.expectedOn() == null ? null : Date.valueOf(in.expectedOn()),
				SupplierService.trim(in.note(), 500), userId(), CurrentActor.name(), Date.valueOf(time.today()));
		BigDecimal total = BigDecimal.ZERO;
		for (int i = 0; i < in.lines().size(); i++) {
			PoLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (line.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(line.productId());
			if (!product.tracksStock()) {
				throw ApiException.rule("not_stocked", product.name() + " is not bought into stock");
			}
			UnitRef unit = catalog.unit(product, line.unitId());
			BigDecimal qty = Amounts.parseQty(line.qty(), f + ".qty");
			ProductCatalog.checkQty(product, unit, qty, f + ".qty");
			BigDecimal unitCost = cost ? Amounts.parseMoney(line.unitCost(), f + ".unitCost") : null;
			if (unitCost == null) {
				unitCost = lastCost(product.id(), unit.factor());
			}
			jdbc.update("""
					insert into po_line (shop_id, po_id, product_id, unit_id, unit_factor, qty_ordered, unit_cost)
					values (?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), id, product.id(), unit.unitId(),
					unit.factor(), qty, unitCost);
			total = total.add(Amounts.money(qty.multiply(unitCost)));
		}
		jdbc.update("update purchase_order set total = ? where id = ?", total, id);
		audit.record("PO_CREATED", "purchase_order", id, null, Map.of("number", number));
		return get(id);
	}

	/** Closes a PO that will not be received in full (e.g. 2 cartons short). */
	@Transactional
	public PoView close(long id, String reason) {
		Map<String, Object> po = lock(id);
		if (!List.of("ORDERED", "PARTIAL").contains(po.get("status"))) {
			throw ApiException.rule("po_closed", "This order is already " + po.get("status").toString().toLowerCase());
		}
		jdbc.update("update purchase_order set status = 'CLOSED', closed_reason = ? where id = ?",
				SupplierService.trim(reason, 300), id);
		audit.record("PO_CLOSED", "purchase_order", id, null, Map.of("reason", String.valueOf(reason)));
		return get(id);
	}

	@Transactional
	public PoView cancel(long id, String reason) {
		Map<String, Object> po = lock(id);
		if (!"ORDERED".equals(po.get("status"))) {
			throw ApiException.rule("po_received",
					"Only an order with nothing received can be cancelled; close it instead");
		}
		jdbc.update("update purchase_order set status = 'CANCELLED', closed_reason = ? where id = ?",
				SupplierService.trim(reason, 300), id);
		audit.record("PO_CANCELLED", "purchase_order", id, null, Map.of("reason", String.valueOf(reason)));
		return get(id);
	}

	@Transactional(readOnly = true)
	public PoView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> po = jdbc.queryForList("""
				select o.*, s.name as supplier_name, b.name as branch_name from purchase_order o
				join supplier s on s.id = o.supplier_id join branch b on b.id = o.branch_id where o.id = ?"""
				+ branches.filter("o.branch_id"), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Purchase order"));
		List<PoLineView> lines = jdbc.query("""
				select l.*, p.name, p.sku, u.name as unit_name from po_line l join product p on p.id = l.product_id
				join unit u on u.id = l.unit_id where l.po_id = ? order by l.id""", (rs, i) -> {
			BigDecimal ordered = rs.getBigDecimal("qty_ordered");
			BigDecimal received = rs.getBigDecimal("qty_received");
			BigDecimal open = ordered.subtract(received).max(BigDecimal.ZERO);
			return new PoLineView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"), rs.getString("sku"),
					rs.getLong("unit_id"), rs.getString("unit_name"), Amounts.qtyText(rs.getBigDecimal("unit_factor")),
					Amounts.qtyText(ordered), Amounts.qtyText(received), Amounts.qtyText(open),
					cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null,
					cost ? Amounts.text(ordered.multiply(rs.getBigDecimal("unit_cost"))) : null);
		}, id);
		return new PoView(id, (String) po.get("number"), ((Number) po.get("supplier_id")).longValue(),
				(String) po.get("supplier_name"), ((Number) po.get("branch_id")).longValue(),
				(String) po.get("branch_name"), (String) po.get("status"),
				po.get("expected_on") == null ? null : ((Date) po.get("expected_on")).toLocalDate(),
				(String) po.get("note"), cost ? Amounts.text((BigDecimal) po.get("total")) : null,
				(String) po.get("actor_name"), ((Date) po.get("business_date")).toLocalDate(),
				((Timestamp) po.get("at")).toInstant(), (String) po.get("closed_reason"), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<PoListItem> list(Long supplierId, String status, Long branchId, int page, int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("o.branch_id"));
		List<Object> args = new ArrayList<>();
		if (supplierId != null) {
			where.append(" and o.supplier_id = ?");
			args.add(supplierId);
		}
		if ("OPEN".equalsIgnoreCase(status)) {
			where.append(" and o.status in ('ORDERED', 'PARTIAL')");
		}
		else if (status != null && !status.isBlank()) {
			where.append(" and o.status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (branchId != null) {
			where.append(" and o.branch_id = ?");
			args.add(branchId);
		}
		long total = jdbc.queryForObject("select count(*) from purchase_order o" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<PoListItem> items = jdbc.query("""
				select o.*, s.name as supplier_name, b.name as branch_name,
				       (select count(*) from po_line l where l.po_id = o.id) as n
				from purchase_order o join supplier s on s.id = o.supplier_id join branch b on b.id = o.branch_id"""
				+ where + " order by o.id desc limit ? offset ?",
				(rs, i) -> new PoListItem(rs.getLong("id"), rs.getString("number"), rs.getString("supplier_name"),
						rs.getString("branch_name"), rs.getString("status"),
						rs.getObject("expected_on", LocalDate.class),
						cost ? Amounts.text(rs.getBigDecimal("total")) : null, rs.getInt("n"),
						rs.getObject("business_date", LocalDate.class)),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	/** The PO as a printable document (PDF / WhatsApp to the supplier). */
	public TableDoc document(PoView po, String shopName) {
		TableDoc.Builder doc = TableDoc.builder("Purchase order " + po.number())
			.heading(shopName)
			.heading("To: " + po.supplierName())
			.heading("Deliver to: " + po.branchName() + (po.expectedOn() == null ? "" : " · Expected " + po.expectedOn()))
			.heading("Date: " + po.date())
			.column("product", "Item", "TEXT")
			.column("sku", "SKU", "TEXT")
			.column("unit", "Unit", "TEXT")
			.column("qty", "Qty", "QTY")
			.column("rate", "Rate", "MONEY")
			.column("amount", "Amount", "MONEY");
		for (PoLineView l : po.lines()) {
			doc.row(Arrays.asList(l.productName(), l.sku(), l.unitName(), l.qtyOrdered(), l.unitCost(), l.amount()));
		}
		if (po.total() != null) {
			doc.totals(Arrays.asList("Total", null, null, null, null, po.total()));
		}
		if (po.note() != null) {
			doc.footer("Note: " + po.note());
		}
		return doc.build();
	}

	/** Last purchase cost of a product for this unit (or the average cost). */
	BigDecimal lastCost(long productId, BigDecimal factor) {
		List<BigDecimal> last = jdbc.queryForList("""
				select base_unit_cost from grn_line where product_id = ? order by id desc limit 1""", BigDecimal.class,
				productId);
		BigDecimal base = last.isEmpty()
				? jdbc.queryForObject("select cost_avg from product where id = ?", BigDecimal.class, productId)
				: last.getFirst();
		return Amounts.money(base.multiply(factor));
	}

	Map<String, Object> lock(long id) {
		Map<String, Object> po = jdbc.queryForList("select * from purchase_order where id = ? for update", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Purchase order"));
		if (!branches.allowed(((Number) po.get("branch_id")).longValue())) {
			throw ApiException.notFound("Purchase order");
		}
		return po;
	}

	static Long userId() {
		return CurrentActor.type() == ActorType.USER ? CurrentActor.id() : null;
	}
}
