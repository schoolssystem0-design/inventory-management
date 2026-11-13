package com.selectcode.pos.iam.infra;

import com.selectcode.pos.shared.tenant.TenantContext;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * One-time tokens for account setup links and password resets (ACC-08, ONB-01). The token sent to the user is
 * {@code <shopId>.<random>}; the shop prefix lets the server set the tenant before looking the token up. Only the
 * SHA-256 hash is stored.
 */
@Repository
public class PasswordTokens {

	public enum Purpose {
		SETUP, RESET
	}

	public record IssuedToken(String token, Instant expiresAt) {
	}

	public record Valid(long tokenId, long userId, Purpose purpose) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;

	PasswordTokens(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Issues a new token and invalidates the user's earlier unused tokens of the same purpose. */
	public IssuedToken issue(long userId, Purpose purpose, Instant expiresAt, Instant now) {
		long shopId = TenantContext.requireShopId();
		jdbc.update("update password_token set used_at = ? where user_id = ? and purpose = ? and used_at is null",
				Timestamp.from(now), userId, purpose.name());
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		String token = shopId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		jdbc.update("""
				insert into password_token (shop_id, user_id, purpose, token_hash, expires_at)
				values (?, ?, ?, ?, ?)""", shopId, userId, purpose.name(), hash(token),
				Timestamp.from(expiresAt));
		return new IssuedToken(token, expiresAt);
	}

	/** The shop encoded in a token, if it is well formed. */
	public static OptionalLong shopOf(String token) {
		if (token == null) {
			return OptionalLong.empty();
		}
		int dot = token.indexOf('.');
		if (dot <= 0 || dot > 19) {
			return OptionalLong.empty();
		}
		try {
			return OptionalLong.of(Long.parseLong(token.substring(0, dot)));
		}
		catch (NumberFormatException ex) {
			return OptionalLong.empty();
		}
	}

	/** Must run with the token's shop in TenantContext. */
	public Optional<Valid> findValid(String token, Instant now) {
		List<Valid> rows = jdbc.query("""
				select id, user_id, purpose from password_token
				where token_hash = ? and used_at is null and expires_at > ?""",
				(rs, i) -> new Valid(rs.getLong(1), rs.getLong(2), Purpose.valueOf(rs.getString(3))), hash(token),
				Timestamp.from(now));
		return rows.stream().findFirst();
	}

	public void markUsed(long tokenId, Instant now) {
		jdbc.update("update password_token set used_at = ? where id = ?", Timestamp.from(now), tokenId);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes()));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
