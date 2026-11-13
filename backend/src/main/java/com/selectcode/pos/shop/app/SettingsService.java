package com.selectcode.pos.shop.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shop settings with change history (SET-01, SET-03, SET-04, SET-05, SET-08). Each changed field writes one
 * {@code settings_change} row with old and new value and who changed it.
 */
@Service
public class SettingsService {

	public record SettingsView(String businessName, String urduName, Long logoFileId, String address, String phones,
			String email, String ntn, String strn, String taxMode, String taxRate, boolean allowNegativeStock,
			String priceRounding, String receiptSize, String receiptLanguage, String receiptHeader, String receiptFooter,
			boolean receiptShowBarcode, boolean receiptShowTax, boolean receiptShowSavings, String returnPolicy,
			int returnDays, String closingTime, String discountLimitPct, String writeoffLimit, int sessionIdleMinutes,
			boolean ownerTotpRequired, boolean maskCustomerPii, String timezone, long version, String scaleValueKind,
			boolean prescriptionRequired, String loyaltySpendPerPoint, String loyaltyPointValue, int loyaltyMinRedeem,
			int loyaltyExpiryMonths) {
	}

	/** Every field optional: only the fields sent are changed. */
	public record SettingsUpdate(String businessName, String urduName, Long logoFileId, String address, String phones,
			String email, String ntn, String strn, String taxMode, String taxRate, Boolean allowNegativeStock,
			String priceRounding, String receiptSize, String receiptLanguage, String receiptHeader, String receiptFooter,
			Boolean receiptShowBarcode, Boolean receiptShowTax, Boolean receiptShowSavings, String returnPolicy,
			Integer returnDays, String closingTime, String discountLimitPct, String writeoffLimit,
			Integer sessionIdleMinutes, Boolean ownerTotpRequired, Boolean maskCustomerPii, String scaleValueKind,
			Boolean prescriptionRequired, String loyaltySpendPerPoint, String loyaltyPointValue, Integer loyaltyMinRedeem,
			Integer loyaltyExpiryMonths) {
	}

	public record SettingsChangeView(long id, String key, String oldValue, String newValue, String actorType,
			String actorName, java.time.Instant at) {
	}

	private record Field(String column, Function<SettingsUpdate, Object> getter, Function<Object, Object> normalise) {
	}

	private static final Set<String> TAX_MODES = Set.of("NONE", "INCLUSIVE", "EXCLUSIVE");

	private static final Set<String> ROUNDING = Set.of("NONE", "ONE", "FIVE", "TEN");

	private static final Set<String> RECEIPT_SIZES = Set.of("A4", "MM80", "MM58");

	private static final Set<String> LANGUAGES = Set.of("EN", "UR", "BOTH");

	private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

