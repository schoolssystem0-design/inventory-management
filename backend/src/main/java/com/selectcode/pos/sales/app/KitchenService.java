package com.selectcode.pos.sales.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shop.app.BranchAccess;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fast food orders (Stage 4c, module kitchen): each order gets a token number (1, 2, 3 … per branch per day), an
 * order type (dine-in with a table, takeaway or delivery) and, when it has menu items or deals, a kitchen ticket.
 * The kitchen screen moves tickets New → Preparing → Ready → Served; a cancelled sale cancels its ticket.
 */
@Service
public class KitchenService {

	static final Set<String> ORDER_TYPES = Set.of("DINE_IN", "TAKEAWAY", "DELIVERY");

	static final List<String> FLOW = List.of("NEW", "PREPARING", "READY", "SERVED");

	/** What a sale records about the order (all null when the shop has no kitchen). */
	record Order(String type, String table, Integer token) {
	}

	public record TicketItem(String name, String qty, String note, List<String> contains) {
	}

	public record TicketView(long id, long saleId, String saleNumber, int tokenNo, String orderType, String tableNo,
			String status, String customerName, Instant createdAt, Instant readyAt, Instant servedAt, long minutes,
			List<TicketItem> items) {
	}

	public record StatusInput(String status) {
	}

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final BranchAccess branches;

	private final AuditService audit;

	KitchenService(JdbcTemplate jdbc, ShopFeatures features, BranchAccess branches, AuditService audit) {
		this.jdbc = jdbc;
		this.features = features;
		this.branches = branches;
		this.audit = audit;
	}

