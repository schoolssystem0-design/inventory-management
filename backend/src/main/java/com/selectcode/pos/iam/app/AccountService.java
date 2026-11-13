package com.selectcode.pos.iam.app;

import com.selectcode.pos.iam.domain.AppUser;
import com.selectcode.pos.iam.domain.PasswordPolicy;
import com.selectcode.pos.iam.infra.AppUserRepository;
import com.selectcode.pos.iam.infra.PasswordTokens;
import com.selectcode.pos.iam.infra.SessionRevoker;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.messaging.MessageSender;
import com.selectcode.pos.shared.messaging.OutgoingMessage;
import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Totp;
import com.selectcode.pos.shared.tenant.ShopLookup;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.AppLinks;
import com.selectcode.pos.shared.web.RateLimiter;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The user's own account (ACC-08, ACC-09, ACC-10, ONB-01): change password, complete a setup or reset link, request a
 * reset, set the approval PIN and turn the authenticator app on or off. Passwords are never shown or retrievable.
 */
@Service
public class AccountService {

	public static final Duration RESET_LINK_LIFETIME = Duration.ofMinutes(60);

	public static final Duration SETUP_LINK_LIFETIME = Duration.ofHours(48);

	static final String PENDING_TOTP = "pos.pendingTotp";

	public record TotpEnrolment(String secret, String otpauthUri) {
	}

	record PendingTotp(long userId, String secret, Instant expiresAt) implements Serializable {
	}

	private final AppUserRepository users;

	private final PasswordTokens tokens;

	private final PasswordEncoder passwordEncoder;

	private final SessionRevoker sessions;

	private final ShopLookup shops;

	private final MessageSender messages;

	private final AuditService audit;

	private final RateLimiter rateLimiter;

	private final AppLinks links;

	private final TransactionTemplate transaction;

	private final Clock clock;