	static {
		FIELDS.put("businessName", new Field("business_name", SettingsUpdate::businessName, v -> required(v, "businessName", 200)));
		FIELDS.put("urduName", new Field("urdu_name", SettingsUpdate::urduName, v -> optional(v, "urduName", 200)));
		FIELDS.put("logoFileId", new Field("logo_file_id", SettingsUpdate::logoFileId, v -> v));
		FIELDS.put("address", new Field("address", SettingsUpdate::address, v -> optional(v, "address", 500)));
		FIELDS.put("phones", new Field("phones", SettingsUpdate::phones, v -> optional(v, "phones", 200)));
		FIELDS.put("email", new Field("email", SettingsUpdate::email, v -> email(v)));
		FIELDS.put("ntn", new Field("ntn", SettingsUpdate::ntn, v -> optional(v, "ntn", 30)));
		FIELDS.put("strn", new Field("strn", SettingsUpdate::strn, v -> optional(v, "strn", 30)));
		FIELDS.put("taxMode", new Field("tax_mode", SettingsUpdate::taxMode, v -> oneOf(v, "taxMode", TAX_MODES)));
		FIELDS.put("taxRate", new Field("tax_rate", SettingsUpdate::taxRate, v -> Amounts.parsePercent((String) v, "taxRate")));
		FIELDS.put("allowNegativeStock", new Field("allow_negative_stock", SettingsUpdate::allowNegativeStock, v -> v));
		FIELDS.put("priceRounding", new Field("price_rounding", SettingsUpdate::priceRounding, v -> oneOf(v, "priceRounding", ROUNDING)));
		FIELDS.put("receiptSize", new Field("receipt_size", SettingsUpdate::receiptSize, v -> oneOf(v, "receiptSize", RECEIPT_SIZES)));
		FIELDS.put("receiptLanguage", new Field("receipt_language", SettingsUpdate::receiptLanguage, v -> oneOf(v, "receiptLanguage", LANGUAGES)));
		FIELDS.put("receiptHeader", new Field("receipt_header", SettingsUpdate::receiptHeader, v -> optional(v, "receiptHeader", 500)));
		FIELDS.put("receiptFooter", new Field("receipt_footer", SettingsUpdate::receiptFooter, v -> optional(v, "receiptFooter", 500)));
		FIELDS.put("receiptShowBarcode", new Field("receipt_show_barcode", SettingsUpdate::receiptShowBarcode, v -> v));
		FIELDS.put("receiptShowTax", new Field("receipt_show_tax", SettingsUpdate::receiptShowTax, v -> v));
		FIELDS.put("receiptShowSavings", new Field("receipt_show_savings", SettingsUpdate::receiptShowSavings, v -> v));
		FIELDS.put("returnPolicy", new Field("return_policy", SettingsUpdate::returnPolicy, v -> optional(v, "returnPolicy", 500)));
		FIELDS.put("returnDays", new Field("return_days", SettingsUpdate::returnDays, v -> range(v, "returnDays", 0, 365)));
		FIELDS.put("closingTime", new Field("closing_time", SettingsUpdate::closingTime, v -> time(v)));
		FIELDS.put("discountLimitPct", new Field("discount_limit_pct", SettingsUpdate::discountLimitPct, v -> Amounts.parsePercent((String) v, "discountLimitPct")));
		FIELDS.put("writeoffLimit", new Field("writeoff_limit", SettingsUpdate::writeoffLimit, v -> Amounts.requireMoney((String) v, "writeoffLimit")));
		FIELDS.put("sessionIdleMinutes", new Field("session_idle_minutes", SettingsUpdate::sessionIdleMinutes, v -> range(v, "sessionIdleMinutes", 2, 480)));
		FIELDS.put("ownerTotpRequired", new Field("owner_totp_required", SettingsUpdate::ownerTotpRequired, v -> v));
		FIELDS.put("maskCustomerPii", new Field("mask_customer_pii", SettingsUpdate::maskCustomerPii, v -> v));
		FIELDS.put("scaleValueKind", new Field("scale_value_kind", SettingsUpdate::scaleValueKind, v -> oneOf(v, "scaleValueKind", Set.of("WEIGHT", "PRICE"))));
		FIELDS.put("prescriptionRequired", new Field("prescription_required", SettingsUpdate::prescriptionRequired, v -> v));
		FIELDS.put("loyaltySpendPerPoint", new Field("loyalty_spend_per_point", SettingsUpdate::loyaltySpendPerPoint, v -> positive((String) v, "loyaltySpendPerPoint")));
		FIELDS.put("loyaltyPointValue", new Field("loyalty_point_value", SettingsUpdate::loyaltyPointValue, v -> positive((String) v, "loyaltyPointValue")));
		FIELDS.put("loyaltyMinRedeem", new Field("loyalty_min_redeem", SettingsUpdate::loyaltyMinRedeem, v -> range(v, "loyaltyMinRedeem", 0, 1_000_000)));
		FIELDS.put("loyaltyExpiryMonths", new Field("loyalty_expiry_months", SettingsUpdate::loyaltyExpiryMonths, v -> range(v, "loyaltyExpiryMonths", 0, 120)));
	}

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	SettingsService(JdbcTemplate jdbc, AuditService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public SettingsView get() {
		return jdbc.queryForObject("""
				select s.*, sh.timezone from shop_settings s join shop sh on sh.id = s.shop_id where s.shop_id = ?""",
				(rs, i) -> new SettingsView(rs.getString("business_name"), rs.getString("urdu_name"),
						(Long) rs.getObject("logo_file_id"), rs.getString("address"), rs.getString("phones"),
						rs.getString("email"), rs.getString("ntn"), rs.getString("strn"), rs.getString("tax_mode"),
						Amounts.percentText(rs.getBigDecimal("tax_rate")), rs.getBoolean("allow_negative_stock"),
						rs.getString("price_rounding"), rs.getString("receipt_size"), rs.getString("receipt_language"),
						rs.getString("receipt_header"), rs.getString("receipt_footer"),
						rs.getBoolean("receipt_show_barcode"), rs.getBoolean("receipt_show_tax"),
						rs.getBoolean("receipt_show_savings"), rs.getString("return_policy"), rs.getInt("return_days"),
						rs.getTime("closing_time").toLocalTime().toString(),
						Amounts.percentText(rs.getBigDecimal("discount_limit_pct")),
						Amounts.text(rs.getBigDecimal("writeoff_limit")), rs.getInt("session_idle_minutes"),
						rs.getBoolean("owner_totp_required"), rs.getBoolean("mask_customer_pii"), rs.getString("timezone"),
						rs.getLong("version"), rs.getString("scale_value_kind"), rs.getBoolean("prescription_required"),
						Amounts.text(rs.getBigDecimal("loyalty_spend_per_point")),
						Amounts.text(rs.getBigDecimal("loyalty_point_value")), rs.getInt("loyalty_min_redeem"),
						rs.getInt("loyalty_expiry_months")),
				TenantContext.requireShopId());
	}

	@Transactional
	public SettingsView update(SettingsUpdate update) {
		long shopId = TenantContext.requireShopId();
		Map<String, Object> current = jdbc.queryForMap("select * from shop_settings where shop_id = ? for update", shopId);
		Map<String, Object> changes = new LinkedHashMap<>();
		Map<String, Object[]> history = new LinkedHashMap<>();
		FIELDS.forEach((key, field) -> {
			Object raw = field.getter().apply(update);
			if (raw == null) {
				return;
			}
			Object value = field.normalise().apply(raw);
			if (value instanceof String s && s.isEmpty()) {
				value = null;
			}
			Object old = current.get(field.column());
			if (!sameValue(old, value)) {
				changes.put(field.column(), value);
				history.put(key, new Object[] { display(old), display(value) });
			}
		});
		if (update.logoFileId() != null && update.logoFileId() > 0) {
			Integer found = jdbc.queryForObject("select count(*) from stored_file where id = ? and purpose = 'SHOP_LOGO'",
					Integer.class, update.logoFileId());
			if (found == null || found == 0) {
				throw ApiException.field("logoFileId", "not_found", "Upload the logo first");
			}
		}
		if (update.logoFileId() != null && update.logoFileId() <= 0) {
			changes.put("logo_file_id", null);
		}
		if (changes.isEmpty()) {
			return get();
		}
		StringBuilder sql = new StringBuilder("update shop_settings set ");
		List<Object> args = new java.util.ArrayList<>();
		changes.forEach((column, value) -> {
			sql.append(column).append(" = ?, ");
			args.add(value instanceof LocalTime t ? java.sql.Time.valueOf(t) : value);
		});
		sql.append("updated_at = now(), version = version + 1 where shop_id = ?");
		args.add(shopId);
		jdbc.update(sql.toString(), args.toArray());
		history.forEach((key, values) -> jdbc.update("""
				insert into settings_change (shop_id, setting_key, old_value, new_value, actor_type, actor_id, actor_name)
				values (?, ?, ?, ?, ?, ?, ?)""", shopId, key, truncate((String) values[0]), truncate((String) values[1]),
				CurrentActor.type().name(), CurrentActor.id(), CurrentActor.name()));
		audit.record("SETTINGS_CHANGED", "shop_settings", shopId, null, history.keySet());
		return get();
	}

	@Transactional(readOnly = true)
	public PageResponse<SettingsChangeView> history(int page, int size) {
		List<SettingsChangeView> items = jdbc.query("""
				select * from settings_change order by at desc, id desc limit ? offset ?""",
				(rs, i) -> new SettingsChangeView(rs.getLong("id"), rs.getString("setting_key"),
						rs.getString("old_value"), rs.getString("new_value"), rs.getString("actor_type"),
						rs.getString("actor_name"), rs.getTimestamp("at").toInstant()),
				size, page * size);
		Long total = jdbc.queryForObject("select count(*) from settings_change", Long.class);
		return PageResponse.of(items, page, size, total == null ? 0 : total);
	}

	private static boolean sameValue(Object old, Object value) {
		if (old instanceof BigDecimal a && value instanceof BigDecimal b) {
			return a.compareTo(b) == 0;
		}
		if (old instanceof java.sql.Time t && value instanceof LocalTime l) {
			return t.toLocalTime().equals(l);
		}
		if (old instanceof Number a && value instanceof Number b) {
			return a.longValue() == b.longValue();
		}
		return Objects.equals(old, value);
	}

	private static String display(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof BigDecimal b) {
			return b.stripTrailingZeros().toPlainString();
		}
		if (value instanceof java.sql.Time t) {
			return t.toLocalTime().toString();
		}
		return String.valueOf(value);
	}

