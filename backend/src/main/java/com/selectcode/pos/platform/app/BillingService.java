package com.selectcode.pos.platform.app;

import com.selectcode.pos.notifications.app.Notifier;
import com.selectcode.pos.platform.app.ShopAdminService.SubscriptionUpdate;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.jobs.Jobs;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Subscriptions and their money (BIL-05..10, ONB-04, ONB-06):
 * <ul>
 * <li>an invoice for the next period is made a week before the due date; reminders go out 7, 3 and 1 days before
 * and once when overdue (email to the shop and a bell message to the owner);</li>
 * <li>the daily status job moves Trial / Active → Grace when unpaid after the due date, and Grace → Read-only after
 * the grace days; recording a payment makes the shop Active again and moves the next due date;</li>
 * <li>upgrades take effect at once with a pro-rata invoice; downgrades wait for the next cycle;</li>
 * <li>closed shops are deleted after 90 days and activity history after 2 years.</li>
 * </ul>
 */
@Service
public class BillingService {

	private static final Logger log = LoggerFactory.getLogger(BillingService.class);

	public record InvoiceView(long id, long shopId, String shopCode, String shopName, String number, String kind,
			String planName, String cycle, LocalDate periodFrom, LocalDate periodTo, String amount, String discount,
			String total, String paid, String balance, LocalDate dueOn, String status, Instant paidAt, String note) {
	}

	public record SubscriptionPaymentInput(String amount, String method, String reference, LocalDate receivedOn) {
	}

	public record InvoiceInput(String amount, LocalDate dueOn, String note) {
	}

	public record PlanChange(String planKey, String cycle) {
	}

	public record PlanChangeResult(String effect, String planKey, LocalDate effectiveOn, InvoiceView invoice) {
	}

	public record StatusOverride(String status, String reason) {
	}

	public record JobResult(int invoicesMade, int remindersSent, int statusChanges, int plansApplied) {
	}

	private final JdbcTemplate jdbc;

	private final com.selectcode.pos.shared.files.FileStorage storage;

	private final ShopAdminService shops;

	private final ShopModuleService modules;

	private final Notifier notifier;

	private final AuditService audit;

	private final Jobs jobs;

	private final Clock clock;

	private final TransactionTemplate tx;

	private final TransactionTemplate inShopTx;

	BillingService(JdbcTemplate jdbc, ShopAdminService shops, ShopModuleService modules, Notifier notifier,
			AuditService audit, Jobs jobs, Clock clock, PlatformTransactionManager transactions,
			com.selectcode.pos.shared.files.FileStorage storage) {
		this.storage = storage;
		this.jdbc = jdbc;
		this.shops = shops;
		this.modules = modules;
		this.notifier = notifier;
		this.audit = audit;
		this.jobs = jobs;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactions);
		this.inShopTx = new TransactionTemplate(transactions);
		this.inShopTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	LocalDate today() {
		return LocalDate.now(clock.withZone(ShopAdminService.ZONE));
	}

	// ------------------------------------------------------------------ invoices and payments