	AccountService(AppUserRepository users, PasswordTokens tokens, PasswordEncoder passwordEncoder,
			SessionRevoker sessions, ShopLookup shops, MessageSender messages, AuditService audit,
			RateLimiter rateLimiter, AppLinks links, PlatformTransactionManager transactionManager, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.sessions = sessions;
		this.shops = shops;
		this.messages = messages;
		this.audit = audit;
		this.rateLimiter = rateLimiter;
		this.links = links;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	public String setupLink(String shopCode, String token) {
		return links.shop(shopCode, "/setup?token=" + token);
	}

	public String resetLink(String shopCode, String token) {
		return links.shop(shopCode, "/reset-password?token=" + token);
	}

	/** Signed-in user changes their own password; their other sessions are ended. */
	public void changePassword(String currentPassword, String newPassword, String keepSessionId) {
		long userId = requireShopUser();
		long shopId = TenantContext.requireShopId();
		transaction.executeWithoutResult(tx -> {
			AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
			if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
				throw ApiException.field("currentPassword", "wrong", "The current password is not correct");
			}
			if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
				throw ApiException.field("newPassword", "unchanged", "Choose a password different from the current one");
			}
			PasswordPolicy.check(newPassword, user.getUsername());
			user.setPassword(passwordEncoder.encode(newPassword), false, clock.instant());
			audit.record("PASSWORD_CHANGED", "app_user", user.getId(), null, null);
		});
		sessions.revoke(shopId, userId, keepSessionId);
	}

	/** Completes a setup link (new user) or a reset link and sets the password. */
	public void completeWithToken(String token, String newPassword) {
		long shopId = PasswordTokens.shopOf(token).orElseThrow(AccountService::invalidLink);
		Instant now = clock.instant();
		long userId = TenantContext.callAsShop(shopId, () -> transaction.execute(tx -> {
			PasswordTokens.Valid valid = tokens.findValid(token, now).orElseThrow(AccountService::invalidLink);
			AppUser user = users.findById(valid.userId()).orElseThrow(AccountService::invalidLink);
			if (user.getStatus() == AppUser.Status.INACTIVE) {
				throw invalidLink();
			}
			PasswordPolicy.check(newPassword, user.getUsername());
			user.setPassword(passwordEncoder.encode(newPassword), false, now);
			tokens.markUsed(valid.tokenId(), now);
			audit.record(valid.purpose() == PasswordTokens.Purpose.SETUP ? "ACCOUNT_SETUP_COMPLETED"
					: "PASSWORD_RESET_COMPLETED", "app_user", user.getId(), null, null);
			return user.getId();
		}));
		TenantContext.runAsShop(shopId, () -> sessions.revoke(shopId, userId, null));
	}

	/** Emails a reset link if shop, user and email exist. Always behaves the same (no account enumeration). */
	public void requestReset(String shopCode, String username, String ip) {
		rateLimiter.check("reset:" + ip, 5, Duration.ofMinutes(15));
		var shop = shops.findByCode(shopCode).filter(ShopLookup.ShopRef::canSignIn).orElse(null);
		if (shop == null || username == null || username.isBlank()) {
			return;
		}
		Instant now = clock.instant();
		TenantContext.runAsShop(shop.id(), () -> transaction.executeWithoutResult(tx -> {
			AppUser user = users.findByUsernameIgnoreCase(username.trim()).orElse(null);
			if (user == null || user.getStatus() != AppUser.Status.ACTIVE || user.getEmail() == null) {
				return;
			}
			PasswordTokens.IssuedToken issued = tokens.issue(user.getId(), PasswordTokens.Purpose.RESET,
					now.plus(RESET_LINK_LIFETIME), now);
			messages.send(OutgoingMessage.email(user.getEmail(), "Reset your password", """
					Someone asked to reset the password for %s at %s.

					Open this link within 60 minutes to choose a new password:
					%s

					If this wasn't you, ignore this email; your password stays the same.
					""".formatted(user.getUsername(), shop.name(), resetLink(shop.code(), issued.token()))));
			audit.record("PASSWORD_RESET_REQUESTED", "app_user", user.getId(), null, null);
		}));
	}

	/**
	 * Unlocks the POS lock screen with the user's own PIN (ACC-09). Five wrong PINs lock the PIN for 15 minutes; the
	 * user then signs in again with their password.
	 */
	public void unlock(String pin) {
		long userId = requireShopUser();
		String result = transaction.execute(tx -> {
			AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
			if (user.getPinHash() == null) {
				return "pin_not_set";
			}
			if (user.pinLocked(clock.instant())) {
				return "pin_locked";
			}
			if (pin == null || !passwordEncoder.matches(pin, user.getPinHash())) {
				user.pinFailed(clock.instant());
				users.saveAndFlush(user);
				return "pin_wrong";
			}
			user.pinSucceeded();
			users.saveAndFlush(user);
			return null;
		});
		if ("pin_not_set".equals(result)) {
			throw ApiException.rule("pin_not_set", "Set a PIN in My account first, or sign in again");
		}
		if ("pin_locked".equals(result)) {
			throw ApiException.forbidden("pin_locked", "Too many wrong PINs. Sign in again with your password.");
		}
		if ("pin_wrong".equals(result)) {
			throw ApiException.forbidden("pin_wrong", "The PIN is not correct");
		}
	}

	/** Sets the user's approval PIN (4–6 digits). The password confirms it is really them. */
	public void setPin(String password, String pin) {
		long userId = requireShopUser();
		if (pin == null || !pin.matches("\\d{4,6}")) {
			throw ApiException.field("pin", "invalid", "Use 4 to 6 digits");
		}
		if (pin.chars().distinct().count() == 1 || "123456".startsWith(pin) || "654321".startsWith(pin)) {
			throw ApiException.field("pin", "too_simple", "Choose a PIN that is harder to guess");
		}
		transaction.executeWithoutResult(tx -> {
			AppUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
			if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
				throw ApiException.field("password", "wrong", "The password is not correct");
			}
			user.setPin(passwordEncoder.encode(pin), clock.instant());
			audit.record("PIN_SET", "app_user", userId, null, null);
		});
	}

	/** Step 1 of turning on the authenticator app: a new secret kept in the session until confirmed. */
	public TotpEnrolment startTotp(HttpSession session) {
		long userId = requireShopUser();
		String secret = Totp.newSecret();
		session.setAttribute(PENDING_TOTP, new PendingTotp(userId, secret, clock.instant().plus(Duration.ofMinutes(10))));
		ActorPrincipal actor = CurrentActor.require();
		return new TotpEnrolment(secret, Totp.otpauthUri("SelectCode POS", actor.loginName() + "@" + actor.shopCode(), secret));
	}

	/** Step 2: the first code proves the app was set up; from now on sign-in asks for a code. */
	public void confirmTotp(String code, HttpSession session) {
		long userId = requireShopUser();
		Object value = session.getAttribute(PENDING_TOTP);
		Instant now = clock.instant();
		if (!(value instanceof PendingTotp pending) || pending.userId() != userId || pending.expiresAt().isBefore(now)) {
			throw ApiException.rule("enrolment_expired", "The setup has expired. Start again.");
		}
		if (!Totp.verify(pending.secret(), code, now)) {
			throw ApiException.field("code", "wrong", "The authenticator code is not correct");
		}
		session.removeAttribute(PENDING_TOTP);
		transaction.executeWithoutResult(tx -> {
			users.findById(userId).orElseThrow().setTotpSecret(pending.secret(), now);
			audit.record("TOTP_ENABLED", "app_user", userId, null, null);
		});
	}

	public void disableTotp(String password) {
		long userId = requireShopUser();
		transaction.executeWithoutResult(tx -> {
			AppUser user = users.findById(userId).orElseThrow();
			if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
				throw ApiException.field("password", "wrong", "The password is not correct");
			}
			user.setTotpSecret(null, clock.instant());
			audit.record("TOTP_DISABLED", "app_user", userId, null, null);
		});
	}

	private static long requireShopUser() {
		ActorPrincipal actor = CurrentActor.require();
		if (actor.actorType() != ActorType.USER) {
			throw new ApiException(HttpStatus.FORBIDDEN, "not_a_shop_user", "Only shop users have this setting");
		}
		return actor.actorId();
	}

	private static ApiException invalidLink() {
		return ApiException.rule("link_invalid", "This link is invalid or has expired. Ask for a new one.");
	}
}
