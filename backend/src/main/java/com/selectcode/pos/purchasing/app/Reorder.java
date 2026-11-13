package com.selectcode.pos.purchasing.app;

import com.selectcode.pos.purchasing.app.PurchaseOrders.PoInput;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoLineInput;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoView;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shop.app.BranchAccess;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Low stock to purchase orders (PUR-07): items at or below their reorder level with a suggested quantity that counts
 * what is already on order and the last 30 days' sales; one click makes one PO per supplier.
 */
@Service
public class Reorder {

	public record Suggestion(long productId, String sku, String name, Long supplierId, String supplierName,
			String onHand, String onOrder, String sold30, String reorderLevel, String suggestedQty, String unitCost) {
	}

	/** @param lines products and quantities to order; empty = every suggestion with a supplier */
	public record ReorderRequest(Long branchId, List<ReorderLine> lines) {
	}

	public record ReorderLine(Long productId, String qty, Long supplierId) {
	}

	private final JdbcTemplate jdbc;

	private final PurchaseOrders orders;

	private final BranchAccess branches;

	private final ShopTime time;

	Reorder(JdbcTemplate jdbc, PurchaseOrders orders, BranchAccess branches, ShopTime time) {
		this.jdbc = jdbc;
		this.orders = orders;
		this.branches = branches;
		this.time = time;
	}

	@Transactional(readOnly = true)
	public List<Suggestion> suggestions(Long branchId) {
		if (branchId != null) {
			branches.require(branchId);
		}
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		String branchCond = branchId == null ? branches.filter("s.branch_id") : " and s.branch_id = " + branchId;
		String moveBranch = branchId == null ? branches.filter("m.branch_id") : " and m.branch_id = " + branchId;
		String poBranch = branchId == null ? branches.filter("o.branch_id") : " and o.branch_id = " + branchId;
		Timestamp since = Timestamp.from(ShopTime.startOf(time.today().minusDays(30)));
		List<Suggestion> list = new ArrayList<>();
		jdbc.query("""
				select p.id, p.sku, p.name, p.reorder_level, p.cost_avg, p.supplier_id, sp.name as supplier_name,
				       coalesce((select sum(s.qty) from product_stock s where s.product_id = p.id""" + branchCond + """
				       ), 0) as on_hand,
				       coalesce((select sum((l.qty_ordered - l.qty_received) * l.unit_factor) from po_line l
				                 join purchase_order o on o.id = l.po_id
				                 where l.product_id = p.id and o.status in ('ORDERED', 'PARTIAL') and l.qty_ordered > l.qty_received"""
				+ poBranch + """
				       ), 0) as on_order,
				       coalesce((select -sum(m.qty) from stock_movement m where m.product_id = p.id and m.move_type = 'SALE'
				                 and m.at >= ?""" + moveBranch + """
				       ), 0) as sold30,
				       coalesce((select l.base_unit_cost from grn_line l where l.product_id = p.id order by l.id desc limit 1),
				                p.cost_avg) as last_cost
				from product p left join supplier sp on sp.id = p.supplier_id
				where p.active and p.reorder_level > 0 and p.kind in ('STANDARD', 'SERIAL', 'BATCH')
				order by sp.name nulls last, p.name""", rs -> {
			BigDecimal onHand = rs.getBigDecimal("on_hand");
			BigDecimal level = rs.getBigDecimal("reorder_level");
			if (onHand.compareTo(level) > 0) {
				return;
			}
			BigDecimal onOrder = rs.getBigDecimal("on_order");
			BigDecimal sold = rs.getBigDecimal("sold30");
			BigDecimal suggest = SaleMath.reorderSuggestion(level, sold, onHand, onOrder);
			list.add(new Suggestion(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
					(Long) rs.getObject("supplier_id"), rs.getString("supplier_name"), Amounts.qtyText(onHand),
					Amounts.qtyText(onOrder), Amounts.qtyText(sold), Amounts.qtyText(level), Amounts.qtyText(suggest),
					cost ? Amounts.text(rs.getBigDecimal("last_cost")) : null));
		}, since);
		return list;
	}

	/** Makes one purchase order per supplier from the chosen (or all suggested) lines. */
	@Transactional
	public List<PoView> createOrders(ReorderRequest request) {
		long branchId = request.branchId() == null ? branches.defaultBranchId() : request.branchId();
		branches.requireActive(branchId);
		Map<Long, List<PoLineInput>> bySupplier = new LinkedHashMap<>();
		if (request.lines() == null || request.lines().isEmpty()) {
			for (Suggestion s : suggestions(request.branchId())) {
				if (s.supplierId() != null && new BigDecimal(s.suggestedQty()).signum() > 0) {
					bySupplier.computeIfAbsent(s.supplierId(), k -> new ArrayList<>())
						.add(new PoLineInput(s.productId(), null, s.suggestedQty(), null));
				}
			}
		}
		else {
			for (int i = 0; i < request.lines().size(); i++) {
				ReorderLine line = request.lines().get(i);
				Long supplierId = line.supplierId();
				if (supplierId == null && line.productId() != null) {
					supplierId = jdbc.queryForList("select supplier_id from product where id = ?", Long.class,
							line.productId()).stream().findFirst().orElse(null);
				}
				if (supplierId == null) {
					throw ApiException.field("lines[" + i + "].supplierId", "required",
							"Choose a supplier for this item");
				}
				bySupplier.computeIfAbsent(supplierId, k -> new ArrayList<>())
					.add(new PoLineInput(line.productId(), null, line.qty(), null));
			}
		}
		if (bySupplier.isEmpty()) {
			throw ApiException.rule("nothing_to_order", "Nothing needs ordering (or the items have no supplier yet)");
		}
		List<PoView> created = new ArrayList<>();
		bySupplier.forEach((supplierId, lines) -> created
			.add(orders.create(new PoInput(supplierId, branchId, null, "Made from the reorder list", lines))));
		return created;
	}
}