	private static String truncate(String v) {
		return v == null || v.length() <= 500 ? v : v.substring(0, 500);
	}

	private static Object required(Object v, String field, int max) {
		String s = ((String) v).trim();
		if (s.isEmpty()) {
			throw ApiException.field(field, "required", "This field is required");
		}
		return limit(s, field, max);
	}

	private static Object optional(Object v, String field, int max) {
		return limit(((String) v).trim(), field, max);
	}

	private static String limit(String s, String field, int max) {
		if (s.length() > max) {
			throw ApiException.field(field, "too_long", "Use at most " + max + " characters");
		}
		return s;
	}

	private static Object email(Object v) {
		String s = ((String) v).trim();
		if (!s.isEmpty() && !s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw ApiException.field("email", "invalid", "Enter a valid email address");
		}
		return limit(s, "email", 254);
	}

	private static Object oneOf(Object v, String field, Set<String> allowed) {
		String s = ((String) v).trim().toUpperCase();
		if (!allowed.contains(s)) {
			throw ApiException.field(field, "invalid", "Choose one of " + String.join(", ", allowed));
		}
		return s;
	}

	private static Object positive(String v, String field) {
		java.math.BigDecimal value = Amounts.requireMoney(v, field);
		if (value.signum() <= 0) {
			throw ApiException.field(field, "invalid", "Enter an amount above zero");
		}
		return value;
	}

	private static Object range(Object v, String field, int min, int max) {
		int n = (Integer) v;
		if (n < min || n > max) {
			throw ApiException.field(field, "out_of_range", "Enter a number from " + min + " to " + max);
		}
		return n;
	}

	private static Object time(Object v) {
		try {
			return LocalTime.parse(((String) v).trim());
		}
		catch (java.time.format.DateTimeParseException e) {
			throw ApiException.field("closingTime", "invalid", "Enter a time like 22:00");
		}
	}
}
