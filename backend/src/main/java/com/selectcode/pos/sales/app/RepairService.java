package com.selectcode.pos.sales.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.notifications.app.Notifier;
import com.selectcode.pos.sales.app.SalesService.PaymentInput;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.SalesService.SaleLineInput;
import com.selectcode.pos.sales.app.SalesService.Tender;
import com.selectcode.pos.shared.audit.AuditService;
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
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repair job cards (MOB-07): a device is booked in with its fault, goes through the repair statuses, collects parts
 * and labour, the customer gets an SMS when it is ready, and handing it back posts a normal invoice (parts leave
 * stock then, labour is the "Repair labour" service) with the advance counted as already paid.
 */
@Service
public class RepairService {

	static final Set<String> OPEN = Set.of("RECEIVED", "CHECKING", "WAITING_PARTS", "REPAIRING", "READY",
			"NOT_REPAIRABLE");

	static final String LABOUR_SKU = "REPAIR-LABOUR";

	/**
	 * @param advance paid when the device is left (needs an open till; cash goes into the drawer)
	 */
	public record RepairInput(Long customerId, String customerName, String customerPhone, String device, String serial,
			String problem, String accessories, String conditionNote, String estimate, String advance,
			String advanceMethod, Long tillSessionId, Long technicianId, LocalDate promisedOn) {
	}

	/** Fields left null are not changed. */
	public record RepairUpdate(String status, String diagnosis, String labour, String estimate, Long technicianId,
			LocalDate promisedOn, String note) {
	}

	public record PartInput(Long productId, String qty, String price) {
	}

	public record DeliverInput(Long tillSessionId, List<PaymentInput> payments) {
	}

	public record CancelRepair(String reason, Long tillSessionId) {
	}

	public record PartView(long id, long productId, String productName, String sku, String qty, String price,
			String amount) {
	}

	public record EventView(String status, String note, String actorName, Instant at) {
	}

	public record RepairView(long id, String number, long branchId, String branchName, Long customerId,
			String customerName, String customerPhone, String device, String serial, String problem,
			String accessories, String conditionNote, String diagnosis, String estimate, String advance,
			String advanceMethod, String labour, String partsTotal, String total, String balance, Long technicianId,
			String technicianName, LocalDate promisedOn, String status, Long saleId, String saleNumber,
			boolean readySmsSent, String actorName, LocalDate date, Instant at, Instant readyAt, Instant closedAt,
			List<PartView> parts, List<EventView> events) {
	}

	public record RepairCounts(long open, long ready, long overdue) {
	}

	private final JdbcTemplate jdbc;

	private final SalesService sales;

	private final SaleQueries queries;

	private final ProductCatalog catalog;

	private final CustomerService customers;

	private final TillService tills;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final Notifier notifier;

	private final Idempotency idempotency;

	private final AuditService audit;

	private final ShopTime time;

