package com.selectcode.pos.purchasing.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
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
import com.selectcode.pos.stock.app.StockService;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns to the supplier (PUR-05): damaged, expired (expired batches may be returned) or wrong goods. Stock goes
 * out and the supplier's balance goes down by the return value; returning against a receipt uses that receipt's cost
 * and never more than was received.
 */
@Service
public class PurchaseReturns {

	public record PurchaseReturnLineInput(Long productId, Long grnLineId, String qty, String unitCost, Long batchId,
			List<String> serials) {
	}

	/** @param reason DAMAGED, EXPIRED, WRONG, EXCESS or OTHER */
	public record PurchaseReturnInput(Long supplierId, Long branchId, Long grnId, String reason, String note,
			List<PurchaseReturnLineInput> lines) {
	}

	public record PurchaseReturnLineView(long productId, String productName, String batchNo, String serial,
			String qty, String unitCost, String value) {
	}

	public record PurchaseReturnView(long id, String number, long supplierId, String supplierName, Long grnId,
			String grnNumber, long branchId, String reason, String note, String total, String actorName,
			LocalDate date, Instant at, List<PurchaseReturnLineView> lines) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final SupplierService suppliers;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final AuditService audit;

	private final ShopTime time;

	PurchaseReturns(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, SupplierService suppliers,
			BranchAccess branches, ShopConfigService numbering, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.suppliers = suppliers;
		this.branches = branches;
		this.numbering = numbering;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public PurchaseReturnView create(PurchaseReturnInput in) {
		if (in.supplierId() == null) {
			throw ApiException.field("supplierId", "required", "Choose a supplier");
		}
		suppliers.lock(in.supplierId());
		String reason = in.reason() == null ? "" : in.reason().trim().toUpperCase();
		if (!Set.of("DAMAGED", "EXPIRED", "WRONG", "EXCESS", "OTHER").contains(reason)) {
			throw ApiException.field("reason", "invalid", "Choose a reason: damaged, expired, wrong, excess or other");
		}
		Map<String, Object> grn = null;
		if (in.grnId() != null) {
			grn = jdbc.queryForList("select * from grn where id = ? for update", in.grnId())
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Goods received note"));
			if (((Number) grn.get("supplier_id")).longValue() != in.supplierId()) {
				throw ApiException.rule("grn_supplier", "This receipt is from another supplier");
			}
		}
		long branchId = in.branchId() != null ? in.branchId()
				: grn != null ? ((Number) grn.get("branch_id")).longValue() : branches.defaultBranchId();
		var branch = branches.requireActive(branchId);
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		boolean seesCost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		String number = numbering.nextNumber("PURCHASE_RETURN", branch.id());
		record Planned(ProductRef product, Long grnLineId, BigDecimal qty, BigDecimal unitCost, Long batchId,
				List<StockService.SerialUnit> units) {
		}
		List<Planned> planned = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (int i = 0; i < in.lines().size(); i++) {
			PurchaseReturnLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			Map<String, Object> grnLine = null;
			Long productId = line.productId();
			if (line.grnLineId() != null) {
				if (grn == null) {
					throw ApiException.field(f + ".grnLineId", "no_grn", "Choose the goods received note first");
				}
				grnLine = jdbc.queryForList("select * from grn_line where id = ? and grn_id = ? for update",
						line.grnLineId(), in.grnId()).stream().findFirst()
					.orElseThrow(() -> ApiException.field(f + ".grnLineId", "invalid", "This line is not on the receipt"));
				productId = ((Number) grnLine.get("product_id")).longValue();
			}
			if (productId == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(productId);
			List<StockService.SerialUnit> units = List.of();
			BigDecimal qty;
			if (product.serial()) {
				if (line.serials() == null || line.serials().isEmpty()) {
					throw ApiException.field(f + ".serials", "required", "Scan the IMEI/serial of each unit");
				}
				units = new ArrayList<>();
				for (String serial : line.serials()) {
					units.add(stock.lockSerial(serial, product.id(), branch.id(), "IN_STOCK"));
				}
				qty = BigDecimal.valueOf(units.size());
			}
			else {
				qty = Amounts.parseQty(line.qty(), f + ".qty");
				ProductCatalog.checkQty(product, catalog.unit(product, null), qty, f + ".qty");
			}
			Long batchId = line.batchId();
			if (grnLine != null && grnLine.get("batch_id") != null) {
				batchId = ((Number) grnLine.get("batch_id")).longValue();
			}
			if (product.batch() && batchId == null) {
				throw ApiException.field(f + ".batchId", "required", "Choose the batch");
			}
			if (!product.batch()) {
				batchId = null;
			}
			BigDecimal unitCost;
			if (grnLine != null) {
				BigDecimal received = (BigDecimal) grnLine.get("base_qty");
				BigDecimal returned = (BigDecimal) grnLine.get("qty_returned");
				if (returned.add(qty).compareTo(received) > 0) {
					throw ApiException.rule("already_returned", "Only " + received.subtract(returned).stripTrailingZeros()
							.toPlainString() + " of " + product.name() + " from this receipt can still be returned");
				}
				unitCost = (BigDecimal) grnLine.get("base_unit_cost");
				jdbc.update("update grn_line set qty_returned = qty_returned + ? where id = ?", qty, line.grnLineId());
			}
			else if (!units.isEmpty()) {
				unitCost = units.stream().map(StockService.SerialUnit::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
					.divide(qty, 2, java.math.RoundingMode.HALF_UP);
			}
			else {
				BigDecimal given = seesCost ? Amounts.parseMoney(line.unitCost(), f + ".unitCost") : null;
				unitCost = given != null ? given : product.costAvg();
			}
			total = total.add(Amounts.money(qty.multiply(unitCost)));
			planned.add(new Planned(product, line.grnLineId(), qty, unitCost, batchId, units));
		}
		LocalDate today = time.today();
		long id = jdbc.queryForObject("""
				insert into purchase_return (shop_id, number, supplier_id, grn_id, branch_id, reason, note, total,
				                             created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), number,
				in.supplierId(), in.grnId(), branch.id(), reason, SupplierService.trim(in.note(), 500), total,
				PurchaseOrders.userId(), CurrentActor.name(), Date.valueOf(today));
		DocRef doc = new DocRef("PURCHASE_RETURN", id, number);
		List<Move> moves = new ArrayList<>();
		for (Planned p : planned) {
			if (!p.units().isEmpty()) {
				for (StockService.SerialUnit unit : p.units()) {
					stock.setSerialStatus(unit.id(), "RETURNED_TO_SUPPLIER", null);
					moves.add(new Move(p.product().id(), branch.id(), null, unit.id(), BigDecimal.ONE.negate(),
							unit.cost(), reason));
					insertLine(id, p.grnLineId(), p.product().id(), null, unit.id(), BigDecimal.ONE, unit.cost());
				}
			}
			else {
				moves.add(new Move(p.product().id(), branch.id(), p.batchId(), null, p.qty().negate(), p.unitCost(),
						reason));
				insertLine(id, p.grnLineId(), p.product().id(), p.batchId(), null, p.qty(), p.unitCost());
			}
		}
		stock.apply(MoveType.PURCHASE_RETURN, doc, moves);
		if (grn != null) {
			BigDecimal due = (BigDecimal) grn.get("due");
			BigDecimal applied = total.min(due);
			if (applied.signum() > 0) {
				jdbc.update("update grn set returned = returned + ? where id = ?", applied, in.grnId());
			}
		}
		suppliers.post(in.supplierId(), "PURCHASE_RETURN", id, number, "Returned to supplier " + number, total, null);
		audit.record("PURCHASE_RETURNED", "purchase_return", id, null,
				Map.of("number", number, "reason", reason, "total", total.toPlainString()));
		return get(id);
	}

	@Transactional(readOnly = true)
	public PurchaseReturnView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> r = jdbc.queryForList("""
				select r.*, s.name as supplier_name, g.number as grn_number from purchase_return r
				join supplier s on s.id = r.supplier_id left join grn g on g.id = r.grn_id where r.id = ?"""
				+ branches.filter("r.branch_id"), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Purchase return"));
		List<PurchaseReturnLineView> lines = jdbc.query("""
				select l.*, p.name, b.batch_no, s.serial from purchase_return_line l join product p on p.id = l.product_id
				left join batch b on b.id = l.batch_id left join serial_unit s on s.id = l.serial_id
				where l.return_id = ? order by l.id""",
				(rs, i) -> new PurchaseReturnLineView(rs.getLong("product_id"), rs.getString("name"),
						rs.getString("batch_no"), rs.getString("serial"), Amounts.qtyText(rs.getBigDecimal("qty")),
						cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null,
						cost ? Amounts.text(rs.getBigDecimal("value")) : null),
				id);
		return new PurchaseReturnView(id, (String) r.get("number"), ((Number) r.get("supplier_id")).longValue(),
				(String) r.get("supplier_name"), r.get("grn_id") == null ? null : ((Number) r.get("grn_id")).longValue(),
				(String) r.get("grn_number"), ((Number) r.get("branch_id")).longValue(), (String) r.get("reason"),
				(String) r.get("note"), cost ? Amounts.text((BigDecimal) r.get("total")) : null,
				(String) r.get("actor_name"), ((Date) r.get("business_date")).toLocalDate(),
				((Timestamp) r.get("at")).toInstant(), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<PurchaseReturnView> list(Long supplierId, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (supplierId != null) {
			where.append(" and supplier_id = ?");
			args.add(supplierId);
		}
		long total = jdbc.queryForObject("select count(*) from purchase_return" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from purchase_return" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	private void insertLine(long returnId, Long grnLineId, long productId, Long batchId, Long serialId, BigDecimal qty,
			BigDecimal unitCost) {
		jdbc.update("""
				insert into purchase_return_line (shop_id, return_id, grn_line_id, product_id, batch_id, serial_id, qty,
				                                  unit_cost, value)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), returnId, grnLineId, productId,
				batchId, serialId, Amounts.qty(qty), Amounts.money(unitCost), Amounts.money(qty.multiply(unitCost)));
	}
}
