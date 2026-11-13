package com.selectcode.pos.sales.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.jobs.Jobs;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loyalty points (CUS-06, SUP-06). Customers earn a point for every {@code loyalty_spend_per_point} rupees of a bill
 * (after any points used on it), pay with points at {@code loyalty_point_value} rupees each (at least
 * {@code loyalty_min_redeem} points), and lose their points after {@code loyalty_expiry_months} months without a
 * purchase. A return takes back the points earned on what was returned; a cancel also gives back points used.
 */
@Service
public class LoyaltyService {

	public record LoyaltySettings(String spendPerPoint, String pointValue, int minRedeem, int expiryMonths) {
	}

	public record LoyaltyAccount(long customerId, String customerName, int points, String value,
			LocalDate lastPurchase, LocalDate expiresOn, LoyaltySettings settings) {
	}

	public record LoyaltyTxnView(long id, String kind, int points, int balanceAfter, Long saleId, String saleNumber,
			String note, String actorName, LocalDate date, Instant at) {
	}

	public record AdjustInput(Integer points, String note) {
	}

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final AuditService audit;

	private final ShopTime time;

	private final Jobs jobs;

	LoyaltyService(JdbcTemplate jdbc, ShopFeatures features, AuditService audit, ShopTime time, Jobs jobs) {
		this.jdbc = jdbc;
		this.features = features;
		this.audit = audit;
		this.time = time;
		this.jobs = jobs;
	}

	public LoyaltySettings settings() {
		Map<String, Object> s = jdbc.queryForMap("""
				select loyalty_spend_per_point, loyalty_point_value, loyalty_min_redeem, loyalty_expiry_months
				from shop_settings where shop_id = ?""", TenantContext.requireShopId());
		return new LoyaltySettings(Amounts.text((BigDecimal) s.get("loyalty_spend_per_point")),
				Amounts.text((BigDecimal) s.get("loyalty_point_value")), ((Number) s.get("loyalty_min_redeem")).intValue(),
				((Number) s.get("loyalty_expiry_months")).intValue());
	}

	/** Points earned on an amount (whole points, rounded down). */
	static int pointsEarned(BigDecimal amount, BigDecimal spendPerPoint) {
		if (amount == null || amount.signum() <= 0) {
			return 0;
		}
		return amount.divide(spendPerPoint, 0, RoundingMode.DOWN).intValueExact();
	}

	/**
	 * Points needed to pay {@code amount} with points; the amount must be a whole number of points.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	int checkRedeem(long customerId, BigDecimal amount) {
		if (!features.isOn(ModuleKey.LOYALTY)) {
			throw ApiException.rule("module_disabled", "Loyalty points are not switched on for this shop");
		}
		LoyaltySettings s = settings();
		BigDecimal value = new BigDecimal(s.pointValue());
		BigDecimal[] div = amount.divideAndRemainder(value);
		if (div[1].signum() != 0) {
			throw ApiException.field("payments.amount", "invalid",
					"Points are worth Rs " + s.pointValue() + " each; pay a whole number of points");
		}
		int points = div[0].intValueExact();
		if (points < s.minRedeem()) {
			throw ApiException.rule("points_minimum", "At least " + s.minRedeem() + " points must be used at a time");
		}
		int balance = jdbc.queryForObject("select loyalty_points from customer where id = ? for update", Integer.class,
				customerId);
		if (points > balance) {
			throw ApiException.rule("points_short", "The customer has only " + balance + " points");
		}
		return points;
	}

	/** Pays part of a sale with points (already checked with {@link #checkRedeem}). */
	@Transactional(propagation = Propagation.MANDATORY)
	void redeem(long customerId, int points, long saleId, String saleNumber, LocalDate date) {
		post(customerId, "REDEEM", -points, saleId, null, "Paid with points on " + saleNumber, date);
	}

