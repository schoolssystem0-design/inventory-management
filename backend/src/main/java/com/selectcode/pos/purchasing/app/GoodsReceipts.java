package com.selectcode.pos.purchasing.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.catalog.app.ProductCatalog.UnitRef;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.Idempotency;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import com.selectcode.pos.stock.app.StockService.NewSerial;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods received notes (PUR-03, PUR-04, MED-02, MED-10, MOB-01): receive all or part of a PO, or without one; the
 * supplier's bill number; bonus (free) items; batches with expiry; IMEIs scanned per unit. Each line updates the
 * weighted average cost before its stock comes in; bonus units lower the unit cost (10+1). The bill is credited to the
 * supplier's account, and anything paid on the spot is debited.
 */
@Service
public class GoodsReceipts {

	public record GrnLineInput(Long productId, Long poLineId, Long unitId, String qty, String bonusQty,
			String unitCost, String discount, String batchNo, LocalDate expiry, LocalDate mfgDate, String tradePrice,
			String retailPrice, List<NewSerial> serials) {
	}

	/** @param paidNow amount paid to the supplier on delivery (optional), with {@code paidMethod} */
	public record GrnInput(Long supplierId, Long branchId, Long poId, String supplierBillNo, LocalDate billDate,
			String discount, String freight, String tax, String note, String paidNow, String paidMethod,
			String paidReference, List<GrnLineInput> lines) {
	}

	public record GrnLineView(long id, long productId, String productName, String unitName, String qty,
			String bonusQty, String unitCost, String discount, String net, String baseQty, String baseUnitCost,
			String batchNo, LocalDate expiry, String qtyReturned, List<String> serials) {
	}

	public record GrnView(long id, String number, long supplierId, String supplierName, long branchId,
			String branchName, Long poId, String poNumber, String supplierBillNo, LocalDate billDate, String subtotal,
			String discount, String freight, String tax, String total, String returned, String paid, String due,
			LocalDate dueOn, String note, String actorName, LocalDate date, Instant at, List<GrnLineView> lines) {
	}

	public record GrnListItem(long id, String number, String supplierName, String branchName, String supplierBillNo,
			String total, String due, LocalDate dueOn, LocalDate date) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final SupplierService suppliers;

	private final PurchaseOrders orders;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final Idempotency idempotency;

	private final ShopFeatures features;

	private final AuditService audit;

	private final ShopTime time;

