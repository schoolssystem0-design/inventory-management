package com.selectcode.pos.iam.app;

import com.selectcode.pos.iam.domain.AppUser;
import com.selectcode.pos.iam.domain.Role;
import com.selectcode.pos.iam.infra.AppUserRepository;
import com.selectcode.pos.iam.infra.RoleRepository;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager approval with PIN (ACC-04): an over-limit action names an approver and their PIN. The approver must be active,
 * have {@code approvals.grant} plus the permission the action needs, and their PIN is locked after 5 wrong tries. Used by
 * the till from Stage 3; the check itself lives here so every module uses the same rule.
 */
@Service
public class ApprovalService {

	public record Approval(long approverId, String approverName) {
	}

	private final AppUserRepository users;

	private final RoleRepository roles;

	private final PasswordEncoder encoder;

	private final AuditService audit;

	private final Clock clock;

	private final org.springframework.beans.factory.ObjectProvider<ApprovalService> selfProvider;

	ApprovalService(AppUserRepository users, RoleRepository roles, PasswordEncoder encoder, AuditService audit,
			Clock clock, org.springframework.beans.factory.ObjectProvider<ApprovalService> selfProvider) {
		this.selfProvider = selfProvider;
		this.users = users;
		this.roles = roles;
		this.encoder = encoder;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Runs in its own transaction so a wrong PIN is counted even when the calling action then fails.
	 * @param approver username of the approving user
	 * @param action short description recorded in the activity history
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
	public Approval approve(String approver, String pin, String requiredPermission, String action) {
		Instant now = clock.instant();
		AppUser user = approver == null ? null : users.findByUsernameIgnoreCase(approver.trim()).orElse(null);
		if (user == null || user.getStatus() != AppUser.Status.ACTIVE || user.getPinHash() == null) {
			throw denied("approver_invalid", "This approver cannot approve (no PIN set or not active)");
		}
		if (user.pinLocked(now)) {
			throw denied("pin_locked", "Too many wrong PINs. Try again in 15 minutes.");
		}
		if (pin == null || !encoder.matches(pin, user.getPinHash())) {
			user.pinFailed(now);
			users.saveAndFlush(user);
			throw denied("pin_wrong", "The PIN is not correct");
		}
		Role role = roles.findById(user.getRoleId()).orElseThrow();
		boolean allowed = user.isOwner() || (role.getPermissions().contains(Permissions.APPROVALS_GRANT)
				&& (requiredPermission == null || role.getPermissions().contains(requiredPermission)));
		if (!allowed) {
			throw denied("approver_not_allowed", user.getDisplayName() + " is not allowed to approve this");
		}
		user.pinSucceeded();
		users.saveAndFlush(user);
		audit.record("APPROVED", "app_user", user.getId(), null, Map.of("action", action));
		return new Approval(user.getId(), user.getDisplayName());
	}

	/**
	 * An over-limit action (ACC-04, docs/API-CONVENTIONS.md §2 "Approvals"). A user who may approve (owner, or
	 * {@code approvals.grant} plus {@code permission}) approves their own action. Anyone else must send an approver
	 * and PIN ({@code X-Approval-User}, {@code X-Approval-Pin}); without them the answer is 403
	 * {@code approval_required} naming the limit that was hit.
	 * @return the approving user's id
	 */
	public long require(ApprovalRequest request, String permission, String action, String limit) {
		if (CurrentActor.hasPermission(Permissions.APPROVALS_GRANT)
				&& (permission == null || CurrentActor.hasPermission(permission))) {
			return CurrentActor.id() == null ? 0 : CurrentActor.id();
		}
		if (request == null || request.user() == null || request.user().isBlank()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "approval_required",
					"Manager approval needed: " + limit + ". Ask a manager to enter their PIN.", Map.of("limit", limit));
		}
		return self().approve(request.user(), request.pin(), permission, action).approverId();
	}

	/** Approver and PIN sent with a request. */
	public record ApprovalRequest(String user, String pin) {

		public static ApprovalRequest of(String user, String pin) {
			return user == null || user.isBlank() ? null : new ApprovalRequest(user, pin);
		}
	}

	private ApprovalService self() {
		return selfProvider.getObject();
	}

	private static ApiException denied(String code, String message) {
		return new ApiException(HttpStatus.FORBIDDEN, code, message);
	}
}
