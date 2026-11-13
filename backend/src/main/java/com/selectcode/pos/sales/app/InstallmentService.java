package com.selectcode.pos.sales.app;

import com.selectcode.pos.customers.app.CustomerLedger;
import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.sales.app.CounterService.CustomerPaymentInput;
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
import com.selectcode.pos.shop.app.ShopConfigService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Installments (MOB-08, ELE-03). A plan is made from an invoice left on the customer's account: the amount still owed
 * plus an optional markup is split into monthly installments. A guarantor is required. Receiving an installment is a
 * customer payment (it clears the plan's invoice first) that also marks the schedule; installments paid after their
 * grace days carry a late fee per day late, charged to the customer's account when the next payment is taken.
 * Markup and late fees go on the customer's ledger and count as other income.
 */
@Service
public class InstallmentService {

	public record InstallmentPlanInput(Long saleId, Integer months, String markupPercent, LocalDate firstDue, Integer graceDays,
			String lateFeePerDay, String guarantorName, String guarantorCnic, String guarantorPhone,
			String guarantorAddress, String notes) {
	}

	public record InstallmentPayment(String amount, String method, String reference, Long tillSessionId) {
	}

	public record CancelPlan(String reason) {
	}

	public record InstallmentDueView(long id, int no, LocalDate dueOn, String amount, String lateFee, String paid, String left,
			LocalDate paidOn, String state, int daysLate) {
	}

	public record InstallmentReceiptView(long id, long customerPaymentId, String paymentNumber, String amount, String lateFee,
			String method, String actorName, Instant at) {
	}

	public record InstallmentPlanView(long id, String number, long saleId, String saleNumber, long customerId,
			String customerName, String customerPhone, String principal, String markupPercent, String markup,
			String total, String paid, String left, String lateFees, int months, LocalDate firstDue, int graceDays,
			String lateFeePerDay, String status, String guarantorName, String guarantorCnic, String guarantorPhone,
			String guarantorAddress, String notes, LocalDate nextDueOn, String nextDueAmount, int overdueCount,
			String overdueAmount, String actorName, LocalDate date, List<InstallmentDueView> dues, List<InstallmentReceiptView> receipts) {
	}

	/** One row of the due list (an installment not fully paid). */
	public record InstallmentDueRow(long planId, String planNumber, long customerId, String customerName, String customerPhone,
			int no, LocalDate dueOn, String left, int daysLate, String lateFee) {
	}

	private final JdbcTemplate jdbc;

	private final CounterService counter;

	private final CustomerLedger ledger;

	private final ShopConfigService numbering;

	private final Idempotency idempotency;

	private final AuditService audit;

	private final com.selectcode.pos.shop.app.BranchAccess branches;

	private final ShopTime time;

	InstallmentService(JdbcTemplate jdbc, CounterService counter, CustomerLedger ledger,
			ShopConfigService numbering, Idempotency idempotency, AuditService audit, ShopTime time,
			com.selectcode.pos.shop.app.BranchAccess branches) {
		this.branches = branches;
		this.jdbc = jdbc;
		this.counter = counter;
		this.ledger = ledger;
		this.numbering = numbering;
		this.idempotency = idempotency;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public InstallmentPlanView create(InstallmentPlanInput in) {
		if (in.saleId() == null) {
			throw ApiException.field("saleId", "required", "Choose the invoice");
		}
		Map<String, Object> sale = jdbc.queryForList("select * from sale where id = ?" + branches.filter("branch_id")
				+ " for update", in.saleId()).stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Invoice"));
		if (!"POSTED".equals(sale.get("status"))) {
			throw ApiException.rule("sale_cancelled", "This invoice was cancelled");
		}
		if (sale.get("customer_id") == null) {
			throw ApiException.rule("no_customer", "The invoice must be on a customer's account");
		}
		BigDecimal principal = (BigDecimal) sale.get("due");
		if (principal.signum() <= 0) {
			throw ApiException.rule("nothing_due", "Nothing is owed on this invoice");
		}
		Integer active = jdbc.queryForObject(
				"select count(*) from installment_plan where sale_id = ? and status <> 'CANCELLED'", Integer.class,
				in.saleId());
		if (active != null && active > 0) {
			throw ApiException.rule("plan_exists", "This invoice already has an installment plan");
		}
		int months = in.months() == null ? 0 : in.months();
		if (months < 1 || months > 60) {
			throw ApiException.field("months", "invalid", "Choose 1 to 60 months");
		}
		BigDecimal markupPct = Amounts.parsePercent(in.markupPercent(), "markupPercent");
		markupPct = markupPct == null ? BigDecimal.ZERO : markupPct;
		BigDecimal markup = Amounts.money(principal.multiply(markupPct).divide(SaleMath.HUNDRED));
		BigDecimal total = principal.add(markup);
		LocalDate today = time.today();
		LocalDate first = in.firstDue() == null ? today.plusMonths(1) : in.firstDue();
		if (first.isBefore(today)) {
			throw ApiException.field("firstDue", "invalid", "The first installment cannot be due in the past");
		}
		int grace = in.graceDays() == null ? 0 : in.graceDays();
		if (grace < 0 || grace > 60) {
			throw ApiException.field("graceDays", "invalid", "Grace days are 0 to 60");
		}
		BigDecimal lateFee = Amounts.nz(Amounts.parseMoney(in.lateFeePerDay(), "lateFeePerDay"));
		String gName = SalesService.trim(in.guarantorName(), 200);
		if (gName == null) {
			throw ApiException.field("guarantorName", "required", "Enter the guarantor's name");
		}
		String digits = in.guarantorCnic() == null ? "" : in.guarantorCnic().replaceAll("[^0-9]", "");
		if (digits.length() != 13) {
			throw ApiException.field("guarantorCnic", "required", "Enter the guarantor's CNIC (13 digits)");
		}
		String cnic = digits.substring(0, 5) + "-" + digits.substring(5, 12) + "-" + digits.substring(12);
		String gPhone = SalesService.trim(in.guarantorPhone(), 30);
		if (gPhone == null || gPhone.replaceAll("[^0-9]", "").length() < 10) {
			throw ApiException.field("guarantorPhone", "required", "Enter the guarantor's phone");
		}
		long customerId = ((Number) sale.get("customer_id")).longValue();
		String number = numbering.nextNumber("INSTALLMENT_PLAN", ((Number) sale.get("branch_id")).longValue());
		long id = jdbc.queryForObject("""
				insert into installment_plan (shop_id, number, sale_id, customer_id, principal, markup_pct, markup, total,
				                              months, first_due, grace_days, late_fee_per_day, guarantor_name,
				                              guarantor_cnic, guarantor_phone, guarantor_address, notes, created_by,
				                              actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, in.saleId(), customerId, principal, markupPct, markup, total,
				months, Date.valueOf(first), grace, lateFee, gName, cnic, gPhone,
				SalesService.trim(in.guarantorAddress(), 500), SalesService.trim(in.notes(), 500), TillService.userId(),
				CurrentActor.name(), Date.valueOf(today));
		List<BigDecimal> amounts = SaleMath.spread(Collections.nCopies(months, BigDecimal.ONE), total);
		for (int i = 0; i < months; i++) {
			jdbc.update("""
					insert into installment_due (shop_id, plan_id, no, due_on, amount) values (?, ?, ?, ?, ?)""",
					TenantContext.requireShopId(), id, i + 1, Date.valueOf(first.plusMonths(i)), amounts.get(i));
		}
		if (markup.signum() > 0) {
			ledger.post(customerId, "INSTALLMENT_MARKUP", id, number, "Installment markup " + number, markup, null);
		}
		audit.record("INSTALLMENT_PLAN", "installment_plan", id, null,
				Map.of("number", number, "total", total.toPlainString(), "months", months));
		return get(id);
	}

	/** Takes an installment: late fees due now are charged first, then the money pays the schedule in order. */
	@Transactional
	public InstallmentPlanView receive(long id, InstallmentPayment in, String idempotencyKey) {
		get(id);
		var earlier = idempotency.claim("INSTALLMENT", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return get(earlier.get());
		}
		Map<String, Object> plan = lock(id);
		if (!"ACTIVE".equals(plan.get("status"))) {
			throw ApiException.rule("plan_closed", "This plan is " + plan.get("status").toString().toLowerCase());
		}
		BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
		if (amount.signum() <= 0) {
			throw ApiException.field("amount", "invalid", "Enter an amount above zero");
		}
		long customerId = ((Number) plan.get("customer_id")).longValue();
		String number = (String) plan.get("number");
		LocalDate today = time.today();
		BigDecimal fees = chargeLateFees(id, plan, today);
		if (fees.signum() > 0) {
			ledger.post(customerId, "LATE_FEE", id, number, "Late fee on installments " + number, fees, null);
		}
		List<Map<String, Object>> dues = jdbc.queryForList("""
				select * from installment_due where plan_id = ? and paid < amount + late_fee order by no for update""", id);
		BigDecimal outstanding = dues.stream()
			.map(d -> ((BigDecimal) d.get("amount")).add((BigDecimal) d.get("late_fee")).subtract((BigDecimal) d.get("paid")))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (amount.compareTo(outstanding) > 0) {
			throw ApiException.rule("more_than_left", "Only Rs " + outstanding.toPlainString() + " is left on this plan");
		}
		long paymentId = counter.receiveInternal(new CustomerPaymentInput(customerId, amount.toPlainString(), in.method(),
				in.reference(), in.tillSessionId(), "Installment " + number), ((Number) plan.get("sale_id")).longValue());
		BigDecimal left = amount;
		for (Map<String, Object> d : dues) {
			if (left.signum() <= 0) {
				break;
			}
			BigDecimal open = ((BigDecimal) d.get("amount")).add((BigDecimal) d.get("late_fee"))
				.subtract((BigDecimal) d.get("paid"));
			BigDecimal take = left.min(open);
			jdbc.update("""
					update installment_due set paid = paid + ?, paid_on = case when paid + ? >= amount + late_fee then ?
					       else paid_on end where id = ?""", take, take, Date.valueOf(today), d.get("id"));
			left = left.subtract(take);
		}
		jdbc.update("""
				insert into installment_receipt (shop_id, plan_id, customer_payment_id, amount, late_fee)
				values (?, ?, ?, ?, ?)""", TenantContext.requireShopId(), id, paymentId, amount, fees);
		Integer open = jdbc.queryForObject(
				"select count(*) from installment_due where plan_id = ? and paid < amount + late_fee", Integer.class, id);
		if (open != null && open == 0) {
			jdbc.update("update installment_plan set status = 'COMPLETED', updated_at = now(), version = version + 1 where id = ?",
					id);
		}
		idempotency.complete("INSTALLMENT", idempotencyKey, id);
		audit.record("INSTALLMENT_RECEIVED", "installment_plan", id, null,
				Map.of("number", number, "amount", amount.toPlainString(), "lateFee", fees.toPlainString()));
		return get(id);
	}

	/** Late fees for installments past their grace days and not yet charged; returns what was added. */
	private BigDecimal chargeLateFees(long planId, Map<String, Object> plan, LocalDate today) {
		BigDecimal perDay = (BigDecimal) plan.get("late_fee_per_day");
		int grace = ((Number) plan.get("grace_days")).intValue();
		if (perDay.signum() == 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (Map<String, Object> d : jdbc.queryForList("""
				select id, due_on from installment_due where plan_id = ? and late_fee = 0 and paid < amount order by no""",
				planId)) {
			LocalDate due = ((Date) d.get("due_on")).toLocalDate();
			long days = lateDays(due, grace, today);
			if (days <= 0) {
				continue;
			}
			BigDecimal fee = Amounts.money(perDay.multiply(BigDecimal.valueOf(days)));
			jdbc.update("update installment_due set late_fee = ? where id = ?", fee, d.get("id"));
			total = total.add(fee);
		}
		return total;
	}

	/** Days late after the grace days (0 when not late). */
	static long lateDays(LocalDate due, int graceDays, LocalDate today) {
		return Math.max(0, ChronoUnit.DAYS.between(due.plusDays(graceDays), today));
	}

	/** Cancels a plan nothing was paid on yet; the markup comes off the customer's account. */
	@Transactional
	public InstallmentPlanView cancel(long id, CancelPlan in) {
		Map<String, Object> plan = lock(id);
		if (!"ACTIVE".equals(plan.get("status"))) {
			throw ApiException.rule("plan_closed", "This plan is " + plan.get("status").toString().toLowerCase());
		}
		String reason = SalesService.trim(in == null ? null : in.reason(), 300);
		if (reason == null) {
			throw ApiException.field("reason", "required", "Write why the plan is cancelled");
		}
		Integer receipts = jdbc.queryForObject("select count(*) from installment_receipt where plan_id = ?", Integer.class,
				id);
		if (receipts != null && receipts > 0) {
			throw ApiException.rule("plan_has_payments", "Installments were already paid on this plan; it cannot be cancelled");
		}
		BigDecimal markup = (BigDecimal) plan.get("markup");
		String number = (String) plan.get("number");
		if (markup.signum() > 0) {
			ledger.post(((Number) plan.get("customer_id")).longValue(), "INSTALLMENT_MARKUP", id, number,
					"Markup removed: plan " + number + " cancelled", null, markup);
		}
		jdbc.update("""
				update installment_plan set status = 'CANCELLED', notes = left(coalesce(notes || ' · ', '') || ?, 500),
				       updated_at = now(), version = version + 1 where id = ?""", "Cancelled: " + reason, id);
		audit.record("INSTALLMENT_CANCELLED", "installment_plan", id, null, Map.of("number", number, "reason", reason));
		return get(id);
	}

	// ------------------------------------------------------------------ reading

	@Transactional(readOnly = true)
	public InstallmentPlanView get(long id) {
		boolean pii = CurrentActor.hasPermission(Permissions.CUSTOMERS_PII);
		LocalDate today = time.today();
		Map<String, Object> p = jdbc.queryForList("""
				select p.*, s.number as sale_number, c.name as customer_name, c.phone as customer_phone
				from installment_plan p join sale s on s.id = p.sale_id join customer c on c.id = p.customer_id
				where p.id = ?""" + branches.filter("s.branch_id"), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Installment plan"));
		int grace = ((Number) p.get("grace_days")).intValue();
		BigDecimal perDay = (BigDecimal) p.get("late_fee_per_day");
		List<InstallmentDueView> dues = jdbc.query("select * from installment_due where plan_id = ? order by no", (rs, i) -> {
			BigDecimal amount = rs.getBigDecimal("amount");
			BigDecimal fee = rs.getBigDecimal("late_fee");
			BigDecimal paid = rs.getBigDecimal("paid");
			BigDecimal left = amount.add(fee).subtract(paid);
			LocalDate due = rs.getObject("due_on", LocalDate.class);
			int late = left.signum() > 0 ? (int) lateDays(due, grace, today) : 0;
			String state = left.signum() <= 0 ? "PAID"
					: late > 0 ? "LATE" : !due.isAfter(today) ? "DUE" : paid.signum() > 0 ? "PART_PAID" : "UPCOMING";
			BigDecimal shownFee = fee.signum() == 0 && late > 0 ? Amounts.money(perDay.multiply(BigDecimal.valueOf(late)))
					: fee;
			return new InstallmentDueView(rs.getLong("id"), rs.getInt("no"), due, Amounts.text(amount), Amounts.text(shownFee),
					Amounts.text(paid), Amounts.text(left), rs.getObject("paid_on", LocalDate.class), state, late);
		}, id);
		List<InstallmentReceiptView> receipts = jdbc.query("""
				select r.*, cp.number as payment_number, cp.method_key, cp.actor_name
				from installment_receipt r join customer_payment cp on cp.id = r.customer_payment_id
				where r.plan_id = ? order by r.id""",
				(rs, i) -> new InstallmentReceiptView(rs.getLong("id"), rs.getLong("customer_payment_id"),
						rs.getString("payment_number"), Amounts.text(rs.getBigDecimal("amount")),
						Amounts.text(rs.getBigDecimal("late_fee")), rs.getString("method_key"), rs.getString("actor_name"),
						rs.getTimestamp("at").toInstant()),
				id);
		BigDecimal total = (BigDecimal) p.get("total");
		BigDecimal fees = jdbc.queryForObject("select coalesce(sum(late_fee), 0) from installment_due where plan_id = ?",
				BigDecimal.class, id);
		BigDecimal paid = jdbc.queryForObject("select coalesce(sum(paid), 0) from installment_due where plan_id = ?",
				BigDecimal.class, id);
		InstallmentDueView next = dues.stream().filter(d -> new BigDecimal(d.left()).signum() > 0).findFirst().orElse(null);
		List<InstallmentDueView> overdue = dues.stream().filter(d -> "LATE".equals(d.state()) || "DUE".equals(d.state())).toList();
		String cnic = (String) p.get("guarantor_cnic");
		return new InstallmentPlanView(((Number) p.get("id")).longValue(), (String) p.get("number"),
				((Number) p.get("sale_id")).longValue(), (String) p.get("sale_number"),
				((Number) p.get("customer_id")).longValue(), (String) p.get("customer_name"),
				pii ? (String) p.get("customer_phone") : CustomerService.maskPhone((String) p.get("customer_phone")),
				Amounts.text((BigDecimal) p.get("principal")), Amounts.percentText((BigDecimal) p.get("markup_pct")),
				Amounts.text((BigDecimal) p.get("markup")), Amounts.text(total), Amounts.text(paid),
				Amounts.text(total.add(fees).subtract(paid)), Amounts.text(fees), ((Number) p.get("months")).intValue(),
				((Date) p.get("first_due")).toLocalDate(), grace, Amounts.text(perDay), (String) p.get("status"),
				(String) p.get("guarantor_name"), pii ? cnic : CustomerService.maskCnic(cnic),
				pii ? (String) p.get("guarantor_phone") : CustomerService.maskPhone((String) p.get("guarantor_phone")),
				pii ? (String) p.get("guarantor_address") : null, (String) p.get("notes"),
				next == null ? null : next.dueOn(), next == null ? null : next.left(), overdue.size(),
				Amounts.text(overdue.stream().map(d -> new BigDecimal(d.left())).reduce(BigDecimal.ZERO, BigDecimal::add)),
				(String) p.get("actor_name"), ((Date) p.get("business_date")).toLocalDate(), dues, receipts);
	}

	@Transactional(readOnly = true)
	public PageResponse<InstallmentPlanView> list(String status, Long customerId, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("s.branch_id"));
		List<Object> args = new ArrayList<>();
		if (status != null && !status.isBlank()) {
			where.append(" and p.status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (customerId != null) {
			where.append(" and p.customer_id = ?");
			args.add(customerId);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (upper(p.number) = ? or lower(c.name) like ? or c.phone like ?)");
			args.add(q.trim().toUpperCase());
			args.add("%" + q.trim().toLowerCase() + "%");
			args.add("%" + q.trim() + "%");
		}
		String from = " from installment_plan p join customer c on c.id = p.customer_id join sale s on s.id = p.sale_id";
		long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select p.id" + from + where + " order by p.id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	/** Installments of active plans due up to {@code to} and not fully paid (overdue first). */
	@Transactional(readOnly = true)
	public List<InstallmentDueRow> dueList(LocalDate to, boolean overdueOnly) {
		LocalDate today = time.today();
		LocalDate until = overdueOnly ? today.minusDays(1) : to == null ? today.plusDays(7) : to;
		boolean pii = CurrentActor.hasPermission(Permissions.CUSTOMERS_PII);
		return jdbc.query("""
				select d.*, p.number, p.customer_id, p.grace_days, p.late_fee_per_day, c.name, c.phone
				from installment_due d join installment_plan p on p.id = d.plan_id join customer c on c.id = p.customer_id
				join sale s on s.id = p.sale_id
				where p.status = 'ACTIVE' and d.paid < d.amount + d.late_fee and d.due_on <= ?""" + branches.filter("s.branch_id")
				+ " order by d.due_on, p.id limit 1000", (rs, i) -> {
			LocalDate due = rs.getObject("due_on", LocalDate.class);
			int late = (int) lateDays(due, rs.getInt("grace_days"), today);
			BigDecimal fee = rs.getBigDecimal("late_fee");
			if (fee.signum() == 0 && late > 0) {
				fee = Amounts.money(rs.getBigDecimal("late_fee_per_day").multiply(BigDecimal.valueOf(late)));
			}
			return new InstallmentDueRow(rs.getLong("plan_id"), rs.getString("number"), rs.getLong("customer_id"),
					rs.getString("name"), pii ? rs.getString("phone") : CustomerService.maskPhone(rs.getString("phone")),
					rs.getInt("no"), due, Amounts.text(rs.getBigDecimal("amount").add(rs.getBigDecimal("late_fee"))
						.subtract(rs.getBigDecimal("paid"))), late, Amounts.text(fee));
		}, Date.valueOf(until));
	}

	private Map<String, Object> lock(long id) {
		get(id);
		return jdbc.queryForList("select * from installment_plan where id = ? for update", id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Installment plan"));
	}
}
