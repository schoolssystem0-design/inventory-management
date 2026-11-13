package com.selectcode.pos.sales.app;

import com.selectcode.pos.sales.app.PromotionEngine.Offer;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Setting up offers and coupons (SUP-05, XSL-02); the arithmetic is in {@link PromotionEngine}. */
@Service
public class PromotionService {

	/**
	 * @param value percent (PERCENT, BILL_PERCENT), rupees off (AMOUNT per unit, BILL_AMOUNT) or the fixed price of
	 *     {@code buyQty} items (MULTI_PRICE); not used by BUY_GET
	 */
	public record PromotionInput(String name, String kind, String value, String buyQty, String getQty, String minAmount,
			String scope, List<Long> categoryIds, List<Long> productIds, LocalDate startsOn, LocalDate endsOn,
			List<Integer> days, String fromTime, String toTime, String couponCode, Integer maxUses, Boolean active) {
	}

	public record PromotionView(long id, String name, String kind, String value, String buyQty, String getQty,
			String minAmount, String scope, List<Long> categoryIds, List<Long> productIds, LocalDate startsOn,
			LocalDate endsOn, List<Integer> days, String fromTime, String toTime, String couponCode, Integer maxUses,
			int uses, boolean active, boolean runningNow, String summary, String given) {
	}

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final AuditService audit;

	private final ShopTime time;