	GoodsReceipts(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, SupplierService suppliers,
			PurchaseOrders orders, BranchAccess branches, ShopConfigService numbering, Idempotency idempotency,
			ShopFeatures features, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.suppliers = suppliers;
		this.orders = orders;
		this.branches = branches;
		this.numbering = numbering;
		this.idempotency = idempotency;
		this.features = features;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public GrnView receive(GrnInput in, String idempotencyKey) {
		var earlier = idempotency.claim("GRN", idempotencyKey);
		if (earlier.isPresent()) {
			return get(earlier.get());
		}
		// Without cost.view (stock keepers) prices come from the purchase order or the last purchase, never from input.
		boolean seesCost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		if (in.supplierId() == null) {
			throw ApiException.field("supplierId", "required", "Choose a supplier");
		}
		Map<String, Object> supplier = suppliers.lock(in.supplierId());
		if (!(Boolean) supplier.get("active")) {
			throw ApiException.rule("supplier_inactive", supplier.get("name") + " is switched off");
		}
		Map<String, Object> po = null;
		long branchId;
		if (in.poId() != null) {
			po = orders.lock(in.poId());
			if (((Number) po.get("supplier_id")).longValue() != in.supplierId()) {
				throw ApiException.rule("po_supplier", "This purchase order is for another supplier");
			}
			if (!Set.of("ORDERED", "PARTIAL").contains(po.get("status"))) {
				throw ApiException.rule("po_closed", "This order is " + po.get("status").toString().toLowerCase());
			}
			branchId = in.branchId() == null ? ((Number) po.get("branch_id")).longValue() : in.branchId();
		}
		else {
			branchId = in.branchId() == null ? branches.defaultBranchId() : in.branchId();
		}
		var branch = branches.requireActive(branchId);
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		LocalDate today = time.today();

		record Planned(int index, ProductRef product, UnitRef unit, Long poLineId, BigDecimal qty, BigDecimal bonus,
				BigDecimal unitCost, BigDecimal discount, BigDecimal net, BigDecimal baseQty, GrnLineInput input) {
		}
		List<Planned> planned = new ArrayList<>();
		for (int i = 0; i < in.lines().size(); i++) {
			GrnLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (line.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(line.productId());
			if (!product.tracksStock()) {
				throw ApiException.rule("not_stocked", product.name() + " is not bought into stock");
			}
			UnitRef unit = catalog.unit(product, line.unitId());
			BigDecimal bonus = Amounts.nz(Amounts.parseQty(line.bonusQty(), f + ".bonusQty"));
			BigDecimal qty;
			if (product.serial()) {
				int units = line.serials() == null ? 0 : line.serials().size();
				if (units == 0) {
					throw ApiException.field(f + ".serials", "required", "Scan the IMEI/serial of each unit");
				}
				if (unit.factor().compareTo(BigDecimal.ONE) != 0) {
					throw ApiException.rule("serial_pack", "IMEI/serial items are received one unit at a time");
				}
				qty = BigDecimal.valueOf(units).subtract(bonus);
				if (qty.signum() < 0) {
					throw ApiException.field(f + ".bonusQty", "too_many", "More bonus units than IMEIs scanned");
				}
			}
			else {
				qty = Amounts.nz(Amounts.parseQty(line.qty(), f + ".qty"));
				if (qty.add(bonus).signum() <= 0) {
					throw ApiException.field(f + ".qty", "required", "Enter the quantity received");
				}
				if (qty.signum() > 0) {
					ProductCatalog.checkQty(product, unit, qty, f + ".qty");
				}
				if (bonus.signum() > 0) {
					ProductCatalog.checkQty(product, unit, bonus, f + ".bonusQty");
				}
			}
			BigDecimal unitCost;
			BigDecimal discount;
			if (seesCost) {
				unitCost = Amounts.requireMoney(line.unitCost(), f + ".unitCost");
				discount = Amounts.nz(Amounts.parseMoney(line.discount(), f + ".discount"));
			}
			else {
				List<BigDecimal> fromPo = po == null ? List.of()
						: jdbc.queryForList("""
								select unit_cost / unit_factor * ? from po_line where po_id = ? and product_id = ?
								and (cast(? as bigint) is null or id = ?) order by id limit 1""", BigDecimal.class, unit.factor(),
								in.poId(), product.id(), line.poLineId(), line.poLineId());
				unitCost = fromPo.isEmpty() ? orders.lastCost(product.id(), unit.factor()) : Amounts.money(fromPo.getFirst());
				discount = BigDecimal.ZERO.setScale(2);
			}
			BigDecimal gross = Amounts.money(qty.multiply(unitCost));
			if (discount.compareTo(gross) > 0) {
				throw ApiException.field(f + ".discount", "too_large", "Discount is more than the line amount");
			}
			if (line.poLineId() != null) {
				if (po == null) {
					throw ApiException.field(f + ".poLineId", "no_po", "Choose the purchase order first");
				}
				Integer ok = jdbc.queryForObject("select count(*) from po_line where id = ? and po_id = ? and product_id = ?",
						Integer.class, line.poLineId(), in.poId(), product.id());
				if (ok == null || ok == 0) {
					throw ApiException.field(f + ".poLineId", "invalid", "This line is not on the purchase order");
				}
			}
			BigDecimal baseQty = Amounts.qty(qty.add(bonus).multiply(unit.factor()));
			planned.add(new Planned(i, product, unit, line.poLineId(), qty, bonus, unitCost, discount,
					gross.subtract(discount), baseQty, line));
		}

		BigDecimal subtotal = planned.stream().map(Planned::net).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal billDiscount = seesCost ? Amounts.nz(Amounts.parseMoney(in.discount(), "discount"))
				: BigDecimal.ZERO.setScale(2);
		if (billDiscount.compareTo(subtotal) > 0) {
			throw ApiException.field("discount", "too_large", "Discount is more than the bill");
		}
		BigDecimal freight = seesCost ? Amounts.nz(Amounts.parseMoney(in.freight(), "freight")) : BigDecimal.ZERO.setScale(2);
		BigDecimal tax = seesCost ? Amounts.nz(Amounts.parseMoney(in.tax(), "tax")) : BigDecimal.ZERO.setScale(2);
		BigDecimal total = subtotal.subtract(billDiscount).add(freight).add(tax);
		List<BigDecimal> shares = SaleMath.spread(planned.stream().map(Planned::net).toList(), billDiscount);

		int termsDays = ((Number) supplier.get("terms_days")).intValue();
		LocalDate billDate = in.billDate() == null ? today : in.billDate();
		String number = numbering.nextNumber("GRN", branch.id());
		long id = jdbc.queryForObject("""
				insert into grn (shop_id, number, supplier_id, po_id, branch_id, supplier_bill_no, bill_date, subtotal,
				                 discount, freight, tax, total, due_on, note, created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, in.supplierId(), in.poId(), branch.id(),
				SupplierService.trim(in.supplierBillNo(), 60), Date.valueOf(billDate), subtotal, billDiscount, freight,
				tax, total, Date.valueOf(billDate.plusDays(termsDays)), SupplierService.trim(in.note(), 500),
				PurchaseOrders.userId(), CurrentActor.name(), Date.valueOf(today));
		DocRef doc = new DocRef("GRN", id, number);

		// Lines in product order: average cost locks the product row, so a fixed order avoids deadlocks.
		List<Planned> ordered = new ArrayList<>(planned);
		ordered.sort(Comparator.comparingLong((Planned p) -> p.product().id()).thenComparingInt(Planned::index));
		List<Move> moves = new ArrayList<>();
		for (Planned p : ordered) {
			BigDecimal landedNet = p.net().subtract(shares.get(p.index()));
			BigDecimal baseUnitCost = landedNet.divide(p.baseQty(), 2, RoundingMode.HALF_UP);
			Long batchId = null;
			if (p.product().batch()) {
				if (p.input().expiry() == null && features.isOn(ModuleKey.BATCHES)) {
					throw ApiException.field("lines[" + p.index() + "].expiry", "required", "Enter the expiry date");
				}
				batchId = stock.batch(p.product().id(), p.input().batchNo(), p.input().expiry(), p.input().mfgDate(),
						baseUnitCost, Amounts.parseMoney(p.input().tradePrice(), "tradePrice"),
						Amounts.parseMoney(p.input().retailPrice(), "retailPrice"));
			}
			long lineId = jdbc.queryForObject("""
					insert into grn_line (shop_id, grn_id, po_line_id, product_id, unit_id, unit_factor, qty, bonus_qty,
					                      unit_cost, discount, net, base_qty, base_unit_cost, batch_id)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
					TenantContext.requireShopId(), id, p.poLineId(), p.product().id(), p.unit().unitId(),
					p.unit().factor(), p.qty(), p.bonus(), p.unitCost(), p.discount(), p.net(), p.baseQty(),
					baseUnitCost, batchId);
			stock.averageIn(p.product().id(), p.baseQty(), baseUnitCost);
			if (p.product().serial()) {
				List<Long> serialIds = stock.createSerials(p.product().id(), branch.id(), p.input().serials(),
						baseUnitCost, "PURCHASE", in.supplierId(), id, null);
				for (Long serialId : serialIds) {
					moves.add(new Move(p.product().id(), branch.id(), null, serialId, BigDecimal.ONE, baseUnitCost,
							null));
				}
			}
			else {
				moves.add(new Move(p.product().id(), branch.id(), batchId, null, p.baseQty(), baseUnitCost, null));
			}
			if (p.poLineId() != null) {
				jdbc.update("update po_line set qty_received = qty_received + ? where id = ?", p.qty(), p.poLineId());
			}
			else if (po != null) {
				// Matched by product when the line did not name its PO line.
				jdbc.update("""
						update po_line set qty_received = qty_received + ?
						where id = (select id from po_line where po_id = ? and product_id = ? order by id limit 1)""",
						p.qty(), in.poId(), p.product().id());
			}
			jdbc.update("update product set supplier_id = coalesce(supplier_id, ?) where id = ?", in.supplierId(),
					p.product().id());
		}
		stock.apply(MoveType.PURCHASE, doc, moves);

		if (po != null) {
			Map<String, Object> state = jdbc.queryForMap("""
					select bool_and(qty_received >= qty_ordered) as all_in, bool_or(qty_received > 0) as any_in
					from po_line where po_id = ?""", in.poId());
			String status = Boolean.TRUE.equals(state.get("all_in")) ? "RECEIVED"
					: Boolean.TRUE.equals(state.get("any_in")) ? "PARTIAL" : "ORDERED";
			jdbc.update("update purchase_order set status = ? where id = ?", status, in.poId());
		}
		String description = "Goods received " + number
				+ (in.supplierBillNo() == null || in.supplierBillNo().isBlank() ? "" : " (bill " + in.supplierBillNo().trim() + ")");
		suppliers.post(in.supplierId(), "GRN", id, number, description, null, total);

		BigDecimal paidNow = seesCost ? Amounts.nz(Amounts.parseMoney(in.paidNow(), "paidNow")) : BigDecimal.ZERO;
		if (paidNow.signum() > 0) {
			if (paidNow.compareTo(total) > 0) {
				throw ApiException.field("paidNow", "too_large", "Paid more than the bill; record the rest as a payment");
			}
			String method = in.paidMethod() == null || in.paidMethod().isBlank() ? "CASH" : in.paidMethod().trim().toUpperCase();
			SupplierPayments.checkMethod(jdbc, method);
			String payNo = numbering.nextNumber("SUPPLIER_PAYMENT", branch.id());
			long payId = jdbc.queryForObject("""
					insert into supplier_payment (shop_id, number, supplier_id, branch_id, amount, method_key, reference,
					                              note, created_by, actor_name, business_date)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(),
					payNo, in.supplierId(), branch.id(), paidNow, method, SupplierService.trim(in.paidReference(), 100),
					"Paid on delivery of " + number, PurchaseOrders.userId(), CurrentActor.name(), Date.valueOf(today));
			jdbc.update("""
					insert into supplier_payment_allocation (shop_id, payment_id, grn_id, amount) values (?, ?, ?, ?)""",
					TenantContext.requireShopId(), payId, id, paidNow);
			jdbc.update("update grn set paid = paid + ? where id = ?", paidNow, id);
			suppliers.post(in.supplierId(), "SUPPLIER_PAYMENT", payId, payNo, "Paid on delivery of " + number, paidNow,
					null);
		}
		idempotency.complete("GRN", idempotencyKey, id);
		audit.record("GRN_POSTED", "grn", id, null, Map.of("number", number, "total", total.toPlainString()));
		return get(id);
	}

	@Transactional(readOnly = true)
	public GrnView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> g = jdbc.queryForList("""
				select g.*, s.name as supplier_name, b.name as branch_name, o.number as po_number from grn g
				join supplier s on s.id = g.supplier_id join branch b on b.id = g.branch_id
				left join purchase_order o on o.id = g.po_id where g.id = ?""" + branches.filter("g.branch_id"), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Goods received note"));
		List<GrnLineView> lines = jdbc.query("""
				select l.*, p.name, u.name as unit_name, bt.batch_no, bt.expiry_date,
				       (select array_agg(s.serial order by s.id) from serial_unit s where s.grn_id = l.grn_id
				        and s.product_id = l.product_id) as serials
				from grn_line l join product p on p.id = l.product_id join unit u on u.id = l.unit_id
				left join batch bt on bt.id = l.batch_id where l.grn_id = ? order by l.id""", (rs, i) -> {
			java.sql.Array serials = rs.getArray("serials");
			return new GrnLineView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
					rs.getString("unit_name"), Amounts.qtyText(rs.getBigDecimal("qty")),
					Amounts.qtyText(rs.getBigDecimal("bonus_qty")), cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null,
					cost ? Amounts.text(rs.getBigDecimal("discount")) : null,
					cost ? Amounts.text(rs.getBigDecimal("net")) : null, Amounts.qtyText(rs.getBigDecimal("base_qty")),
					cost ? Amounts.text(rs.getBigDecimal("base_unit_cost")) : null, rs.getString("batch_no"),
					rs.getObject("expiry_date", LocalDate.class), Amounts.qtyText(rs.getBigDecimal("qty_returned")),
					serials == null ? List.of() : List.of((String[]) serials.getArray()));
		}, id);
		return new GrnView(id, (String) g.get("number"), ((Number) g.get("supplier_id")).longValue(),
				(String) g.get("supplier_name"), ((Number) g.get("branch_id")).longValue(), (String) g.get("branch_name"),
				g.get("po_id") == null ? null : ((Number) g.get("po_id")).longValue(), (String) g.get("po_number"),
				(String) g.get("supplier_bill_no"),
				g.get("bill_date") == null ? null : ((Date) g.get("bill_date")).toLocalDate(),
				money(cost, g.get("subtotal")), money(cost, g.get("discount")), money(cost, g.get("freight")),
				money(cost, g.get("tax")), money(cost, g.get("total")), money(cost, g.get("returned")),
				money(cost, g.get("paid")), money(cost, g.get("due")),
				g.get("due_on") == null ? null : ((Date) g.get("due_on")).toLocalDate(), (String) g.get("note"),
				(String) g.get("actor_name"), ((Date) g.get("business_date")).toLocalDate(),
				((Timestamp) g.get("at")).toInstant(), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<GrnListItem> list(Long supplierId, Long branchId, LocalDate from, LocalDate to, Boolean unpaid,
			int page, int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("g.branch_id"));
		List<Object> args = new ArrayList<>();
		if (supplierId != null) {
			where.append(" and g.supplier_id = ?");
			args.add(supplierId);
		}
		if (branchId != null) {
			where.append(" and g.branch_id = ?");
			args.add(branchId);
		}
		if (from != null) {
			where.append(" and g.business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and g.business_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (Boolean.TRUE.equals(unpaid)) {
			where.append(" and g.due > 0");
		}
		long total = jdbc.queryForObject("select count(*) from grn g" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<GrnListItem> items = jdbc.query("""
				select g.*, s.name as supplier_name, b.name as branch_name from grn g
				join supplier s on s.id = g.supplier_id join branch b on b.id = g.branch_id""" + where
				+ " order by g.id desc limit ? offset ?",
				(rs, i) -> new GrnListItem(rs.getLong("id"), rs.getString("number"), rs.getString("supplier_name"),
						rs.getString("branch_name"), rs.getString("supplier_bill_no"),
						cost ? Amounts.text(rs.getBigDecimal("total")) : null,
						cost ? Amounts.text(rs.getBigDecimal("due")) : null, rs.getObject("due_on", LocalDate.class),
						rs.getObject("business_date", LocalDate.class)),
				pageArgs.toArray());
		return new PageResponse<>(items, page, size, total);
	}

	private static String money(boolean show, Object value) {
		return show ? Amounts.text((BigDecimal) value) : null;
	}
}
