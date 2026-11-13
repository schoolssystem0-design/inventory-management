package com.selectcode.pos.shop.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document numbering (SET-06), payment methods (SET-07) and the owner's support approval code (PLT-17).
 */
@Service
public class ShopConfigService {

	public record NumberingView(long id, Long branchId, String docType, String prefix, long nextNo, int digits,
			String example) {
	}

	public record NumberingUpdate(String prefix, Long nextNo, Integer digits) {
	}

	public record PaymentMethodView(long id, String key, String label, boolean active, int sortOrder) {
	}

	public record PaymentMethodUpdate(String label, Boolean active, Integer sortOrder) {
	}

	public record SupportCode(String code, Instant expiresAt) {
	}

	public static final Duration SUPPORT_CODE_LIFETIME = Duration.ofMinutes(60);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	private final Clock clock;

	ShopConfigService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<NumberingView> numbering() {
		return jdbc.query("select * from numbering order by coalesce(branch_id, 0), doc_type", (rs, i) -> {
			String prefix = rs.getString("prefix");
			long next = rs.getLong("next_no");
			int digits = rs.getInt("digits");
			return new NumberingView(rs.getLong("id"), (Long) rs.getObject("branch_id"), rs.getString("doc_type"), prefix,
					next, digits, example(prefix, next, digits));
		});
	}

	@Transactional
	public NumberingView updateNumbering(long id, NumberingUpdate update) {
		Map<String, Object> row = jdbc.queryForList("select * from numbering where id = ? for update", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Numbering"));
		String prefix = update.prefix() == null ? (String) row.get("prefix") : update.prefix().trim().toUpperCase();
		if (!prefix.matches("[A-Z0-9-]{1,12}")) {
			throw ApiException.field("prefix", "invalid", "Use up to 12 letters, numbers or hyphens");
		}
		long current = ((Number) row.get("next_no")).longValue();
		long next = update.nextNo() == null ? current : update.nextNo();
		if (next < current) {
			throw ApiException.field("nextNo", "too_low",
					"The next number can only go up (it is " + current + "), so numbers are never reused");
		}
		int digits = update.digits() == null ? ((Number) row.get("digits")).intValue() : update.digits();
		if (digits < 3 || digits > 10) {
			throw ApiException.field("digits", "out_of_range", "Use 3 to 10 digits");
		}
		jdbc.update("update numbering set prefix = ?, next_no = ?, digits = ? where id = ?", prefix, next, digits, id);
		audit.record("NUMBERING_CHANGED", "numbering", id, Map.of("prefix", row.get("prefix"), "nextNo", current),
				Map.of("prefix", prefix, "nextNo", next, "digits", digits));
		return numbering().stream().filter(n -> n.id() == id).findFirst().orElseThrow();
	}

	@Transactional(readOnly = true)
	public List<PaymentMethodView> paymentMethods() {
		return jdbc.query("select * from payment_method order by sort_order, id",
				(rs, i) -> new PaymentMethodView(rs.getLong("id"), rs.getString("method_key"), rs.getString("label"),
						rs.getBoolean("active"), rs.getInt("sort_order")));
	}

	@Transactional
	public PaymentMethodView updatePaymentMethod(long id, PaymentMethodUpdate update) {
		Map<String, Object> row = jdbc.queryForList("select * from payment_method where id = ?", id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Payment method"));
		String label = update.label() == null ? (String) row.get("label") : update.label().trim();
		if (label.isEmpty() || label.length() > 60) {
			throw ApiException.field("label", "invalid", "Enter a label of up to 60 characters");
		}
		boolean active = update.active() == null ? (Boolean) row.get("active") : update.active();
		if (!active && "CASH".equals(row.get("method_key"))) {
			throw ApiException.rule("cash_required", "Cash cannot be switched off");
		}
		int sort = update.sortOrder() == null ? ((Number) row.get("sort_order")).intValue() : update.sortOrder();
		jdbc.update("update payment_method set label = ?, active = ?, sort_order = ?, version = version + 1 where id = ?",
				label, active, sort, id);
		audit.record("PAYMENT_METHOD_CHANGED", "payment_method", id, null,
				Map.of("label", label, "active", active, "sortOrder", sort));
		return paymentMethods().stream().filter(p -> p.id() == id).findFirst().orElseThrow();
	}

	/** The owner creates a one-hour code that lets SelectCode support make changes in a support session. */
	@Transactional
	public SupportCode newSupportCode() {
		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		Instant expires = clock.instant().plus(SUPPORT_CODE_LIFETIME);
		jdbc.update("update shop_settings set support_code_hash = ?, support_code_expires = ? where shop_id = ?",
				hash(code), Timestamp.from(expires), TenantContext.requireShopId());
		audit.record("SUPPORT_CODE_ISSUED", "shop_settings", TenantContext.requireShopId(), null, null);
		return new SupportCode(code, expires);
	}

	/** Checks and uses up the owner's support code. Must run with the shop in context. */
	@Transactional
	public boolean useSupportCode(String code) {
		long shopId = TenantContext.requireShopId();
		Map<String, Object> row = jdbc.queryForMap(
				"select support_code_hash, support_code_expires from shop_settings where shop_id = ? for update", shopId);
		String stored = (String) row.get("support_code_hash");
		Timestamp expires = (Timestamp) row.get("support_code_expires");
		if (stored == null || expires == null || expires.toInstant().isBefore(clock.instant()) || code == null
				|| !MessageDigest.isEqual(stored.getBytes(), hash(code.trim()).getBytes())) {
			return false;
		}
		jdbc.update("update shop_settings set support_code_hash = null, support_code_expires = null where shop_id = ?",
				shopId);
		return true;
	}

	/** Takes the next document number for a type (Stage 3 documents). Must run inside the caller's transaction. */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public String nextNumber(String docType, Long branchId) {
		List<Map<String, Object>> rows = jdbc.queryForList("""
				update numbering set next_no = next_no + 1
				where id = (select id from numbering where doc_type = ? and (branch_id = ? or branch_id is null)
				            order by branch_id nulls last limit 1)
				returning prefix, next_no - 1 as number, digits""", docType, branchId);
		if (rows.isEmpty()) {
			throw new IllegalStateException("No numbering for " + docType);
		}
		Map<String, Object> row = rows.getFirst();
		return example((String) row.get("prefix"), ((Number) row.get("number")).longValue(),
				((Number) row.get("digits")).intValue());
	}

	static String example(String prefix, long number, int digits) {
		return prefix + "-" + String.format("%0" + digits + "d", number);
	}

	private static String hash(String code) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes()));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
