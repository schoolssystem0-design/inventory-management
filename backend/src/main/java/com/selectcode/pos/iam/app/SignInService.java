package com.selectcode.pos.iam.app;

import com.selectcode.pos.iam.ShopUserPrincipal;
import com.selectcode.pos.iam.domain.AppUser;
import com.selectcode.pos.iam.infra.AppUserRepository;
import com.selectcode.pos.shared.security.SecurityProperties;
import com.selectcode.pos.shared.security.Totp;
import com.selectcode.pos.shared.tenant.ShopLookup;
import com.selectcode.pos.shared.tenant.ShopLookup.ShopRef;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shop user sign-in: shop code + username + password, and the authenticator code when the user turned it on (ACC-06,
 * ACC-10). Every attempt goes to login history; 5 wrong passwords lock the account for 15 minutes.
 */
@Service
public class SignInService {

	public record SignInAttempt(String shopCode, String username, String password, String totpCode, String ip,
			String userAgent) {
	}

	/** @param idleMinutes the shop's session timeout setting */
	public record SignedIn(ShopUserPrincipal principal, int idleMinutes) {
	}

	private final ShopLookup shops;

	private final AppUserRepository users;

	private final JdbcTemplate jdbc;

	private final PasswordEncoder passwordEncoder;

	private final TransactionTemplate transaction;

	private final RateLimiter rateLimiter;

	private final Clock clock;

	private final int signInLimit;

	private final String dummyHash;

	SignInService(ShopLookup shops, AppUserRepository users, JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
			PlatformTransactionManager transactionManager, RateLimiter rateLimiter, Clock clock,
			SecurityProperties security) {
		this.shops = shops;
		this.users = users;
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.transaction = new TransactionTemplate(transactionManager);
		this.rateLimiter = rateLimiter;
		this.clock = clock;
		this.signInLimit = security.signinLimitPerNetwork();
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	public SignedIn signIn(SignInAttempt attempt) {
		rateLimiter.check("signin:" + attempt.ip(), signInLimit, Duration.ofMinutes(5));
		ShopRef shop = shops.findByCode(attempt.shopCode()).orElse(null);
		if (shop == null) {
			passwordEncoder.matches(attempt.password(), dummyHash);
			throw invalidCredentials();
		}
		if (!shop.canSignIn()) {
			throw new ApiException(HttpStatus.LOCKED, "shop_suspended",
					"This shop's account is suspended. Please contact " + com.selectcode.pos.shared.Brand.support() + ".");
		}
		// The tenant is set before the transaction starts, so the connection gets the shop's RLS context.
		Object outcome = TenantContext.callAsShop(shop.id(), () -> transaction.execute(tx -> attempt(shop, attempt)));
		if (outcome instanceof ApiException error) {
			throw error;
		}
		return (SignedIn) outcome;
	}

	private Object attempt(ShopRef shop, SignInAttempt attempt) {
		Instant now = clock.instant();
		AppUser user = users.findByUsernameIgnoreCase(attempt.username().trim()).orElse(null);
		if (user == null) {
			passwordEncoder.matches(attempt.password(), dummyHash);
			record(null, attempt, "INVALID");
			return invalidCredentials();
		}
		if (user.getStatus() == AppUser.Status.INACTIVE) {
			record(user.getId(), attempt, "BLOCKED");
			return invalidCredentials();
		}
		if (user.isLocked(now)) {
			record(user.getId(), attempt, "BLOCKED");
			return new ApiException(HttpStatus.LOCKED, "account_locked",
					"Too many wrong passwords. Try again in 15 minutes or ask the owner to unlock you.");
		}
		if (!passwordEncoder.matches(attempt.password(), user.getPasswordHash())) {
			users.recordFailedSignIn(user.getId(), AppUser.MAX_FAILED_ATTEMPTS, now.plus(AppUser.LOCK_DURATION));
			record(user.getId(), attempt, "INVALID");
			return invalidCredentials();
		}
		if (user.getTotpSecret() != null) {
			if (attempt.totpCode() == null || attempt.totpCode().isBlank()) {
				return new ApiException(HttpStatus.UNAUTHORIZED, "totp_required",
						"Enter the 6-digit code from your authenticator app");
			}
			if (!Totp.verify(user.getTotpSecret(), attempt.totpCode(), now)) {
				users.recordFailedSignIn(user.getId(), AppUser.MAX_FAILED_ATTEMPTS, now.plus(AppUser.LOCK_DURATION));
				record(user.getId(), attempt, "TOTP_INVALID");
				return new ApiException(HttpStatus.UNAUTHORIZED, "totp_invalid", "The authenticator code is not correct");
			}
		}
		users.recordSuccessfulSignIn(user.getId(), now);
		record(user.getId(), attempt, "SUCCESS");
		Integer idle = jdbc.queryForObject("select session_idle_minutes from shop_settings where shop_id = ?",
				Integer.class, shop.id());
		return new SignedIn(new ShopUserPrincipal(user.getId(), shop.id(), shop.code(), shop.name(), user.getUsername(),
				user.getDisplayName()), idle == null ? 10 : idle);
	}

	private void record(Long userId, SignInAttempt attempt, String result) {
		jdbc.update("""
				insert into login_event (shop_id, user_id, username, result, ip, user_agent) values (?, ?, ?, ?, ?, ?)""",
				TenantContext.requireShopId(), userId, truncate(attempt.username(), 60), result,
				truncate(attempt.ip(), 64), truncate(attempt.userAgent(), 400));
	}

	private static String truncate(String v, int max) {
		return v == null || v.length() <= max ? v : v.substring(0, max);
	}

	private static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Username or password is incorrect");
	}
}