	PromotionService(JdbcTemplate jdbc, ShopFeatures features, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.features = features;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public PromotionView create(PromotionInput in) {
		Clean c = clean(in);
		long id;
		try {
			id = jdbc.queryForObject("""
					insert into promotion (shop_id, name, kind, value, buy_qty, get_qty, min_amount, scope, category_ids,
					                       product_ids, starts_on, ends_on, days, from_time, to_time, coupon_code, max_uses,
					                       active)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
					TenantContext.requireShopId(), c.name, c.kind, c.value, c.buyQty, c.getQty, c.minAmount, c.scope,
					c.categoryIds, c.productIds, c.startsOn, c.endsOn, c.days, c.fromTime, c.toTime, c.coupon, c.maxUses,
					c.active);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.field("couponCode", "duplicate", "Another offer already uses this coupon code");
		}
		audit.record("PROMOTION_CREATED", "promotion", id, null, Map.of("name", c.name, "kind", c.kind));
		return get(id);
	}

	@Transactional
	public PromotionView update(long id, PromotionInput in) {
		get(id);
		Clean c = clean(in);
		try {
			jdbc.update("""
					update promotion set name = ?, kind = ?, value = ?, buy_qty = ?, get_qty = ?, min_amount = ?, scope = ?,
					       category_ids = ?, product_ids = ?, starts_on = ?, ends_on = ?, days = ?, from_time = ?,
					       to_time = ?, coupon_code = ?, max_uses = ?, active = ?, updated_at = now(), version = version + 1
					where id = ?""", c.name, c.kind, c.value, c.buyQty, c.getQty, c.minAmount, c.scope, c.categoryIds,
					c.productIds, c.startsOn, c.endsOn, c.days, c.fromTime, c.toTime, c.coupon, c.maxUses, c.active, id);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.field("couponCode", "duplicate", "Another offer already uses this coupon code");
		}
		audit.record("PROMOTION_UPDATED", "promotion", id, null, Map.of("name", c.name, "active", c.active));
		return get(id);
	}

	@Transactional(readOnly = true)
	public PromotionView get(long id) {
		LocalDate today = time.today();
		LocalTime now = LocalTime.now(ShopTime.ZONE);
		return jdbc.query("""
				select p.*, coalesce((select sum(amount) from promotion_use u where u.promotion_id = p.id), 0) as given
				from promotion p where p.id = ?""", (rs, i) -> view(rs, today, now), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Offer"));
	}

	@Transactional(readOnly = true)
	public List<PromotionView> list(Boolean active) {
		LocalDate today = time.today();
		LocalTime now = LocalTime.now(ShopTime.ZONE);
		String where = active == null ? "" : " where p.active = " + active;
		return jdbc.query("""
				select p.*, coalesce((select sum(amount) from promotion_use u where u.promotion_id = p.id), 0) as given
				from promotion p""" + where + " order by p.active desc, p.id desc", (rs, i) -> view(rs, today, now));
	}

	/** Offers that may apply today (time of day is checked by the caller; the till checks it on its own clock). */
	@Transactional(readOnly = true)
	public List<Offer> today() {
		if (!features.isOn(ModuleKey.PROMOTIONS)) {
			return List.of();
		}
		LocalDate today = time.today();
		return jdbc.query("""
				select * from promotion where active and (starts_on is null or starts_on <= ?)
				and (ends_on is null or ends_on >= ?) and (max_uses is null or uses < max_uses) order by id""",
				(rs, i) -> offer(rs), Date.valueOf(today), Date.valueOf(today));
	}

	/** Locks the offers a sale used and counts the use (coupon limits). Runs in the sale's transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	void recordUse(Set<Long> offerIds, Map<Long, BigDecimal> amounts, long saleId, Long customerId, LocalDate date) {
		for (Long id : offerIds.stream().sorted().toList()) {
			Map<String, Object> row = jdbc.queryForMap("select max_uses, uses, name from promotion where id = ? for update", id);
			Integer max = (Integer) row.get("max_uses");
			int uses = ((Number) row.get("uses")).intValue();
			if (max != null && uses >= max) {
				throw ApiException.rule("coupon_used_up", "\"" + row.get("name") + "\" has been used the most times allowed");
			}
			jdbc.update("update promotion set uses = uses + 1 where id = ?", id);
			jdbc.update("""
					insert into promotion_use (shop_id, promotion_id, sale_id, customer_id, amount, business_date)
					values (?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), id, saleId, customerId,
					Amounts.money(amounts.getOrDefault(id, BigDecimal.ZERO)), Date.valueOf(date));
		}
	}

	/** A cancelled sale gives its uses back. */
	@Transactional(propagation = Propagation.MANDATORY)
	void releaseUse(long saleId) {
		for (Long id : jdbc.queryForList("select distinct promotion_id from promotion_use where sale_id = ?", Long.class,
				saleId)) {
			jdbc.update("update promotion set uses = greatest(uses - 1, 0) where id = ?", id);
		}
	}

	/** Whether a coupon code exists and is running (for a clear message at the till). */
	@Transactional(readOnly = true)
	public Offer coupon(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		return jdbc.query("select * from promotion where upper(coupon_code) = ?", (rs, i) -> offer(rs),
				code.trim().toUpperCase()).stream().findFirst()
			.orElseThrow(() -> ApiException.rule("coupon_invalid", "Coupon " + code.trim() + " is not valid"));
	}

	// ------------------------------------------------------------------ mapping

	static Offer offer(ResultSet rs) throws SQLException {
		Time from = rs.getTime("from_time");
		Time to = rs.getTime("to_time");
		return new Offer(rs.getLong("id"), rs.getString("name"), rs.getString("kind"), rs.getBigDecimal("value"),
				rs.getBigDecimal("buy_qty"), rs.getBigDecimal("get_qty"), rs.getBigDecimal("min_amount"),
				rs.getString("scope"), longs(rs.getArray("category_ids")), longs(rs.getArray("product_ids")),
				rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
				ints(rs.getArray("days")), from == null ? null : from.toLocalTime(), to == null ? null : to.toLocalTime(),
				rs.getString("coupon_code"), (Integer) rs.getObject("max_uses"), rs.getInt("uses"),
				rs.getBoolean("active"));
	}

	private static PromotionView view(ResultSet rs, LocalDate today, LocalTime now) throws SQLException {
		Offer o = offer(rs);
		return new PromotionView(o.id(), o.name(), o.kind(), Amounts.text(o.value()), Amounts.qtyText(o.buyQty()),
				Amounts.qtyText(o.getQty()), Amounts.text(o.minAmount()), o.scope(), new ArrayList<>(o.categoryIds()),
				new ArrayList<>(o.productIds()), o.startsOn(), o.endsOn(), o.days().stream().sorted().toList(),
				o.fromTime() == null ? null : o.fromTime().toString(), o.toTime() == null ? null : o.toTime().toString(),
				o.couponCode(), o.maxUses(), o.uses(), o.active(), PromotionEngine.running(o, today, now), summary(o),
				Amounts.text(rs.getBigDecimal("given")));
	}

	/** One line for lists and receipts, e.g. "Buy 2 get 1 free". */
	static String summary(Offer o) {
		String v = o.value() == null ? "" : o.value().stripTrailingZeros().toPlainString();
		return switch (o.kind()) {
			case "PERCENT" -> v + "% off";
			case "AMOUNT" -> "Rs " + v + " off each";
			case "BUY_GET" -> "Buy " + o.buyQty().stripTrailingZeros().toPlainString() + " get "
					+ o.getQty().stripTrailingZeros().toPlainString() + " free";
			case "MULTI_PRICE" -> o.buyQty().stripTrailingZeros().toPlainString() + " for Rs " + v;
			case "BILL_PERCENT" -> v + "% off the bill" + minText(o);
			case "BILL_AMOUNT" -> "Rs " + v + " off the bill" + minText(o);
			default -> o.kind();
		};
	}

	private static String minText(Offer o) {
		return o.minAmount() == null || o.minAmount().signum() == 0 ? ""
				: " over Rs " + o.minAmount().stripTrailingZeros().toPlainString();
	}

	private static Set<Long> longs(Array a) throws SQLException {
		if (a == null) {
			return Set.of();
		}
		return Arrays.stream((Object[]) a.getArray()).map(x -> ((Number) x).longValue())
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<Integer> ints(Array a) throws SQLException {
		if (a == null) {
			return Set.of();
		}
		return Arrays.stream((Object[]) a.getArray()).map(x -> ((Number) x).intValue())
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	// ------------------------------------------------------------------ input

	private record Clean(String name, String kind, BigDecimal value, BigDecimal buyQty, BigDecimal getQty,
			BigDecimal minAmount, String scope, Long[] categoryIds, Long[] productIds, Date startsOn, Date endsOn,
			Integer[] days, Time fromTime, Time toTime, String coupon, Integer maxUses, boolean active) {
	}

	private Clean clean(PromotionInput in) {
		String name = in.name() == null ? "" : in.name().trim();
		if (name.isEmpty()) {
			throw ApiException.field("name", "required", "Give the offer a name");
		}
		String kind = in.kind() == null ? "" : in.kind().trim().toUpperCase();
		if (!PromotionEngine.LINE_KINDS.contains(kind) && !PromotionEngine.BILL_KINDS.contains(kind)) {
			throw ApiException.field("kind", "invalid", "Choose the kind of offer");
		}
		BigDecimal value = Amounts.parseMoney(in.value(), "value");
		BigDecimal buy = Amounts.parseQty(in.buyQty(), "buyQty");
		BigDecimal get = Amounts.parseQty(in.getQty(), "getQty");
		switch (kind) {
			case "PERCENT", "BILL_PERCENT" -> {
				if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0) {
					throw ApiException.field("value", "invalid", "Enter a percent between 0 and 100");
				}
			}
			case "AMOUNT", "BILL_AMOUNT" -> {
				if (value == null || value.signum() <= 0) {
					throw ApiException.field("value", "invalid", "Enter the amount off");
				}
			}
			case "BUY_GET" -> {
				if (buy == null || buy.signum() <= 0 || buy.stripTrailingZeros().scale() > 0) {
					throw ApiException.field("buyQty", "invalid", "Enter how many must be bought (whole number)");
				}
				if (get == null || get.signum() <= 0 || get.stripTrailingZeros().scale() > 0) {
					throw ApiException.field("getQty", "invalid", "Enter how many are free (whole number)");
				}
				value = BigDecimal.ZERO;
			}
			case "MULTI_PRICE" -> {
				if (buy == null || buy.compareTo(BigDecimal.ONE) <= 0 || buy.stripTrailingZeros().scale() > 0) {
					throw ApiException.field("buyQty", "invalid", "Enter how many items (2 or more)");
				}
				if (value == null || value.signum() <= 0) {
					throw ApiException.field("value", "invalid", "Enter the price for them together");
				}
				get = null;
			}
			default -> {
			}
		}
		if (!"BUY_GET".equals(kind) && !"MULTI_PRICE".equals(kind)) {
			buy = null;
			get = null;
		}
		BigDecimal min = Amounts.parseMoney(in.minAmount(), "minAmount");
		boolean bill = PromotionEngine.BILL_KINDS.contains(kind);
		String scope = bill || in.scope() == null || in.scope().isBlank() ? "ALL" : in.scope().trim().toUpperCase();
		if (!List.of("ALL", "CATEGORY", "PRODUCTS").contains(scope)) {
			throw ApiException.field("scope", "invalid", "Choose all items, a category or products");
		}
		List<Long> categories = "CATEGORY".equals(scope) && in.categoryIds() != null ? in.categoryIds() : List.of();
		List<Long> products = "PRODUCTS".equals(scope) && in.productIds() != null ? in.productIds() : List.of();
		if ("CATEGORY".equals(scope) && categories.isEmpty()) {
			throw ApiException.field("categoryIds", "required", "Choose at least one category");
		}
		if ("PRODUCTS".equals(scope) && products.isEmpty()) {
			throw ApiException.field("productIds", "required", "Choose at least one product");
		}
		if (!categories.isEmpty()) {
			Integer n = jdbc.queryForObject("select count(*) from category where id = any(?)", Integer.class,
					(Object) categories.toArray(Long[]::new));
			if (n == null || n != new LinkedHashSet<>(categories).size()) {
				throw ApiException.field("categoryIds", "invalid", "A category was not found");
			}
		}
		if (!products.isEmpty()) {
			Integer n = jdbc.queryForObject("select count(*) from product where id = any(?)", Integer.class,
					(Object) products.toArray(Long[]::new));
			if (n == null || n != new LinkedHashSet<>(products).size()) {
				throw ApiException.field("productIds", "invalid", "A product was not found");
			}
		}
		if (in.startsOn() != null && in.endsOn() != null && in.endsOn().isBefore(in.startsOn())) {
			throw ApiException.field("endsOn", "invalid", "The end date is before the start date");
		}
		List<Integer> days = in.days() == null ? List.of() : in.days().stream().distinct().sorted().toList();
		if (days.stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
			throw ApiException.field("days", "invalid", "Days are 1 (Monday) to 7 (Sunday)");
		}
		Time from = time(in.fromTime(), "fromTime");
		Time to = time(in.toTime(), "toTime");
		if ((from == null) != (to == null)) {
			throw ApiException.field(from == null ? "fromTime" : "toTime", "required", "Enter both the start and end time");
		}
		if (from != null && from.equals(to)) {
			throw ApiException.field("toTime", "invalid", "The end time is the same as the start time");
		}
		String coupon = in.couponCode() == null || in.couponCode().isBlank() ? null
				: in.couponCode().trim().toUpperCase();
		if (coupon != null && !coupon.matches("[A-Z0-9_-]{3,30}")) {
			throw ApiException.field("couponCode", "invalid", "Coupon codes are 3 to 30 letters or numbers");
		}
		if (in.maxUses() != null && in.maxUses() <= 0) {
			throw ApiException.field("maxUses", "invalid", "Enter how many times it can be used, or leave it empty");
		}
		return new Clean(name.length() > 120 ? name.substring(0, 120) : name, kind, value == null ? BigDecimal.ZERO : value,
				buy, get, min == null ? BigDecimal.ZERO : min, scope, categories.toArray(Long[]::new),
				products.toArray(Long[]::new), in.startsOn() == null ? null : Date.valueOf(in.startsOn()),
				in.endsOn() == null ? null : Date.valueOf(in.endsOn()), days.toArray(Integer[]::new), from, to, coupon,
				in.maxUses(), !Boolean.FALSE.equals(in.active()));
	}

	private static Time time(String text, String field) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Time.valueOf(LocalTime.parse(text.trim().length() == 5 ? text.trim() : text.trim().substring(0, 5)));
		}
		catch (RuntimeException e) {
			throw ApiException.field(field, "invalid", "Enter a time like 17:00");
		}
	}
}