	/** Points for a sale (none when the module is off or the sale has no customer). */
	@Transactional(propagation = Propagation.MANDATORY)
	int earn(Long customerId, BigDecimal amount, long saleId, String saleNumber, LocalDate date) {
		if (customerId == null || !features.isOn(ModuleKey.LOYALTY)) {
			return 0;
		}
		int points = pointsEarned(amount, new BigDecimal(settings().spendPerPoint()));
		jdbc.update("update customer set loyalty_last_at = ? where id = ?", Date.valueOf(date), customerId);
		if (points > 0) {
			post(customerId, "EARN", points, saleId, null, "Earned on " + saleNumber, date);
		}
		return points;
	}

	/** Takes back the points earned on the returned share of a sale (never below zero). */
	@Transactional(propagation = Propagation.MANDATORY)
	void takeBackForReturn(long saleId, long returnId, String returnNumber, BigDecimal returnedValue,
			BigDecimal saleTotal, LocalDate date) {
		Map<String, Object> s = earnedOn(saleId);
		if (s == null || saleTotal.signum() <= 0) {
			return;
		}
		int earned = ((Number) s.get("earned")).intValue();
		int takenBack = ((Number) s.get("taken_back")).intValue();
		int share = new BigDecimal(earned).multiply(returnedValue).divide(saleTotal, 0, RoundingMode.HALF_UP)
			.intValueExact();
		int take = Math.min(share, earned - takenBack);
		take(((Number) s.get("customer_id")).longValue(), take, saleId, returnId, "Taken back on return " + returnNumber,
				date);
	}

	/** A cancelled sale: points earned on it come off, points used on it go back. */
	@Transactional(propagation = Propagation.MANDATORY)
	void reverseSale(long saleId, String saleNumber, LocalDate date) {
		Map<String, Object> s = earnedOn(saleId);
		if (s == null) {
			return;
		}
		long customerId = ((Number) s.get("customer_id")).longValue();
		int earned = ((Number) s.get("earned")).intValue() - ((Number) s.get("taken_back")).intValue();
		int used = ((Number) s.get("used")).intValue() - ((Number) s.get("given_back")).intValue();
		if (used > 0) {
			post(customerId, "GIVE_BACK", used, saleId, null, "Given back: " + saleNumber + " cancelled", date);
		}
		take(customerId, earned, saleId, null, "Taken back: " + saleNumber + " cancelled", date);
	}

	private void take(long customerId, int points, long saleId, Long returnId, String note, LocalDate date) {
		if (points <= 0) {
			return;
		}
		int balance = jdbc.queryForObject("select loyalty_points from customer where id = ? for update", Integer.class,
				customerId);
		int take = Math.min(points, balance);
		if (take > 0) {
			post(customerId, "TAKE_BACK", -take, saleId, returnId, note, date);
		}
	}

