package com.selectcode.pos.shared.persistence;

import com.selectcode.pos.shared.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free per-shop counters (SKUs and later document numbers). The row is locked by the UPDATE, so concurrent callers
 * get distinct values; a rolled-back caller's value is reused.
 */
@Component
public class NumberSequences {

	private final JdbcTemplate jdbc;

	NumberSequences(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Must run inside the caller's transaction (so the number is only used if the caller commits). */
	@Transactional(propagation = Propagation.MANDATORY)
	public long next(String name) {
		long shopId = TenantContext.requireShopId();
		jdbc.update("insert into number_sequence (shop_id, name, next_value) values (?, ?, 1) on conflict do nothing",
				shopId, name);
		Long value = jdbc.queryForObject("""
				update number_sequence set next_value = next_value + 1
				where shop_id = ? and name = ? returning next_value - 1""", Long.class, shopId, name);
		return value == null ? 1 : value;
	}

	/** e.g. format("P", 7, 5) = "P-00007". */
	public static String format(String prefix, long value, int digits) {
		return prefix + "-" + String.format("%0" + digits + "d", value);
	}
}