	public List<InvoiceView> invoices(Long shopId, String status) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (shopId != null) {
			where.append(" and i.shop_id = ?");
			args.add(shopId);
		}
		if (status != null && !status.isBlank()) {
			where.append(" and i.status = ?");
			args.add(status.trim().toUpperCase());
		}
		return jdbc.query("""
				select i.*, s.code, s.name as shop_name, p.name as plan_name from subscription_invoice i
				join shop s on s.id = i.shop_id join plan p on p.id = i.plan_id""" + where
				+ " order by i.due_on desc, i.id desc limit 500", (rs, n) -> invoice(rs), args.toArray());
	}

	public InvoiceView invoice(long id) {
		return jdbc.query("""
				select i.*, s.code, s.name as shop_name, p.name as plan_name from subscription_invoice i
				join shop s on s.id = i.shop_id join plan p on p.id = i.plan_id where i.id = ?""", (rs, n) -> invoice(rs),
				id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Invoice"));
	}

	/** A manual invoice (setup fee, add-ons, adjustments). */
	public InvoiceView createManual(long shopId, InvoiceInput in) {
		Long id = tx.execute(s -> {
			Map<String, Object> sub = subscription(shopId);
			BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
			LocalDate due = in.dueOn() == null ? today() : in.dueOn();
			return insertInvoice(shopId, "MANUAL", ((Number) sub.get("plan_id")).longValue(), (String) sub.get("cycle"),
					due, due, amount, BigDecimal.ZERO, due, in.note());
		});
		return invoice(id);
	}

	/** Records a payment (BIL-05); a fully paid renewal moves the next due date and makes the shop Active. */
	public InvoiceView recordPayment(long invoiceId, SubscriptionPaymentInput in) {
		long shopId = tx.execute(s -> {
			Map<String, Object> inv = jdbc.queryForList("select * from subscription_invoice where id = ? for update",
					invoiceId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Invoice"));
			if (!"OPEN".equals(inv.get("status"))) {
				throw ApiException.rule("invoice_closed", "This invoice is " + inv.get("status").toString().toLowerCase());
			}
			BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
			if (amount.signum() <= 0) {
				throw ApiException.field("amount", "invalid", "Enter an amount above zero");
			}
			String method = in.method() == null ? "CASH" : in.method().trim().toUpperCase();
			if (!Set.of("CASH", "BANK", "JAZZCASH", "EASYPAISA", "CHEQUE").contains(method)) {
				throw ApiException.field("method", "invalid", "Choose cash, bank, JazzCash, Easypaisa or cheque");
			}
			BigDecimal total = (BigDecimal) inv.get("total");
			BigDecimal paid = ((BigDecimal) inv.get("paid")).add(amount);
			if (paid.compareTo(total) > 0) {
				throw ApiException.field("amount", "too_large", "More than the invoice balance");
			}
			long shop = ((Number) inv.get("shop_id")).longValue();
			jdbc.update("""
					insert into subscription_payment (shop_id, invoice_id, amount, method, reference, received_on, recorded_by)
					values (?, ?, ?, ?, ?, ?, ?)""", shop, invoiceId, amount, method,
					in.reference() == null ? null : in.reference().trim(),
					Date.valueOf(in.receivedOn() == null ? today() : in.receivedOn()), CurrentActor.id());
			boolean full = paid.compareTo(total) == 0;
			jdbc.update("update subscription_invoice set paid = ?, status = ?, paid_at = case when ? then now() end where id = ?",
					paid, full ? "PAID" : "OPEN", full, invoiceId);
			if (full && "RENEWAL".equals(inv.get("kind"))) {
				LocalDate periodTo = ((Date) inv.get("period_to")).toLocalDate();
				jdbc.update("update subscription set next_due_on = greatest(next_due_on, ?), updated_at = now() where shop_id = ?",
						Date.valueOf(periodTo.plusDays(1)), shop);
			}
			if (full) {
				reconsider(shop, "Payment received");
			}
			audit.recordPlatform("SUBSCRIPTION_PAYMENT", shop,
					Map.of("invoice", inv.get("number"), "amount", amount.toPlainString(), "method", method));
			return shop;
		});
		InvoiceView view = invoice(invoiceId);
		inShop(shopId, () -> {
			String email = jdbc.queryForObject("select email from shop where id = ?", String.class, shopId);
			notifier.email(email, "subscription_receipt", "Payment received for " + view.number(),
					"Thank you. We received Rs " + Amounts.text(Amounts.requireMoney(in.amount(), "amount")) + " for invoice "
							+ view.number() + ". Balance: Rs " + view.balance() + ".",
					null);
		});
		return view;
	}

	/** Moves the shop out of Grace / Read-only when nothing is overdue any more. */
	private void reconsider(long shopId, String reason) {
		Map<String, Object> shop = jdbc.queryForMap("select status from shop where id = ?", shopId);
		String status = (String) shop.get("status");
		if (!Set.of("TRIAL", "GRACE", "READ_ONLY").contains(status)) {
			return;
		}
		Integer overdue = jdbc.queryForObject("""
				select count(*) from subscription_invoice where shop_id = ? and status = 'OPEN' and due_on < ?""",
				Integer.class, shopId, Date.valueOf(today()));
		if (overdue != null && overdue == 0) {
			setStatus(shopId, "ACTIVE", reason);
		}
	}

	// ------------------------------------------------------------------ plan changes (BIL-09)

	/**
	 * Upgrades (a more expensive plan) apply now with an invoice for the price difference over the rest of the
	 * period; downgrades are booked for the next cycle, and nothing is deleted when the lower plan's limits are
	 * already exceeded (BIL-08).
	 */
	public PlanChangeResult changePlan(long shopId, PlanChange in) {
		Map<String, Object> sub = subscription(shopId);
		Map<String, Object> target = jdbc.queryForList("select * from plan where plan_key = ? and active",
				in.planKey() == null ? "" : in.planKey().trim()).stream().findFirst()
			.orElseThrow(() -> ApiException.field("planKey", "invalid", "Choose a plan"));
		String cycle = in.cycle() == null || in.cycle().isBlank() ? (String) sub.get("cycle")
				: in.cycle().trim().toUpperCase();
		if (!Set.of("MONTH", "YEAR").contains(cycle)) {
			throw ApiException.field("cycle", "invalid", "Choose MONTH or YEAR");
		}
		BigDecimal newPrice = (BigDecimal) target.get("YEAR".equals(cycle) ? "price_year" : "price_month");
		BigDecimal oldPrice = (BigDecimal) sub.get("price");
		long currentPlan = ((Number) sub.get("plan_id")).longValue();
		long targetId = ((Number) target.get("id")).longValue();
		if (targetId == currentPlan && cycle.equals(sub.get("cycle"))) {
			throw ApiException.rule("same_plan", "The shop is already on this plan");
		}
		LocalDate today = today();
		LocalDate nextDue = ((Date) sub.get("next_due_on")).toLocalDate();
		if (newPrice.compareTo(oldPrice) > 0 || !cycle.equals(sub.get("cycle")) && newPrice.compareTo(oldPrice) >= 0) {
			shops.changeSubscription(shopId, new SubscriptionUpdate((String) target.get("plan_key"), cycle, null, null, null));
			InvoiceView invoice = null;
			String status = jdbc.queryForObject("select status from shop where id = ?", String.class, shopId);
			if (!"TRIAL".equals(status) && nextDue.isAfter(today)) {
				LocalDate periodStart = "YEAR".equals(sub.get("cycle")) ? nextDue.minusYears(1) : nextDue.minusMonths(1);
				long periodDays = Math.max(1, ChronoUnit.DAYS.between(periodStart, nextDue));
				long remaining = ChronoUnit.DAYS.between(today, nextDue);
				BigDecimal difference = newPrice.subtract(oldPrice).max(BigDecimal.ZERO)
					.multiply(BigDecimal.valueOf(remaining))
					.divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
				if (difference.signum() > 0) {
					Long id = tx.execute(s -> insertInvoice(shopId, "UPGRADE", targetId, cycle, today, nextDue.minusDays(1),
							difference, BigDecimal.ZERO, today, "Upgrade for the rest of the period"));
					invoice = invoice(id);
				}
			}
			tx.executeWithoutResult(s -> jdbc.update(
					"update subscription set pending_plan_id = null, pending_cycle = null where shop_id = ?", shopId));
			return new PlanChangeResult("UPGRADED", (String) target.get("plan_key"), today, invoice);
		}
		tx.executeWithoutResult(s -> {
			jdbc.update("update subscription set pending_plan_id = ?, pending_cycle = ?, updated_at = now() where shop_id = ?",
					targetId, cycle, shopId);
			audit.recordPlatform("PLAN_DOWNGRADE_BOOKED", shopId,
					Map.of("plan", target.get("plan_key"), "from", nextDue.toString()));
		});
		return new PlanChangeResult("DOWNGRADE_BOOKED", (String) target.get("plan_key"), nextDue, null);
	}

	/** Super admin override of the automatic status, with a reason (BIL-07). */
	public void overrideStatus(long shopId, StatusOverride in) {
		String status = in.status() == null ? "" : in.status().trim().toUpperCase();
		if (!Set.of("TRIAL", "ACTIVE", "GRACE", "READ_ONLY").contains(status)) {
			throw ApiException.field("status", "invalid", "Choose trial, active, grace or read-only (suspend separately)");
		}
		if (in.reason() == null || in.reason().isBlank()) {
			throw ApiException.field("reason", "required", "Write why the status is changed");
		}
		tx.executeWithoutResult(s -> {
			String current = jdbc.queryForList("select status from shop where id = ?", String.class, shopId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Shop"));
			if (Set.of("SUSPENDED", "CLOSED").contains(current)) {
				throw ApiException.rule("shop_stopped", "Reactivate the shop first");
			}
			setStatus(shopId, status, in.reason().trim());
		});
	}

	// ------------------------------------------------------------------ the daily job

	@Scheduled(cron = "0 30 0 * * *", zone = "Asia/Karachi")
	public void nightly() {
		if (!jobs.enabled()) {
			return;
		}
		jobs.exclusive("billing", () -> {
			JobResult r = runDaily();
			log.info("billing job: {}", r);
			retention();
		});
	}

	/** Invoices, reminders, status moves and booked downgrades for every shop (callable from tests). */
	public JobResult runDaily() {
		LocalDate today = today();
		int invoices = 0;
		int reminders = 0;
		int changes = 0;
		int plans = 0;
		int daysBefore = setting("invoice_days_before", 7);
		int graceDays = setting("grace_days", 7);
		for (Map<String, Object> s : jdbc.queryForList("""
				select s.id, s.status, s.trial_ends_on, s.email, sub.* from shop s join subscription sub on sub.shop_id = s.id
				where s.status in ('TRIAL', 'ACTIVE', 'GRACE', 'READ_ONLY') and s.purged_at is null order by s.id""")) {
			long shopId = ((Number) s.get("shop_id")).longValue();
			try {
				LocalDate nextDue = ((Date) s.get("next_due_on")).toLocalDate();
				// Booked downgrade starts with the new period.
				if (s.get("pending_plan_id") != null && !today.isBefore(nextDue)) {
					String key = jdbc.queryForObject("select plan_key from plan where id = ?", String.class,
							s.get("pending_plan_id"));
					shops.changeSubscription(shopId, new SubscriptionUpdate(key, (String) s.get("pending_cycle"), null, null,
							null));
					tx.executeWithoutResult(t -> jdbc.update(
							"update subscription set pending_plan_id = null, pending_cycle = null where shop_id = ?", shopId));
					plans++;
				}
				Map<String, Object> sub = subscription(shopId);
				nextDue = ((Date) sub.get("next_due_on")).toLocalDate();
				if (!today.isBefore(nextDue.minusDays(daysBefore))) {
					LocalDate due = nextDue;
					Boolean made = tx.execute(t -> renewalInvoice(shopId, sub, due));
					if (Boolean.TRUE.equals(made)) {
						invoices++;
					}
				}
				reminders += remind(shopId, (String) s.get("email"), today);
				changes += tx.execute(t -> moveStatus(shopId, today, graceDays));
			}
			catch (RuntimeException e) {
				log.warn("billing job failed for shop {}: {}", shopId, e.getMessage(), e);
			}
		}
		return new JobResult(invoices, reminders, changes, plans);
	}

	private boolean renewalInvoice(long shopId, Map<String, Object> sub, LocalDate due) {
		Integer exists = jdbc.queryForObject("""
				select count(*) from subscription_invoice where shop_id = ? and kind = 'RENEWAL' and period_from = ?
				and status <> 'VOID'""", Integer.class, shopId, Date.valueOf(due));
		if (exists != null && exists > 0) {
			return false;
		}
		long planId = sub.get("pending_plan_id") != null ? ((Number) sub.get("pending_plan_id")).longValue()
				: ((Number) sub.get("plan_id")).longValue();
		String cycle = sub.get("pending_cycle") != null ? (String) sub.get("pending_cycle") : (String) sub.get("cycle");
		BigDecimal price;
		BigDecimal discount;
		if (sub.get("pending_plan_id") != null) {
			price = jdbc.queryForObject("select " + ("YEAR".equals(cycle) ? "price_year" : "price_month")
					+ " from plan where id = ?", BigDecimal.class, planId);
			discount = BigDecimal.ZERO;
		}
		else {
			price = (BigDecimal) sub.get("price");
			discount = (BigDecimal) sub.get("discount");
		}
		LocalDate to = ("YEAR".equals(cycle) ? due.plusYears(1) : due.plusMonths(1)).minusDays(1);
		insertInvoice(shopId, "RENEWAL", planId, cycle, due, to, price, discount, due, null);
		return true;
	}

	/** Reminders 7, 3 and 1 days before the due date and once when overdue, each sent once (BIL-06). */
	private int remind(long shopId, String shopEmail, LocalDate today) {
		int sent = 0;
		for (Map<String, Object> inv : jdbc.queryForList(
				"select * from subscription_invoice where shop_id = ? and status = 'OPEN' order by due_on", shopId)) {
			LocalDate due = ((Date) inv.get("due_on")).toLocalDate();
			long days = ChronoUnit.DAYS.between(today, due);
			String kind = days == 7 ? "D7" : days == 3 ? "D3" : days == 1 ? "D1" : days < 0 ? "OVERDUE" : null;
			if (kind == null) {
				continue;
			}
			long invoiceId = ((Number) inv.get("id")).longValue();
			Boolean first = tx.execute(t -> jdbc.update("""
					insert into subscription_reminder (shop_id, invoice_id, kind) values (?, ?, ?) on conflict do nothing""",
					shopId, invoiceId, kind) == 1);
			if (!Boolean.TRUE.equals(first)) {
				continue;
			}
			BigDecimal balance = ((BigDecimal) inv.get("total")).subtract((BigDecimal) inv.get("paid"));
			String text = "OVERDUE".equals(kind)
					? "Your " + com.selectcode.pos.shared.Brand.PRODUCT + " subscription (invoice " + inv.get("number") + ", Rs "
							+ balance.toPlainString() + ") is overdue. Please pay to avoid read-only mode."
					: "Your " + com.selectcode.pos.shared.Brand.PRODUCT + " subscription of Rs " + balance.toPlainString()
							+ " (invoice " + inv.get("number")
							+ ") is due on " + due + ".";
			inShop(shopId, () -> {
				notifier.email(shopEmail, "subscription_due", "Subscription " + ("OVERDUE".equals(kind) ? "overdue" : "due"),
						text, "subscription:" + inv.get("number") + ":" + kind);
				notifier.inAppFor(Permissions.SUBSCRIPTION_VIEW, "subscription_due", "Subscription", text, "/settings",
						"subscription:" + inv.get("number") + ":" + kind);
			});
			sent++;
		}
		return sent;
	}

	/** Trial / Active → Grace after the due date; Grace → Read-only after the grace days; back to Active when paid. */
	private int moveStatus(long shopId, LocalDate today, int graceDays) {
		String status = jdbc.queryForObject("select status from shop where id = ? for update", String.class, shopId);
		LocalDate oldestOverdue = jdbc.queryForObject("""
				select min(due_on) from subscription_invoice where shop_id = ? and status = 'OPEN' and due_on < ?""",
				LocalDate.class, shopId, Date.valueOf(today));
		if (oldestOverdue == null) {
			if (Set.of("GRACE", "READ_ONLY").contains(status)) {
				setStatus(shopId, "ACTIVE", "Nothing overdue");
				return 1;
			}
			return 0;
		}
		boolean pastGrace = oldestOverdue.plusDays(graceDays).isBefore(today);
		if (pastGrace && !"READ_ONLY".equals(status)) {
			setStatus(shopId, "READ_ONLY", "Overdue more than " + graceDays + " days");
			return 1;
		}
		if (!pastGrace && Set.of("TRIAL", "ACTIVE").contains(status)) {
			setStatus(shopId, "GRACE", "Payment overdue since " + oldestOverdue);
			return 1;
		}
		return 0;
	}

	/** Deletes closed shops after their 90 days (ONB-06) and activity history after 2 years (OPS-15). */
	public int retention() {
		int purged = 0;
		for (Long id : jdbc.queryForList("""
				select id from shop where status = 'CLOSED' and purged_at is null and delete_after is not null
				and delete_after < ?""", Long.class, Date.valueOf(today()))) {
			try {
				Integer rows = tx.execute(t -> jdbc.queryForObject("select purge_shop(?)", Integer.class, id));
				// Uploaded files too (CNIC photos, prescriptions, logos): SECURITY.md "Data protection".
				int files = storage.deletePrefix(com.selectcode.pos.shared.files.FileStorage.shopPrefix(id));
				audit.recordPlatform("SHOP_PURGED", id, Map.of("rows", String.valueOf(rows), "files", String.valueOf(files)));
				purged++;
			}
			catch (RuntimeException e) {
				log.error("could not purge closed shop {}: {}", id, e.getMessage(), e);
			}
		}
		tx.execute(t -> jdbc.queryForObject("select purge_old_audit(?)", Integer.class,
				java.sql.Timestamp.from(clock.instant().minus(730, ChronoUnit.DAYS))));
		return purged;
	}

	// ------------------------------------------------------------------ helpers

	private void setStatus(long shopId, String status, String reason) {
		jdbc.update("update shop set status = ?, status_reason = ?, status_changed_at = now(), updated_at = now() where id = ?",
				status, reason, shopId);
		audit.recordPlatform("SHOP_STATUS_" + status, shopId, Map.of("reason", reason));
	}

	private long insertInvoice(long shopId, String kind, long planId, String cycle, LocalDate from, LocalDate to,
			BigDecimal amount, BigDecimal discount, LocalDate due, String note) {
		long seq = jdbc.queryForObject("select count(*) + 1 from subscription_invoice", Long.class);
		String number = "SUB-" + today().getYear() + "-" + String.format("%05d", seq);
		while (!jdbc.queryForList("select 1 from subscription_invoice where number = ?", number).isEmpty()) {
			seq++;
			number = "SUB-" + today().getYear() + "-" + String.format("%05d", seq);
		}
		BigDecimal total = amount.subtract(discount).max(BigDecimal.ZERO);
		long id = jdbc.queryForObject("""
				insert into subscription_invoice (shop_id, number, kind, plan_id, cycle, period_from, period_to, amount,
				                                  discount, total, due_on, note)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, shopId, number, kind, planId, cycle,
				Date.valueOf(from), Date.valueOf(to), amount, discount, total, Date.valueOf(due), note);
		audit.recordPlatform("SUBSCRIPTION_INVOICE", shopId, Map.of("number", number, "total", total.toPlainString()));
		return id;
	}

	private Map<String, Object> subscription(long shopId) {
		return jdbc.queryForList("select * from subscription where shop_id = ?", shopId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Shop"));
	}

	private int setting(String key, int fallback) {
		try {
			return jdbc.queryForList("select value from platform_setting where setting_key = ?", String.class, key)
				.stream()
				.findFirst()
				.map(Integer::parseInt)
				.orElse(fallback);
		}
		catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Runs shop-side work (outbox rows are shop data) in its own transaction as that shop. */
	private void inShop(long shopId, Runnable work) {
		try {
			TenantContext.runAsShop(shopId, () -> inShopTx.executeWithoutResult(s -> work.run()));
		}
		catch (RuntimeException e) {
			log.warn("could not notify shop {}: {}", shopId, e.getMessage());
		}
	}

	private static InvoiceView invoice(java.sql.ResultSet rs) throws java.sql.SQLException {
		BigDecimal total = rs.getBigDecimal("total");
		BigDecimal paid = rs.getBigDecimal("paid");
		return new InvoiceView(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("code"), rs.getString("shop_name"),
				rs.getString("number"), rs.getString("kind"), rs.getString("plan_name"), rs.getString("cycle"),
				rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
				Amounts.text(rs.getBigDecimal("amount")), Amounts.text(rs.getBigDecimal("discount")), Amounts.text(total),
				Amounts.text(paid), Amounts.text(total.subtract(paid)), rs.getObject("due_on", LocalDate.class),
				rs.getString("status"), rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
				rs.getString("note"));
	}
}