	RepairService(JdbcTemplate jdbc, SalesService sales, SaleQueries queries, ProductCatalog catalog,
			CustomerService customers, TillService tills, BranchAccess branches, ShopConfigService numbering,
			Notifier notifier, Idempotency idempotency, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.sales = sales;
		this.queries = queries;
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

	@Transactional
	public RepairView create(RepairInput in) {
		String device = SalesService.trim(in.device(), 200);
		if (device == null) {
			throw ApiException.field("device", "required", "Enter the device (make and model)");
		}
		String problem = SalesService.trim(in.problem(), 1000);
		if (problem == null) {
			throw ApiException.field("problem", "required", "Describe the fault");
		}
		Long customerId = in.customerId();
		String name = SalesService.trim(in.customerName(), 200);
		String phone = SalesService.trim(in.customerPhone(), 30);
		if (customerId != null) {
			Map<String, Object> c = jdbc.queryForList("select name, phone from customer where id = ?", customerId).stream()
				.findFirst().orElseThrow(() -> ApiException.notFound("Customer"));
			name = (String) c.get("name");
			phone = phone != null ? phone : (String) c.get("phone");
		}
		else if (phone != null) {
			var existing = customers.byPhone(phone);
			if (existing.isPresent()) {
				customerId = existing.get().id();
				name = name == null ? existing.get().name() : name;
			}
		}
		if (name == null) {
			throw ApiException.field("customerName", "required", "Enter the customer's name");
		}
		if (phone == null) {
			throw ApiException.field("customerPhone", "required", "Enter the customer's phone (for the ready SMS)");
		}
		BigDecimal estimate = Amounts.parseMoney(in.estimate(), "estimate");
		BigDecimal advance = Amounts.nz(Amounts.parseMoney(in.advance(), "advance"));
		String method = null;
		Long sessionId = null;
		long branchId;
		if (advance.signum() > 0) {
			method = in.advanceMethod() == null || in.advanceMethod().isBlank() ? "CASH"
					: in.advanceMethod().trim().toUpperCase();
			if (!Set.of("CASH", "CARD", "BANK", "JAZZCASH", "EASYPAISA").contains(method)) {
				throw ApiException.field("advanceMethod", "invalid", "Take the advance in cash, card, bank or a wallet");
			}
			Map<String, Object> session = tills.requireOpenSession(in.tillSessionId(), null);
			sessionId = ((Number) session.get("id")).longValue();
			branchId = ((Number) session.get("branch_id")).longValue();
		}
		else if (in.tillSessionId() != null) {
			branchId = ((Number) tills.requireOpenSession(in.tillSessionId(), null).get("branch_id")).longValue();
		}
		else {
			branchId = branches.defaultBranchId();
		}
		checkTechnician(in.technicianId());
		String number = numbering.nextNumber("REPAIR", branchId);
		long id = jdbc.queryForObject("""
				insert into repair_job (shop_id, number, branch_id, customer_id, customer_name, customer_phone, device, serial,
				                        problem, accessories, condition_note, estimate, advance, advance_method,
				                        advance_session_id, technician_id, promised_on, created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, branchId, customerId, name, phone, device,
				SalesService.trim(in.serial(), 60), problem, SalesService.trim(in.accessories(), 300),
				SalesService.trim(in.conditionNote(), 300), estimate, advance, method, sessionId, in.technicianId(),
				in.promisedOn() == null ? null : Date.valueOf(in.promisedOn()), TillService.userId(), CurrentActor.name(),
				Date.valueOf(time.today()));
		if (advance.signum() > 0 && "CASH".equals(method)) {
			cash(sessionId, "IN", advance, "Repair advance " + number);
		}
		event(id, "RECEIVED", advance.signum() > 0 ? "Advance Rs " + advance.toPlainString() + " (" + method.toLowerCase()
				+ ")" : null);
		audit.record("REPAIR_BOOKED", "repair_job", id, null, Map.of("number", number, "device", device));
		return get(id);
	}

	@Transactional
	public RepairView update(long id, RepairUpdate in) {
		Map<String, Object> job = lock(id);
		requireOpen(job);
		String status = in.status() == null || in.status().isBlank() ? null : in.status().trim().toUpperCase();
		if (status != null && (!OPEN.contains(status))) {
			throw ApiException.field("status", "invalid",
					"Use deliver to hand the device back, or cancel to close the job");
		}
		BigDecimal labour = Amounts.parseMoney(in.labour(), "labour");
		BigDecimal estimate = Amounts.parseMoney(in.estimate(), "estimate");
		if (in.technicianId() != null) {
			checkTechnician(in.technicianId());
		}
		String old = (String) job.get("status");
		jdbc.update("""
				update repair_job set status = coalesce(?, status), diagnosis = coalesce(?, diagnosis),
				       labour = coalesce(?, labour), estimate = coalesce(?, estimate),
				       technician_id = coalesce(?, technician_id), promised_on = coalesce(?, promised_on),
				       ready_at = case when ? = 'READY' and ready_at is null then now() else ready_at end,
				       updated_at = now(), version = version + 1 where id = ?""", status,
				SalesService.trim(in.diagnosis(), 1000), labour, estimate, in.technicianId(),
				in.promisedOn() == null ? null : Date.valueOf(in.promisedOn()), status, id);
		String note = SalesService.trim(in.note(), 500);
		if (status != null && !status.equals(old) || note != null) {
			event(id, status != null ? status : old, note);
		}
		if ("READY".equals(status) && !"READY".equals(old) && !Boolean.TRUE.equals(job.get("ready_sms_sent"))) {
			RepairView v = get(id);
			String rawPhone = (String) job.get("customer_phone");
			String shop = jdbc.queryForObject("select business_name from shop_settings where shop_id = ?", String.class,
					TenantContext.requireShopId());
			boolean sent = notifier.sms(rawPhone, "REPAIR_READY",
					shop + ": your " + v.device() + " is ready. Job " + v.number() + ". Amount due Rs " + v.balance()
							+ ".", "repair-ready-" + id, false);
			if (sent) {
				jdbc.update("update repair_job set ready_sms_sent = true where id = ?", id);
			}
		}
		audit.record("REPAIR_UPDATED", "repair_job", id, null, Map.of("status", String.valueOf(status)));
		return get(id);
	}

	@Transactional
	public RepairView addPart(long id, PartInput in) {
		Map<String, Object> job = lock(id);
		requireOpen(job);
		if (in.productId() == null) {
			throw ApiException.field("productId", "required", "Choose the part");
		}
		ProductRef p = catalog.require(in.productId());
		if (p.serial() || "BUNDLE".equals(p.kind()) || "VARIANT_PARENT".equals(p.kind())) {
			throw ApiException.rule("part_kind", "Use an ordinary item as a part (not an IMEI item, bundle or variant group)");
		}
		BigDecimal qty = Amounts.parseQty(in.qty() == null || in.qty().isBlank() ? "1" : in.qty(), "qty");
		if (qty.signum() <= 0) {
			throw ApiException.field("qty", "invalid", "Enter how many");
		}
		ProductCatalog.checkQty(p, catalog.unit(p, null), qty, "qty");
		BigDecimal price = Amounts.parseMoney(in.price(), "price");
		// The part is billed at this price on hand-back without asking again, so only someone who may change
		// prices at the till may set a different one (QA 2026-11-13).
		if (price != null && price.compareTo(p.price()) != 0 && !CurrentActor.hasPermission(Permissions.POS_PRICE_OVERRIDE)) {
			throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "price_change_not_allowed",
					"You may not change the price of " + p.name() + " (list price Rs " + p.price().toPlainString() + ")");
		}
		jdbc.update("insert into repair_part (shop_id, job_id, product_id, qty, price) values (?, ?, ?, ?, ?)",
				TenantContext.requireShopId(), id, p.id(), qty, price == null ? p.price() : price);
		event(id, (String) job.get("status"), "Part added: " + p.name() + " × " + qty.stripTrailingZeros().toPlainString());
		return get(id);
	}

	@Transactional
	public RepairView removePart(long id, long partId) {
		Map<String, Object> job = lock(id);
		requireOpen(job);
		int n = jdbc.update("delete from repair_part where id = ? and job_id = ?", partId, id);
		if (n == 0) {
			throw ApiException.notFound("Part");
		}
		return get(id);
	}

	/**
	 * Hands the device back. With parts or labour an invoice is posted (advance counted as paid; any advance above the
	 * bill is given back in cash); with nothing to charge the advance is given back.
	 */
	@Transactional
	public RepairView deliver(long id, DeliverInput in, String idempotencyKey, ApprovalRequest approval) {
		get(id);
		var earlier = idempotency.claim("REPAIR_DELIVERY", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return get(earlier.get());
		}
		Map<String, Object> job = lock(id);
		if (!Set.of("READY", "NOT_REPAIRABLE").contains(job.get("status"))) {
			throw ApiException.rule("repair_not_ready", "Mark the job ready (or can't repair) before handing it back");
		}
		String number = (String) job.get("number");
		BigDecimal advance = (BigDecimal) job.get("advance");
		BigDecimal labour = (BigDecimal) job.get("labour");
		List<SaleLineInput> lines = new ArrayList<>();
		for (Map<String, Object> part : jdbc.queryForList("select * from repair_part where job_id = ? order by id", id)) {
			lines.add(new SaleLineInput(((Number) part.get("product_id")).longValue(), null,
					Amounts.qtyText((BigDecimal) part.get("qty")), Amounts.text((BigDecimal) part.get("price")), null, null,
					null, null));
		}
		if (labour.signum() > 0) {
			lines.add(new SaleLineInput(labourProduct(), null, "1", Amounts.text(labour), null, null, null, null));
		}
		Map<String, Object> session = tills.requireOpenSession(in == null ? null : in.tillSessionId(), null);
		long sessionId = ((Number) session.get("id")).longValue();
		Long saleId = null;
		BigDecimal giveBack = advance;
		if (!lines.isEmpty()) {
			BigDecimal estimateTotal = BigDecimal.ZERO;
			for (SaleLineInput l : lines) {
				estimateTotal = estimateTotal.add(SaleMath.lineAmount(new BigDecimal(l.qty()), new BigDecimal(l.price())));
			}
			BigDecimal fromAdvance = advance.min(estimateTotal);
			giveBack = advance.subtract(fromAdvance);
			Long customerId = job.get("customer_id") == null ? null : ((Number) job.get("customer_id")).longValue();
			SaleInput sale = new SaleInput(sessionId, customerId, null, (String) job.get("customer_name"),
					(String) job.get("customer_phone"), null, lines, null, null, in == null ? null : in.payments(),
					"Repair " + number, null, null, null);
			List<Tender> advanceTender = fromAdvance.signum() > 0 ? List.of(new Tender("ADVANCE", fromAdvance, number))
					: List.of();
			saleId = sales.postInternal(sale, approval, advanceTender, null, true);
		}
		if (giveBack.signum() > 0) {
			if ("CASH".equals(job.get("advance_method")) || job.get("advance_method") == null) {
				if (tills.expectedCash(sessionId).compareTo(giveBack) < 0) {
					throw ApiException.rule("not_enough_cash", "Not enough cash in the till to give back the advance");
				}
				cash(sessionId, "OUT", giveBack, "Repair advance given back " + number);
			}
		}
		jdbc.update("""
				update repair_job set status = 'DELIVERED', sale_id = ?, closed_at = now(), updated_at = now(),
				       version = version + 1 where id = ?""", saleId, id);
		event(id, "DELIVERED", saleId == null ? "Handed back, nothing charged"
				: "Invoice " + queries.get(saleId).number() + (giveBack.signum() > 0
						? "; advance given back Rs " + giveBack.toPlainString() : ""));
		idempotency.complete("REPAIR_DELIVERY", idempotencyKey, id);
		audit.record("REPAIR_DELIVERED", "repair_job", id, null, Map.of("number", number));
		return get(id);
	}

	@Transactional
	public RepairView cancel(long id, CancelRepair in) {
		Map<String, Object> job = lock(id);
		requireOpen(job);
		String reason = SalesService.trim(in == null ? null : in.reason(), 500);
		if (reason == null) {
			throw ApiException.field("reason", "required", "Write why the job is cancelled");
		}
		BigDecimal advance = (BigDecimal) job.get("advance");
		String number = (String) job.get("number");
		if (advance.signum() > 0 && "CASH".equals(job.get("advance_method"))) {
			Map<String, Object> session = tills.requireOpenSession(in.tillSessionId(), null);
			long sessionId = ((Number) session.get("id")).longValue();
			if (tills.expectedCash(sessionId).compareTo(advance) < 0) {
				throw ApiException.rule("not_enough_cash", "Not enough cash in the till to give back the advance");
			}
			cash(sessionId, "OUT", advance, "Repair advance given back " + number);
		}
		jdbc.update("""
				update repair_job set status = 'CANCELLED', closed_at = now(), updated_at = now(), version = version + 1
				where id = ?""", id);
		event(id, "CANCELLED", reason);
		audit.record("REPAIR_CANCELLED", "repair_job", id, null, Map.of("number", number, "reason", reason));
		return get(id);
	}

	// ------------------------------------------------------------------ reading

	@Transactional(readOnly = true)
	public RepairView get(long id) {
		Map<String, Object> j = jdbc.queryForList("""
				select j.*, b.name as branch_name, u.display_name as technician_name, s.number as sale_number
				from repair_job j join branch b on b.id = j.branch_id left join app_user u on u.id = j.technician_id
				left join sale s on s.id = j.sale_id where j.id = ?""" + branches.filter("j.branch_id"), id).stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Repair job"));
		List<PartView> parts = jdbc.query("""
				select rp.*, p.name, p.sku from repair_part rp join product p on p.id = rp.product_id
				where rp.job_id = ? order by rp.id""",
				(rs, i) -> new PartView(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
						rs.getString("sku"), Amounts.qtyText(rs.getBigDecimal("qty")), Amounts.text(rs.getBigDecimal("price")),
						Amounts.text(SaleMath.lineAmount(rs.getBigDecimal("qty"), rs.getBigDecimal("price")))),
				id);
		List<EventView> events = jdbc.query("select * from repair_event where job_id = ? order by id",
				(rs, i) -> new EventView(rs.getString("status"), rs.getString("note"), rs.getString("actor_name"),
						rs.getTimestamp("at").toInstant()),
				id);
		BigDecimal partsTotal = parts.stream().map(p -> new BigDecimal(p.amount())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		BigDecimal labour = (BigDecimal) j.get("labour");
		BigDecimal advance = (BigDecimal) j.get("advance");
		BigDecimal total = partsTotal.add(labour);
		return new RepairView(((Number) j.get("id")).longValue(), (String) j.get("number"),
				((Number) j.get("branch_id")).longValue(), (String) j.get("branch_name"),
				j.get("customer_id") == null ? null : ((Number) j.get("customer_id")).longValue(),
				(String) j.get("customer_name"),
				CurrentActor.hasPermission(Permissions.CUSTOMERS_PII) || CurrentActor.hasPermission(Permissions.REPAIRS_MANAGE)
						? (String) j.get("customer_phone") : CustomerService.maskPhone((String) j.get("customer_phone")),
				(String) j.get("device"),
				(String) j.get("serial"), (String) j.get("problem"), (String) j.get("accessories"),
				(String) j.get("condition_note"), (String) j.get("diagnosis"), Amounts.text((BigDecimal) j.get("estimate")),
				Amounts.text(advance), (String) j.get("advance_method"), Amounts.text(labour), Amounts.text(partsTotal),
				Amounts.text(total), Amounts.text(total.subtract(advance).max(BigDecimal.ZERO)),
				j.get("technician_id") == null ? null : ((Number) j.get("technician_id")).longValue(),
				(String) j.get("technician_name"),
				j.get("promised_on") == null ? null : ((Date) j.get("promised_on")).toLocalDate(),
				(String) j.get("status"), j.get("sale_id") == null ? null : ((Number) j.get("sale_id")).longValue(),
				(String) j.get("sale_number"), Boolean.TRUE.equals(j.get("ready_sms_sent")), (String) j.get("actor_name"),
				((Date) j.get("business_date")).toLocalDate(), ((java.sql.Timestamp) j.get("at")).toInstant(),
				j.get("ready_at") == null ? null : ((java.sql.Timestamp) j.get("ready_at")).toInstant(),
				j.get("closed_at") == null ? null : ((java.sql.Timestamp) j.get("closed_at")).toInstant(), parts, events);
	}

	/**
	 * @param status a status, OPEN (every open job) or null for all
	 * @param q job number, customer name or phone, device or IMEI
	 */
	@Transactional(readOnly = true)
	public PageResponse<RepairView> list(String status, Long technicianId, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if ("OPEN".equalsIgnoreCase(status)) {
			where.append(" and status in ('RECEIVED', 'CHECKING', 'WAITING_PARTS', 'REPAIRING', 'READY', 'NOT_REPAIRABLE')");
		}
		else if (status != null && !status.isBlank()) {
			where.append(" and status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (technicianId != null) {
			where.append(" and technician_id = ?");
			args.add(technicianId);
		}
		if (q != null && !q.isBlank()) {
			String like = "%" + q.trim().toLowerCase() + "%";
			where.append(" and (upper(number) = ? or lower(customer_name) like ? or customer_phone like ? or lower(device)"
					+ " like ? or upper(serial) = ?)");
			args.add(q.trim().toUpperCase());
			args.add(like);
			args.add("%" + q.trim() + "%");
			args.add(like);
			args.add(q.trim().toUpperCase());
		}
		long total = jdbc.queryForObject("select count(*) from repair_job" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from repair_job" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	@Transactional(readOnly = true)
	public RepairCounts counts() {
		Map<String, Object> m = jdbc.queryForMap("""
				select count(*) filter (where status in ('RECEIVED', 'CHECKING', 'WAITING_PARTS', 'REPAIRING')) as open,
				       count(*) filter (where status in ('READY', 'NOT_REPAIRABLE')) as ready,
				       count(*) filter (where status in ('RECEIVED', 'CHECKING', 'WAITING_PARTS', 'REPAIRING')
				                        and promised_on < ?) as overdue
				from repair_job where 1=1""" + branches.filter("branch_id"), Date.valueOf(time.today()));
		return new RepairCounts(((Number) m.get("open")).longValue(), ((Number) m.get("ready")).longValue(),
				((Number) m.get("overdue")).longValue());
	}

	// ------------------------------------------------------------------ helpers

	private Map<String, Object> lock(long id) {
		Map<String, Object> job = jdbc.queryForList("select * from repair_job where id = ?" + branches.filter("branch_id")
				+ " for update", id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Repair job"));
		return job;
	}

	private static void requireOpen(Map<String, Object> job) {
		if (!OPEN.contains(job.get("status"))) {
			throw ApiException.rule("repair_closed", "This job is already " + job.get("status").toString().toLowerCase());
		}
	}

	private void checkTechnician(Long userId) {
		if (userId == null) {
			return;
		}
		Integer ok = jdbc.queryForObject("select count(*) from app_user where id = ? and status = 'ACTIVE'", Integer.class,
				userId);
		if (ok == null || ok == 0) {
			throw ApiException.field("technicianId", "invalid", "Choose an active user as the technician");
		}
	}

	private void event(long jobId, String status, String note) {
		jdbc.update("insert into repair_event (shop_id, job_id, status, note, actor_name) values (?, ?, ?, ?, ?)",
				TenantContext.requireShopId(), jobId, status, note, CurrentActor.name());
	}

	private void cash(long sessionId, String kind, BigDecimal amount, String reason) {
		jdbc.update("""
				insert into cash_movement (shop_id, till_session_id, kind, amount, reason, user_id, actor_name)
				values (?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), sessionId, kind, amount, reason,
				TillService.userId(), CurrentActor.name());
	}

	/** The "Repair labour" service item (made the first time it is needed). */
	@Transactional(propagation = Propagation.MANDATORY)
	long labourProduct() {
		return ServiceItems.ensure(jdbc, LABOUR_SKU, "Repair labour");
	}
}
