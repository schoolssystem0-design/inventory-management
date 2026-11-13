package com.selectcode.pos.sales.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.catalog.app.ProductCatalog.UnitRef;
import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.notifications.app.Notifier;
import com.selectcode.pos.sales.app.SalesService.PaymentInput;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.SalesService.SaleLineInput;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.Idempotency;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService;
import com.selectcode.pos.stock.app.StockService.BatchPick;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
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
 * Deliveries (ELE-04) and delivery challans (HRD-04). A delivery tracks taking an invoice's (or challan's) goods to
 * the customer, with installation if needed. A challan sends goods out before the bill: stock leaves at once, and the
 * challan is later turned into an invoice (the challan's stock comes back and the invoice takes it, in one
 * transaction) or cancelled (stock back).
 */
@Service
public class DeliveryService {

	static final Set<String> DELIVERY_STATUSES = Set.of("PENDING", "ON_THE_WAY", "DELIVERED", "INSTALLED", "FAILED",
			"CANCELLED");

	public record DeliveryInput(Long saleId, Long challanId, String customerName, String customerPhone, String address,
			LocalDate scheduledOn, String driver, String vehicle, Boolean installation, String note) {
	}

	/** Fields left null are not changed. */
	public record DeliveryUpdate(String status, LocalDate scheduledOn, String driver, String vehicle, String receivedBy,
			String note) {
	}

	public record DeliveryView(long id, String number, long branchId, Long saleId, String saleNumber, Long challanId,
			String challanNumber, String customerName, String customerPhone, String address, LocalDate scheduledOn,
			String driver, String vehicle, boolean installation, String status, String note, String receivedBy,
			String actorName, LocalDate date, Instant at, Instant doneAt) {
	}

	public record ChallanLineInput(Long productId, Long unitId, String qty, String price) {
	}

	public record ChallanInput(Long branchId, Long customerId, String customerName, String customerPhone,
			String address, List<ChallanLineInput> lines, String note) {
	}

	public record ConvertChallan(Long tillSessionId, List<PaymentInput> payments) {
	}

	public record CancelChallan(String reason) {
	}

	public record ChallanLineView(long id, int lineNo, long productId, String productName, Long unitId,
			String unitName, String qty, String price, String amount) {
	}

	public record ChallanView(long id, String number, long branchId, String branchName, Long customerId,
			String customerName, String customerPhone, String address, String status, Long saleId, String saleNumber,
			String total, String note, String actorName, LocalDate date, Instant at, Instant closedAt,
			List<ChallanLineView> lines) {
	}

	private final JdbcTemplate jdbc;

	private final SalesService sales;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final CustomerService customers;

	private final TillService tills;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final Notifier notifier;

	private final Idempotency idempotency;

	private final AuditService audit;

	private final ShopTime time;

	DeliveryService(JdbcTemplate jdbc, SalesService sales, StockService stock, ProductCatalog catalog,
			CustomerService customers, TillService tills, BranchAccess branches, ShopConfigService numbering,
			Notifier notifier, Idempotency idempotency, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.sales = sales;
		this.stock = stock;
		this.catalog = catalog;
		this.customers = customers;
		this.tills = tills;
		this.branches = branches;
		this.numbering = numbering;
		this.notifier = notifier;
		this.idempotency = idempotency;
		this.audit = audit;
		this.time = time;
	}

	// ------------------------------------------------------------------ deliveries