	private Map<String, Object> earnedOn(long saleId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				select customer_id,
				       coalesce(sum(points) filter (where kind = 'EARN'), 0) as earned,
				       coalesce(-sum(points) filter (where kind = 'TAKE_BACK'), 0) as taken_back,
				       coalesce(-sum(points) filter (where kind = 'REDEEM'), 0) as used,
				       coalesce(sum(points) filter (where kind = 'GIVE_BACK'), 0) as given_back
				from loyalty_txn where sale_id = ? group by customer_id""", saleId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	/** Writes one entry and moves the customer's balance (the customer row is locked first). */
	private void post(long customerId, String kind, int points, Long saleId, Long returnId, String note, LocalDate date) {
		Integer balance = jdbc.queryForObject("select loyalty_points from customer where id = ? for update", Integer.class,
				customerId);
		if (balance == null || balance + points < 0) {
			throw ApiException.rule("points_short", "The customer has only " + balance + " points");
		}
		Integer after = jdbc.queryForObject(
				"update customer set loyalty_points = loyalty_points + ? where id = ? returning loyalty_points",
				Integer.class, points, customerId);
		if (after == null || after < 0) {
			throw ApiException.rule("points_short", "Not enough points");
		}
		jdbc.update("""
				insert into loyalty_txn (shop_id, customer_id, kind, points, balance_after, sale_id, return_id, note,
				                         actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), customerId, kind, points, after,
				saleId, returnId, note == null ? null : note.length() > 200 ? note.substring(0, 200) : note,
				CurrentActor.name(), Date.valueOf(date));
	}

	// ------------------------------------------------------------------ screens

	@Transactional(readOnly = true)
	public LoyaltyAccount account(long customerId) {
		LoyaltySettings s = settings();
		return jdbc.query("select id, name, loyalty_points, loyalty_last_at from customer where id = ?", (rs, i) -> {
			LocalDate last = rs.getObject("loyalty_last_at", LocalDate.class);
			int points = rs.getInt("loyalty_points");
			return new LoyaltyAccount(rs.getLong("id"), rs.getString("name"), points,
					Amounts.text(new BigDecimal(s.pointValue()).multiply(BigDecimal.valueOf(points))), last,
					last == null || s.expiryMonths() == 0 || points == 0 ? null : last.plusMonths(s.expiryMonths()), s);
		}, customerId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Customer"));
	}

	@Transactional(readOnly = true)
	public PageResponse<LoyaltyTxnView> history(long customerId, int page, int size) {
		account(customerId);
		long total = jdbc.queryForObject("select count(*) from loyalty_txn where customer_id = ?", Long.class, customerId);
		List<LoyaltyTxnView> items = jdbc.query("""
				select t.*, s.number as sale_number from loyalty_txn t left join sale s on s.id = t.sale_id
				where t.customer_id = ? order by t.id desc limit ? offset ?""",
				(rs, i) -> new LoyaltyTxnView(rs.getLong("id"), rs.getString("kind"), rs.getInt("points"),
						rs.getInt("balance_after"), (Long) rs.getObject("sale_id"), rs.getString("sale_number"),
						rs.getString("note"), rs.getString("actor_name"), rs.getObject("business_date", LocalDate.class),
						rs.getTimestamp("at").toInstant()),
				customerId, size, (long) page * size);
		return new PageResponse<>(items, page, size, total);
	}

	/** A manager adds or removes points by hand, with a note (shown in the history and the activity log). */
	@Transactional
	public LoyaltyAccount adjust(long customerId, AdjustInput in) {
		account(customerId);
		if (in.points() == null || in.points() == 0) {
			throw ApiException.field("points", "required", "Enter the points to add (or minus to remove)");
		}
		if (in.note() == null || in.note().isBlank()) {
			throw ApiException.field("note", "required", "Write why the points are changed");
		}
		jdbc.queryForObject("select loyalty_points from customer where id = ? for update", Integer.class, customerId);
		post(customerId, "ADJUST", in.points(), null, null, in.note().trim(), time.today());
		audit.record("LOYALTY_ADJUSTED", "customer", customerId, null,
				Map.of("points", in.points(), "note", in.note().trim()));
		return account(customerId);
	}

	/** Points of customers with no purchase for the expiry period are cleared (current shop). */
	@Transactional
	public int expire() {
		if (!features.isOn(ModuleKey.LOYALTY)) {
			return 0;
		}
		int months = settings().expiryMonths();
		if (months == 0) {
			return 0;
		}
		LocalDate today = time.today();
		LocalDate cutoff = today.minusMonths(months);
		int n = 0;
		for (Map<String, Object> c : jdbc.queryForList("""
				select id, loyalty_points from customer where loyalty_points > 0
				and coalesce(loyalty_last_at, created_at::date) < ? order by id for update""", Date.valueOf(cutoff))) {
			int points = ((Number) c.get("loyalty_points")).intValue();
			post(((Number) c.get("id")).longValue(), "EXPIRE", -points, null, null,
					"No purchase for " + months + " months", today);
			n++;
		}
		return n;
	}

	/** 02:30 every day. */
	@Scheduled(cron = "0 30 2 * * *", zone = "Asia/Karachi")
	public void nightly() {
		if (!jobs.enabled()) {
			return;
		}
		jobs.exclusive("loyalty-expiry", () -> jobs.forEachShop("loyalty-expiry",
				jobs.shops("TRIAL", "ACTIVE", "GRACE"), shop -> expire()));
	}
}