	/** Checks the order type and table, and takes the next token of the day at the branch. */
	@Transactional(propagation = Propagation.MANDATORY)
	Order order(String typeIn, String tableIn, long branchId, LocalDate today) {
		if (!features.isOn(ModuleKey.KITCHEN)) {
			if (typeIn != null && !typeIn.isBlank()) {
				throw ApiException.rule("module_disabled", "Kitchen orders are not switched on for this shop");
			}
			return new Order(null, null, null);
		}
		String type = typeIn == null || typeIn.isBlank() ? "TAKEAWAY" : typeIn.trim().toUpperCase();
		if (!ORDER_TYPES.contains(type)) {
			throw ApiException.field("orderType", "invalid", "Order type is dine-in, takeaway or delivery");
		}
		String table = SalesService.trim(tableIn, 20);
		if ("DINE_IN".equals(type) && table == null) {
			throw ApiException.field("tableNo", "required", "Enter the table number");
		}
		// One token sequence per branch per day; the lock keeps two tills from taking the same number.
		jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))", Object.class,
				"token:" + TenantContext.requireShopId() + ":" + branchId + ":" + today);
		Integer last = jdbc.queryForObject(
				"select coalesce(max(token_no), 0) from sale where branch_id = ? and business_date = ?", Integer.class,
				branchId, Date.valueOf(today));
		return new Order(type, "DINE_IN".equals(type) ? table : null, (last == null ? 0 : last) + 1);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	void ticket(long saleId, long branchId, Order order, LocalDate today) {
		if (order.token() == null) {
			return;
		}
		jdbc.update("""
				insert into kitchen_ticket (shop_id, branch_id, sale_id, token_no, order_type, table_no, business_date)
				values (?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), branchId, saleId, order.token(),
				order.type(), order.table(), Date.valueOf(today));
	}

	/** A cancelled sale: its order is not made. */
	@Transactional(propagation = Propagation.MANDATORY)
	void cancelForSale(long saleId) {
		jdbc.update("update kitchen_ticket set status = 'CANCELLED', version = version + 1 where sale_id = ?", saleId);
	}

	/** Moves a ticket one step on (or to a later step); never back. */
	@Transactional
	public TicketView move(long id, StatusInput in) {
		Map<String, Object> t = jdbc.queryForList("select * from kitchen_ticket where id = ?"
				+ branches.filter("branch_id") + " for update", id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Order"));
		String status = in == null || in.status() == null ? "" : in.status().trim().toUpperCase();
		int from = FLOW.indexOf((String) t.get("status"));
		int to = FLOW.indexOf(status);
		if (to < 0) {
			throw ApiException.field("status", "invalid", "Status is preparing, ready or served");
		}
		if (from < 0) {
			throw ApiException.rule("ticket_closed", "This order was cancelled");
		}
		if (to <= from) {
			throw ApiException.rule("ticket_backwards", "This order is already " + ((String) t.get("status")).toLowerCase());
		}
		jdbc.update("""
				update kitchen_ticket set status = ?,
				       started_at = case when ? >= 1 and started_at is null then now() else started_at end,
				       ready_at = case when ? >= 2 and ready_at is null then now() else ready_at end,
				       served_at = case when ? >= 3 and served_at is null then now() else served_at end,
				       version = version + 1 where id = ?""", status, to, to, to, id);
		audit.record("KITCHEN_" + status, "kitchen_ticket", id, null, Map.of("token", t.get("token_no")));
		return get(id);
	}

	@Transactional(readOnly = true)
	public TicketView get(long id) {
		List<TicketView> list = query(" and t.id = ?", List.of(id));
		if (list.isEmpty()) {
			throw ApiException.notFound("Order");
		}
		return list.getFirst();
	}

	/**
	 * Tickets for the kitchen screen.
	 * @param status OPEN (new, preparing, ready), a status, or ALL; today's only unless {@code date} is given
	 */
	@Transactional(readOnly = true)
	public List<TicketView> list(Long branchId, String status, LocalDate date) {
		StringBuilder where = new StringBuilder(" and t.business_date = ?");
		List<Object> args = new ArrayList<>(List.of(Date.valueOf(date)));
		if (branchId != null) {
			branches.require(branchId);
			where.append(" and t.branch_id = ?");
			args.add(branchId);
		}
		String s = status == null || status.isBlank() ? "OPEN" : status.trim().toUpperCase();
		if ("OPEN".equals(s)) {
			where.append(" and t.status in ('NEW', 'PREPARING', 'READY')");
		}
		else if (!"ALL".equals(s)) {
			where.append(" and t.status = ?");
			args.add(s);
		}
		return query(where.toString(), args);
	}

	private List<TicketView> query(String where, List<Object> args) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				select t.*, s.number as sale_number, s.customer_name from kitchen_ticket t join sale s on s.id = t.sale_id
				where 1=1""" + branches.filter("t.branch_id") + where + " order by t.id limit 300", args.toArray());
		if (rows.isEmpty()) {
			return List.of();
		}
		Long[] saleIds = rows.stream().map(r -> ((Number) r.get("sale_id")).longValue()).toArray(Long[]::new);
		// Kitchen items: menu items and deals (a deal lists what is in it). Drinks and other stock items are handed
		// over at the counter and are not shown.
		Map<Long, List<TicketItem>> items = new LinkedHashMap<>();
		jdbc.query("""
				select l.sale_id, l.product_id, l.product_name, sum(l.qty) as qty, l.note, p.kind,
				       (select array_agg(ip.name || case when bi.qty <> 1 then ' × ' || trim_scale(bi.qty) else '' end
				                         order by ip.name)
				        from bundle_item bi join product ip on ip.id = bi.item_product_id
				        where bi.bundle_product_id = l.product_id and p.kind = 'BUNDLE') as contains
				from sale_line l join product p on p.id = l.product_id
				where l.sale_id = any(?) and p.kind in ('RECIPE', 'BUNDLE')
				group by l.sale_id, l.product_id, l.product_name, l.note, p.kind
				order by min(l.line_no)""", rs -> {
			java.sql.Array arr = rs.getArray("contains");
			List<String> contains = arr == null ? List.of() : List.of((String[]) arr.getArray());
			BigDecimal qty = rs.getBigDecimal("qty");
			items.computeIfAbsent(rs.getLong("sale_id"), k -> new ArrayList<>())
				.add(new TicketItem(rs.getString("product_name"), qty.stripTrailingZeros().toPlainString(),
						rs.getString("note"), contains));
		}, (Object) saleIds);
		Instant now = Instant.now();
		List<TicketView> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			long saleId = ((Number) r.get("sale_id")).longValue();
			Instant created = ((java.sql.Timestamp) r.get("created_at")).toInstant();
			Instant served = r.get("served_at") == null ? null : ((java.sql.Timestamp) r.get("served_at")).toInstant();
			Instant ready = r.get("ready_at") == null ? null : ((java.sql.Timestamp) r.get("ready_at")).toInstant();
			Instant end = served != null ? served : ready != null ? ready : now;
			out.add(new TicketView(((Number) r.get("id")).longValue(), saleId, (String) r.get("sale_number"),
					((Number) r.get("token_no")).intValue(), (String) r.get("order_type"), (String) r.get("table_no"),
					(String) r.get("status"), (String) r.get("customer_name"), created, ready, served,
					java.time.Duration.between(created, end).toMinutes(), items.getOrDefault(saleId, List.of())));
		}
		return out;
	}
}