	@Transactional
	public DeliveryView create(DeliveryInput in) {
		if ((in.saleId() == null) == (in.challanId() == null)) {
			throw ApiException.field("saleId", "required", "Choose the invoice or the challan to deliver");
		}
		Map<String, Object> doc = in.saleId() != null
				? jdbc.queryForList("select * from sale where id = ?" + branches.filter("branch_id"), in.saleId()).stream()
					.findFirst().orElseThrow(() -> ApiException.notFound("Invoice"))
				: jdbc.queryForList("select * from challan where id = ?" + branches.filter("branch_id"), in.challanId())
					.stream().findFirst().orElseThrow(() -> ApiException.notFound("Challan"));
		if ("CANCELLED".equals(doc.get("status"))) {
			throw ApiException.rule("doc_cancelled", "This document was cancelled");
		}
		String name = SalesService.trim(in.customerName(), 200);
		name = name != null ? name : (String) doc.get("customer_name");
		String phone = SalesService.trim(in.customerPhone(), 30);
		phone = phone != null ? phone : (String) doc.get("customer_phone");
		Object customerId = doc.get("customer_id");
		if ((name == null || phone == null) && customerId != null) {
			Map<String, Object> c = rawCustomer(((Number) customerId).longValue());
			name = name != null ? name : (String) c.get("name");
			phone = phone != null ? phone : (String) c.get("phone");
		}
		if (name == null) {
			throw ApiException.field("customerName", "required", "Enter who receives the goods");
		}
		String address = SalesService.trim(in.address(), 500);
		if (address == null && doc.get("address") != null) {
			address = (String) doc.get("address");
		}
		if (address == null && customerId != null) {
			address = (String) rawCustomer(((Number) customerId).longValue()).get("address");
		}
		if (address == null) {
			throw ApiException.field("address", "required", "Enter the delivery address");
		}
		long branchId = ((Number) doc.get("branch_id")).longValue();
		String number = numbering.nextNumber("DELIVERY", branchId);
		long id = jdbc.queryForObject("""
				insert into delivery (shop_id, number, branch_id, sale_id, challan_id, customer_name, customer_phone, address,
				                      scheduled_on, driver, vehicle, installation, note, created_by, actor_name,
				                      business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, branchId, in.saleId(), in.challanId(), name, phone, address,
				in.scheduledOn() == null ? null : Date.valueOf(in.scheduledOn()), SalesService.trim(in.driver(), 120),
				SalesService.trim(in.vehicle(), 40), Boolean.TRUE.equals(in.installation()),
				SalesService.trim(in.note(), 500), TillService.userId(), CurrentActor.name(), Date.valueOf(time.today()));
		audit.record("DELIVERY_CREATED", "delivery", id, null, Map.of("number", number));
		return delivery(id);
	}

	@Transactional
	public DeliveryView update(long id, DeliveryUpdate in) {
		Map<String, Object> d = jdbc.queryForList("select * from delivery where id = ?" + branches.filter("branch_id")
				+ " for update", id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Delivery"));
		String old = (String) d.get("status");
		if (Set.of("DELIVERED", "INSTALLED", "CANCELLED").contains(old)) {
			throw ApiException.rule("delivery_closed", "This delivery is already " + old.toLowerCase());
		}
		String status = in.status() == null || in.status().isBlank() ? null : in.status().trim().toUpperCase();
		if (status != null && !DELIVERY_STATUSES.contains(status)) {
			throw ApiException.field("status", "invalid", "Status is pending, on the way, delivered, installed, failed or cancelled");
		}
		if ("INSTALLED".equals(status) && !Boolean.TRUE.equals(d.get("installation"))) {
			throw ApiException.field("status", "invalid", "This delivery has no installation; mark it delivered");
		}
		jdbc.update("""
				update delivery set status = coalesce(?, status), scheduled_on = coalesce(?, scheduled_on),
				       driver = coalesce(?, driver), vehicle = coalesce(?, vehicle), received_by = coalesce(?, received_by),
				       note = coalesce(?, note),
				       done_at = case when ? in ('DELIVERED', 'INSTALLED', 'FAILED', 'CANCELLED') then now() else done_at end,
				       updated_at = now(), version = version + 1 where id = ?""", status,
				in.scheduledOn() == null ? null : Date.valueOf(in.scheduledOn()), SalesService.trim(in.driver(), 120),
				SalesService.trim(in.vehicle(), 40), SalesService.trim(in.receivedBy(), 120),
				SalesService.trim(in.note(), 500), status, id);
		if ("ON_THE_WAY".equals(status) && !"ON_THE_WAY".equals(old)) {
			String shop = jdbc.queryForObject("select business_name from shop_settings where shop_id = ?", String.class,
					TenantContext.requireShopId());
			notifier.sms((String) d.get("customer_phone"), "DELIVERY_OUT",
					shop + ": your order " + d.get("number") + " is on the way"
							+ (d.get("driver") != null || in.driver() != null
									? " with " + (in.driver() != null ? in.driver() : d.get("driver")) : "")
							+ ".",
					"delivery-out-" + id, false);
		}
		audit.record("DELIVERY_UPDATED", "delivery", id, null, Map.of("status", String.valueOf(status)));
		return delivery(id);
	}

	@Transactional(readOnly = true)
	public DeliveryView delivery(long id) {
		return jdbc.query("""
				select d.*, s.number as sale_number, c.number as challan_number from delivery d
				left join sale s on s.id = d.sale_id left join challan c on c.id = d.challan_id
				where d.id = ?""" + branches.filter("d.branch_id"),
				(rs, i) -> new DeliveryView(rs.getLong("id"), rs.getString("number"), rs.getLong("branch_id"),
						(Long) rs.getObject("sale_id"), rs.getString("sale_number"), (Long) rs.getObject("challan_id"),
						rs.getString("challan_number"), rs.getString("customer_name"), rs.getString("customer_phone"),
						rs.getString("address"), rs.getObject("scheduled_on", LocalDate.class), rs.getString("driver"),
						rs.getString("vehicle"), rs.getBoolean("installation"), rs.getString("status"),
						rs.getString("note"), rs.getString("received_by"), rs.getString("actor_name"),
						rs.getObject("business_date", LocalDate.class), rs.getTimestamp("at").toInstant(),
						rs.getTimestamp("done_at") == null ? null : rs.getTimestamp("done_at").toInstant()),
				id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Delivery"));
	}

	/**
	 * @param status a status, OPEN (pending or on the way) or null for all
	 */
	@Transactional(readOnly = true)
	public PageResponse<DeliveryView> deliveries(String status, LocalDate from, LocalDate to, Long saleId,
			Long challanId, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if ("OPEN".equalsIgnoreCase(status)) {
			where.append(" and status in ('PENDING', 'ON_THE_WAY')");
		}
		else if (status != null && !status.isBlank()) {
			where.append(" and status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (from != null) {
			where.append(" and coalesce(scheduled_on, business_date) >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and coalesce(scheduled_on, business_date) <= ?");
			args.add(Date.valueOf(to));
		}
		if (saleId != null) {
			where.append(" and sale_id = ?");
			args.add(saleId);
		}
		if (challanId != null) {
			where.append(" and challan_id = ?");
			args.add(challanId);
		}
		long total = jdbc.queryForObject("select count(*) from delivery" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from delivery" + where
				+ " order by (status in ('PENDING', 'ON_THE_WAY')) desc, coalesce(scheduled_on, business_date), id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::delivery).toList(), page, size, total);
	}

	// ------------------------------------------------------------------ challans

	@Transactional
	public ChallanView createChallan(ChallanInput in) {
		long branchId = in.branchId() == null ? branches.defaultBranchId() : in.branchId();
		branches.require(branchId);
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		Long customerId = in.customerId();
		String name = SalesService.trim(in.customerName(), 200);
		String phone = SalesService.trim(in.customerPhone(), 30);
		String address = SalesService.trim(in.address(), 500);
		if (customerId != null) {
			Map<String, Object> c = rawCustomer(customerId);
			name = (String) c.get("name");
			phone = phone != null ? phone : (String) c.get("phone");
			address = address != null ? address : (String) c.get("address");
		}
		if (name == null) {
			throw ApiException.field("customerName", "required", "Choose the customer or enter their name");
		}
		record Planned(ProductRef product, UnitRef unit, BigDecimal qty, BigDecimal baseQty, BigDecimal price) {
		}
		List<Planned> planned = new ArrayList<>();
		for (int i = 0; i < in.lines().size(); i++) {
			ChallanLineInput l = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (l.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef p = catalog.require(l.productId());
			if (p.serial() || "BUNDLE".equals(p.kind()) || "VARIANT_PARENT".equals(p.kind())) {
				throw ApiException.rule("challan_kind",
						p.name() + ": IMEI items, bundles and variant groups go on an invoice, not a challan");
			}
			UnitRef unit = catalog.unit(p, l.unitId());
			BigDecimal qty = Amounts.parseQty(l.qty(), f + ".qty");
			if (qty == null || qty.signum() <= 0) {
				throw ApiException.field(f + ".qty", "required", "Enter the quantity");
			}
			ProductCatalog.checkQty(p, unit, qty, f + ".qty");
			BigDecimal price = Amounts.parseMoney(l.price(), f + ".price");
			// Challans are billed at their own prices without asking again: a different price needs the right
			// to change prices (QA 2026-11-13).
			if (price != null && price.compareTo(unit.price()) != 0
					&& !CurrentActor.hasPermission(com.selectcode.pos.shared.security.Permissions.POS_PRICE_OVERRIDE)) {
				throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "price_change_not_allowed",
						"You may not change the price of " + p.name() + " (list price Rs " + unit.price().toPlainString() + ")");
			}
			planned.add(new Planned(p, unit, qty, Amounts.qty(qty.multiply(unit.factor())),
					price == null ? unit.price() : price));
		}
		String number = numbering.nextNumber("CHALLAN", branchId);
		LocalDate today = time.today();
		long id = jdbc.queryForObject("""
				insert into challan (shop_id, number, branch_id, customer_id, customer_name, customer_phone, address, note,
				                     created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), number,
				branchId, customerId, name, phone, address, SalesService.trim(in.note(), 500), TillService.userId(),
				CurrentActor.name(), Date.valueOf(today));
		List<Move> moves = new ArrayList<>();
		for (int i = 0; i < planned.size(); i++) {
			Planned p = planned.get(i);
			jdbc.update("""
					insert into challan_line (shop_id, challan_id, line_no, product_id, product_name, unit_id, unit_name,
					                          unit_factor, qty, base_qty, price)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), id, i + 1, p.product().id(),
					p.product().name(), p.unit().unitId(), p.unit().name(), p.unit().factor(), p.qty(), p.baseQty(),
					p.price());
			if (!p.product().tracksStock()) {
				continue;
			}
			if (p.product().batch()) {
				for (BatchPick pick : stock.pickBatches(p.product().id(), branchId, p.baseQty(), today)) {
					moves.add(new Move(p.product().id(), branchId, pick.batchId(), null, pick.qty().negate(),
							pick.cost() == null ? p.product().costAvg() : pick.cost(), null));
				}
			}
			else {
				moves.add(new Move(p.product().id(), branchId, null, null, p.baseQty().negate(), p.product().costAvg(), null));
			}
		}
		stock.apply(MoveType.CHALLAN, new DocRef("CHALLAN", id, number), moves);
		audit.record("CHALLAN_CREATED", "challan", id, null, Map.of("number", number, "customer", name));
		return challan(id);
	}

	/** Bills a challan: its stock comes back and the invoice takes it, at the challan's prices. */
	@Transactional
	public ChallanView convert(long id, ConvertChallan in, String idempotencyKey, ApprovalRequest approval) {
		challan(id);
		var earlier = idempotency.claim("CHALLAN_CONVERT", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return challan(earlier.get());
		}
		Map<String, Object> c = lockChallan(id);
		Map<String, Object> session = tills.requireOpenSession(in == null ? null : in.tillSessionId(), null);
		long branchId = ((Number) c.get("branch_id")).longValue();
		if (((Number) session.get("branch_id")).longValue() != branchId) {
			throw ApiException.rule("wrong_branch", "Bill this challan from a till at the branch it was sent from");
		}
		String number = (String) c.get("number");
		putBack(id, number, branchId, "Billed");
		List<SaleLineInput> lines = new ArrayList<>();
		for (Map<String, Object> l : jdbc.queryForList("select * from challan_line where challan_id = ? order by line_no",
				id)) {
			lines.add(new SaleLineInput(((Number) l.get("product_id")).longValue(),
					l.get("unit_id") == null ? null : ((Number) l.get("unit_id")).longValue(),
					Amounts.qtyText((BigDecimal) l.get("qty")), Amounts.text((BigDecimal) l.get("price")), null, null, null,
					null));
		}
		Long customerId = c.get("customer_id") == null ? null : ((Number) c.get("customer_id")).longValue();
		SaleInput sale = new SaleInput(((Number) session.get("id")).longValue(), customerId, null,
				(String) c.get("customer_name"), (String) c.get("customer_phone"), null, lines, null, null,
				in == null ? null : in.payments(), "From challan " + number, null, null, null);
		long saleId = sales.postInternal(sale, approval, List.of(), null, true);
		jdbc.update("""
				update challan set status = 'INVOICED', sale_id = ?, closed_at = now(), version = version + 1 where id = ?""",
				saleId, id);
		jdbc.update("update delivery set sale_id = ? where challan_id = ? and sale_id is null", saleId, id);
		idempotency.complete("CHALLAN_CONVERT", idempotencyKey, id);
		audit.record("CHALLAN_INVOICED", "challan", id, null, Map.of("number", number, "sale", saleId));
		return challan(id);
	}

	@Transactional
	public ChallanView cancelChallan(long id, CancelChallan in) {
		Map<String, Object> c = lockChallan(id);
		String reason = SalesService.trim(in == null ? null : in.reason(), 300);
		if (reason == null) {
			throw ApiException.field("reason", "required", "Write why the challan is cancelled");
		}
		String number = (String) c.get("number");
		putBack(id, number, ((Number) c.get("branch_id")).longValue(), "Cancelled");
		jdbc.update("""
				update challan set status = 'CANCELLED', closed_at = now(), note = left(coalesce(note || ' · ', '') || ?, 500),
				       version = version + 1 where id = ?""", "Cancelled: " + reason, id);
		jdbc.update("update delivery set status = 'CANCELLED', done_at = now() where challan_id = ? and status in ('PENDING', 'ON_THE_WAY')",
				id);
		audit.record("CHALLAN_CANCELLED", "challan", id, null, Map.of("number", number, "reason", reason));
		return challan(id);
	}

	/** Every stock movement of the challan, reversed (same batches and costs). */
	private void putBack(long id, String number, long branchId, String note) {
		List<Move> moves = new ArrayList<>();
		for (Map<String, Object> m : jdbc.queryForList("""
				select * from stock_movement where doc_type = 'CHALLAN' and doc_id = ? and move_type = 'CHALLAN'""", id)) {
			moves.add(new Move(((Number) m.get("product_id")).longValue(), branchId,
					m.get("batch_id") == null ? null : ((Number) m.get("batch_id")).longValue(), null,
					((BigDecimal) m.get("qty")).negate(), (BigDecimal) m.get("unit_cost"), note));
		}
		moves.sort(Comparator.comparingLong(Move::productId));
		stock.apply(MoveType.CHALLAN_BACK, new DocRef("CHALLAN", id, number), moves);
	}

	private Map<String, Object> rawCustomer(long id) {
		return jdbc.queryForList("select name, phone, address from customer where id = ?", id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Customer"));
	}

	private Map<String, Object> lockChallan(long id) {
		Map<String, Object> c = jdbc.queryForList("select * from challan where id = ?" + branches.filter("branch_id")
				+ " for update", id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Challan"));
		if (!"OPEN".equals(c.get("status"))) {
			throw ApiException.rule("challan_closed", "This challan is already " + c.get("status").toString().toLowerCase());
		}
		return c;
	}

	@Transactional(readOnly = true)
	public ChallanView challan(long id) {
		Map<String, Object> c = jdbc.queryForList("""
				select c.*, b.name as branch_name, s.number as sale_number from challan c join branch b on b.id = c.branch_id
				left join sale s on s.id = c.sale_id where c.id = ?""" + branches.filter("c.branch_id"), id).stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Challan"));
		List<ChallanLineView> lines = jdbc.query("select * from challan_line where challan_id = ? order by line_no",
				(rs, i) -> new ChallanLineView(rs.getLong("id"), rs.getInt("line_no"), rs.getLong("product_id"),
						rs.getString("product_name"), (Long) rs.getObject("unit_id"), rs.getString("unit_name"),
						Amounts.qtyText(rs.getBigDecimal("qty")), Amounts.text(rs.getBigDecimal("price")),
						Amounts.text(SaleMath.lineAmount(rs.getBigDecimal("qty"), rs.getBigDecimal("price")))),
				id);
		BigDecimal total = lines.stream().map(l -> new BigDecimal(l.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new ChallanView(((Number) c.get("id")).longValue(), (String) c.get("number"),
				((Number) c.get("branch_id")).longValue(), (String) c.get("branch_name"),
				c.get("customer_id") == null ? null : ((Number) c.get("customer_id")).longValue(),
				(String) c.get("customer_name"), (String) c.get("customer_phone"), (String) c.get("address"),
				(String) c.get("status"), c.get("sale_id") == null ? null : ((Number) c.get("sale_id")).longValue(),
				(String) c.get("sale_number"), Amounts.text(total.setScale(2, RoundingMode.HALF_UP)),
				(String) c.get("note"), (String) c.get("actor_name"), ((Date) c.get("business_date")).toLocalDate(),
				((Timestamp) c.get("at")).toInstant(),
				c.get("closed_at") == null ? null : ((Timestamp) c.get("closed_at")).toInstant(), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<ChallanView> challans(String status, Long customerId, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (status != null && !status.isBlank()) {
			where.append(" and status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (customerId != null) {
			where.append(" and customer_id = ?");
			args.add(customerId);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (upper(number) = ? or lower(customer_name) like ?)");
			args.add(q.trim().toUpperCase());
			args.add("%" + q.trim().toLowerCase() + "%");
		}
		long total = jdbc.queryForObject("select count(*) from challan" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from challan" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::challan).toList(), page, size, total);
	}
}
