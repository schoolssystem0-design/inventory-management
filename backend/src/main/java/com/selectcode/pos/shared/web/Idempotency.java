package com.selectcode.pos.shared.web;

import com.selectcode.pos.shared.tenant.TenantContext;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency keys for requests that create money documents (docs/architecture/API-CONVENTIONS.md §2, POS-09). The key row is
 * inserted first in the caller's transaction: a second request with the same key waits for the first to commit and
 * then gets the first document instead of making a duplicate. If the first rolls back, the second goes ahead.
 */
@Component
public class Idempotency {

	private final JdbcTemplate jdbc;

	Idempotency(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @return the document made earlier with this key, or empty when the caller should create it now
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Long> claim(String scope, String key) {
		if (key == null || key.isBlank()) {
			return Optional.empty();
		}
		String k = key.trim();
		if (k.length() > 80) {
			throw ApiException.field("Idempotency-Key", "too_long", "The request key is too long");
		}
		int inserted = jdbc.update("""
				insert into idempotency_key (shop_id, scope, key) values (?, ?, ?) on conflict do nothing""",
				TenantContext.requireShopId(), scope, k);
		if (inserted == 1) {
			return Optional.empty();
		}
		Long doc = jdbc.queryForObject("select doc_id from idempotency_key where scope = ? and key = ?", Long.class,
				scope, k);
		if (doc == null) {
			throw ApiException.conflict("request_in_progress", "This request is still being processed. Try again.");
		}
		return Optional.of(doc);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(String scope, String key, long docId) {
		if (key == null || key.isBlank()) {
			return;
		}
		jdbc.update("update idempotency_key set doc_id = ? where scope = ? and key = ?", docId, scope, key.trim());
	}

	/** Requires a key (used where the API conventions say the header is mandatory). */
	public static String require(String key) {
		if (key == null || key.isBlank()) {
			throw ApiException.field("Idempotency-Key", "required",
					"Send an Idempotency-Key header so a retried request is not saved twice");
		}
		return key;
	}
}
