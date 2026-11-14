package com.selectcode.pos.iam.app;

import com.selectcode.pos.iam.domain.AppUser;
import com.selectcode.pos.iam.domain.PasswordPolicy;
import com.selectcode.pos.iam.domain.Role;
import com.selectcode.pos.iam.domain.RoleTemplates;
import com.selectcode.pos.iam.infra.AppUserRepository;
import com.selectcode.pos.iam.infra.PasswordTokens;
import com.selectcode.pos.iam.infra.RoleRepository;
import com.selectcode.pos.iam.infra.SessionRevoker;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.PlanLimits;
import com.selectcode.pos.shared.messaging.MessageSender;
import com.selectcode.pos.shared.messaging.OutgoingMessage;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.ShopLookup;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shop users and their roles and branches (ACC-01, ACC-03, ACC-08), and the owner provisioning used by the platform
 * when a shop is created (ONB-01, PLT-13).
 */
@Service
public class UserAdminService {

	public record UserView(long id, String username, String displayName, String phone, String email, long roleId,
			String roleName, String roleKey, boolean owner, boolean allBranches, List<Long> branchIds, String status,
			boolean locked, boolean pinSet, boolean totpEnabled, boolean mustChangePassword, Instant lastLoginAt,
			Instant createdAt, long version) {
	}

	public record UserInput(String username, String displayName, String phone, String email, Long roleId,
			Boolean allBranches, List<Long> branchIds, String password, Boolean sendSetupLink) {
	}

	/** {@code temporaryPassword} is shown once and never stored in clear text. */
	public record UserCreated(UserView user, String temporaryPassword, boolean setupLinkSent) {
	}

	public record PasswordReset(String temporaryPassword, boolean linkSent) {
	}

	public record SetupLink(long userId, String link, Instant expiresAt, boolean emailed) {
	}

	public record OwnerInput(String name, String username, String email, String phone) {
	}

	public record OwnerSummary(long userId, String username, String displayName, String email, String phone,
			Instant lastLoginAt, boolean passwordSet) {
	}

	private final AppUserRepository users;

	private final RoleRepository roles;

	private final PasswordTokens tokens;

	private final PasswordEncoder encoder;

	private final SessionRevoker sessions;

	private final JdbcTemplate jdbc;

	private final PlanLimits limits;

	private final MessageSender messages;

	private final AccountService accounts;

	private final ShopLookup shops;

	private final AuditService audit;

	private final Clock clock;

	UserAdminService(AppUserRepository users, RoleRepository roles, PasswordTokens tokens, PasswordEncoder encoder,
			SessionRevoker sessions, JdbcTemplate jdbc, PlanLimits limits, MessageSender messages,
			AccountService accounts, ShopLookup shops, AuditService audit, Clock clock) {
		this.users = users;
		this.roles = roles;
		this.tokens = tokens;
		this.encoder = encoder;
		this.sessions = sessions;
		this.jdbc = jdbc;
		this.limits = limits;
		this.messages = messages;
		this.accounts = accounts;
		this.shops = shops;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ queries

	@Transactional(readOnly = true)
	public PageResponse<UserView> list(String q, String status, int page, int size) {
		String term = q == null ? "" : q.trim().toLowerCase();
		List<AppUser> all = users.findAll()
			.stream()
			.filter(u -> status == null || status.isBlank() || u.getStatus().name().equalsIgnoreCase(status))
			.filter(u -> term.isEmpty() || u.getUsername().toLowerCase().contains(term)
					|| u.getDisplayName().toLowerCase().contains(term)
					|| (u.getPhone() != null && u.getPhone().contains(term)))
			.sorted(java.util.Comparator.comparing(AppUser::isOwner).reversed()
				.thenComparing(u -> u.getDisplayName().toLowerCase()))
			.toList();
		Map<Long, Role> roleById = roleMap();
		Map<Long, List<Long>> branches = userBranches();
		List<UserView> items = all.stream()
			.skip((long) page * size)
			.limit(size)
			.map(u -> view(u, roleById, branches))
			.toList();
		return PageResponse.of(items, page, size, all.size());
	}

	@Transactional(readOnly = true)
	public UserView get(long id) {
		return view(find(id), roleMap(), userBranches());
	}

	// ------------------------------------------------------------------ commands

	@Transactional
	public UserCreated create(UserInput in) {
		limits.check(PlanLimits.Limit.USERS, users.countByStatus(AppUser.Status.ACTIVE), 1);
		String username = username(in.username());
		if (users.findByUsernameIgnoreCase(username).isPresent()) {
			throw ApiException.conflict("duplicate_username", "Username " + username + " is already taken");
		}
		Role role = assignableRole(in.roleId());
		String displayName = required(in.displayName(), "displayName", 200);
		String email = email(in.email());
		boolean link = Boolean.TRUE.equals(in.sendSetupLink());
		if (link && email == null) {
			throw ApiException.field("email", "required", "Enter an email address to send a setup link");
		}
		String temporary = null;
		String hash;
		if (link) {
			hash = encoder.encode(PasswordPolicy.temporary() + PasswordPolicy.temporary());
		}
		else if (in.password() != null && !in.password().isBlank()) {
			PasswordPolicy.check(in.password(), username);
			hash = encoder.encode(in.password());
		}
		else {
			temporary = PasswordPolicy.temporary();
			hash = encoder.encode(temporary);
		}
		AppUser user = new AppUser(username, displayName, role.getId(), hash);
		user.updateProfile(displayName, phone(in.phone()), email, clock.instant());
		// A password typed by someone else, or generated, must be changed at first sign-in.
		user.setPassword(hash, true, clock.instant());
		user = users.saveAndFlush(user);
		setBranches(user, in.allBranches(), in.branchIds());
		boolean sent = false;
		if (link) {
			sent = sendSetupLink(user).emailed();
		}
		audit.record("USER_CREATED", "app_user", user.getId(), null,
				Map.of("username", username, "role", role.getName()));
		return new UserCreated(get(user.getId()), temporary, sent);
	}

	@Transactional
	public UserView update(long id, UserInput in) {
		AppUser user = find(id);
		Map<String, Object> before = Map.of("displayName", user.getDisplayName(), "roleId", user.getRoleId());
		if (in.username() != null && !in.username().equalsIgnoreCase(user.getUsername())) {
			throw ApiException.field("username", "fixed", "A username cannot be changed; create a new user instead");
		}
		guardOwner(user);
		String displayName = in.displayName() == null ? user.getDisplayName() : required(in.displayName(), "displayName", 200);
		user.updateProfile(displayName, in.phone() == null ? user.getPhone() : phone(in.phone()),
				in.email() == null ? user.getEmail() : email(in.email()), clock.instant());
		if (in.roleId() != null && in.roleId() != user.getRoleId()) {
			if (user.isOwner()) {
				throw ApiException.rule("owner_role", "The owner always has every permission");
			}
			user.setRole(assignableRole(in.roleId()).getId(), clock.instant());
		}
		if (in.allBranches() != null || in.branchIds() != null) {
			setBranches(user, in.allBranches() == null ? user.isAllBranches() : in.allBranches(),
					in.branchIds() == null ? branchIds(user.getId()) : in.branchIds());
		}
		users.flush();
		audit.record("USER_UPDATED", "app_user", id, before,
				Map.of("displayName", displayName, "roleId", user.getRoleId()));
		return get(id);
	}

	@Transactional
	public UserView setActive(long id, boolean active) {
		AppUser user = find(id);
		if (user.isOwner()) {
			throw ApiException.rule("owner_status",
					"The owner cannot be deactivated; " + com.selectcode.pos.shared.Brand.support() + " can change the owner");
		}
		if (active && user.getStatus() == AppUser.Status.INACTIVE) {
			limits.check(PlanLimits.Limit.USERS, users.countByStatus(AppUser.Status.ACTIVE), 1);
		}
		if (CurrentActor.type() == ActorType.USER && CurrentActor.id() != null && CurrentActor.id() == id) {
			throw ApiException.rule("self", "You cannot deactivate yourself");
		}
		user.setStatus(active ? AppUser.Status.ACTIVE : AppUser.Status.INACTIVE, clock.instant());
		if (active) {
			user.unlock(clock.instant());
		}
		users.flush();
		if (!active) {
			sessions.revoke(TenantContext.requireShopId(), id, null);
		}
		audit.record(active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "app_user", id, null, null);
		return get(id);
	}

	/** Temporary password (shown once, must be changed) or a reset link by email. Also unlocks the account. */
	@Transactional
	public PasswordReset resetPassword(long id, boolean byLink) {
		AppUser user = find(id);
		guardOwner(user);
		user.unlock(clock.instant());
		if (byLink) {
			if (user.getEmail() == null) {
				throw ApiException.rule("no_email", "This user has no email address; use a temporary password");
			}
			Instant now = clock.instant();
			var issued = tokens.issue(id, PasswordTokens.Purpose.RESET, now.plus(AccountService.RESET_LINK_LIFETIME), now);
			String code = TenantContext.shopId().flatMap(shops::findById).map(ShopLookup.ShopRef::code).orElseThrow();
			boolean sent = messages.send(OutgoingMessage.email(user.getEmail(), "Reset your password",
					"Open this link within 60 minutes to choose a new password:\n"
							+ accounts.resetLink(code, issued.token())));
			audit.record("PASSWORD_RESET_LINK_SENT", "app_user", id, null, null);
			return new PasswordReset(null, sent);
		}
		String temporary = PasswordPolicy.temporary();
		user.setPassword(encoder.encode(temporary), true, clock.instant());
		users.flush();
		sessions.revoke(TenantContext.requireShopId(), id, null);
		audit.record("PASSWORD_RESET_BY_ADMIN", "app_user", id, null, null);
		return new PasswordReset(temporary, false);
	}

	// ------------------------------------------------------------------ provisioning (platform)

	/** Creates the system roles and the owner of a new shop and returns the owner's setup link. */
	@Transactional(propagation = Propagation.MANDATORY)
	public SetupLink provisionShop(OwnerInput owner, Set<ModuleKey> modules) {
		ensureSystemRoles(modules);
		Role ownerRole = roles.findBySystemKey(RoleTemplates.OWNER).orElseThrow();
		String username = username(owner.username() == null || owner.username().isBlank() ? "owner" : owner.username());
		AppUser user = new AppUser(username, required(owner.name(), "ownerName", 200), ownerRole.getId(),
				encoder.encode(PasswordPolicy.temporary() + PasswordPolicy.temporary()));
		user.updateProfile(user.getDisplayName(), phone(owner.phone()), email(owner.email()), clock.instant());
		user.setOwner(true);
		user = users.saveAndFlush(user);
		audit.record("OWNER_CREATED", "app_user", user.getId(), null, Map.of("username", username));
		return sendSetupLink(user);
	}

	/** Adds system roles that are missing (e.g. Pharmacist after the formula module is switched on). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void ensureSystemRoles(Set<ModuleKey> modules) {
		for (RoleTemplates.Template template : RoleTemplates.forModules(modules)) {
			roles.findBySystemKey(template.key()).ifPresentOrElse(
					existing -> existing.setPermissions(template.permissions(), clock.instant()),
					() -> roles.save(new Role(template.name(), template.key(), template.description(),
							template.permissions())));
		}
		roles.flush();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public SetupLink issueOwnerSetupLink() {
		AppUser owner = users.findByOwnerTrue().orElseThrow(() -> ApiException.notFound("Owner"));
		owner.unlock(clock.instant());
		owner.setStatus(AppUser.Status.ACTIVE, clock.instant());
		return sendSetupLink(owner);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public OwnerSummary owner() {
		return users.findByOwnerTrue()
			.map(u -> new OwnerSummary(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.getPhone(),
					u.getLastLoginAt(), u.getLastLoginAt() != null || !u.isMustChangePassword()))
			.orElse(null);
	}

	/** Makes another active user the owner; the old owner becomes a branch manager. */
	@Transactional(propagation = Propagation.MANDATORY)
	public OwnerSummary changeOwner(long newOwnerId) {
		AppUser next = find(newOwnerId);
		if (next.getStatus() != AppUser.Status.ACTIVE) {
			throw ApiException.rule("user_inactive", "Choose an active user");
		}
		if (next.isOwner()) {
			return owner();
		}
		Role ownerRole = roles.findBySystemKey(RoleTemplates.OWNER).orElseThrow();
		Role manager = roles.findBySystemKey("MANAGER").orElse(ownerRole);
		users.findByOwnerTrue().ifPresent(old -> {
			old.setOwner(false);
			old.setRole(manager.getId(), clock.instant());
			users.saveAndFlush(old);
		});
		next.setOwner(true);
		next.setRole(ownerRole.getId(), clock.instant());
		next.setAllBranches(true);
		users.saveAndFlush(next);
		audit.record("OWNER_CHANGED", "app_user", newOwnerId, null, null);
		return owner();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void revokeAllSessions() {
		long shopId = TenantContext.requireShopId();
		for (AppUser user : users.findAll()) {
			sessions.revoke(shopId, user.getId(), null);
		}
	}

	@Transactional(readOnly = true)
	public long activeUserCount() {
		return users.countByStatus(AppUser.Status.ACTIVE);
	}

	// ------------------------------------------------------------------ helpers

	private SetupLink sendSetupLink(AppUser user) {
		Instant now = clock.instant();
		var issued = tokens.issue(user.getId(), PasswordTokens.Purpose.SETUP, now.plus(AccountService.SETUP_LINK_LIFETIME),
				now);
		var shop = shops.findById(TenantContext.requireShopId()).orElseThrow();
		String link = accounts.setupLink(shop.code(), issued.token());
		boolean emailed = false;
		if (user.getEmail() != null) {
			emailed = messages.send(OutgoingMessage.email(user.getEmail(),
					"Set up your " + com.selectcode.pos.shared.Brand.PRODUCT + " account", """
					Hello %s,

					An account has been created for you at %s.
					Username: %s

					Open this link within 48 hours to choose your password:
					%s
					""".formatted(user.getDisplayName(), shop.name(), user.getUsername(), link)));
		}
		return new SetupLink(user.getId(), link, issued.expiresAt(), emailed);
	}

	private void guardOwner(AppUser user) {
		// Only the owner (or SelectCode support) may change the owner's account.
		if (user.isOwner() && CurrentActor.type() == ActorType.USER
				&& (CurrentActor.id() == null || CurrentActor.id() != user.getId().longValue())) {
			throw ApiException.forbidden("owner_protected", "Only the owner can change the owner's account");
		}
	}

	private Role assignableRole(Long roleId) {
		if (roleId == null) {
			throw ApiException.field("roleId", "required", "Choose a role");
		}
		Role role = roles.findById(roleId).orElseThrow(() -> ApiException.field("roleId", "not_found", "Role not found"));
		if (RoleTemplates.OWNER.equals(role.getSystemKey())) {
			throw ApiException.field("roleId", "owner_role",
					"There is one owner per shop; " + com.selectcode.pos.shared.Brand.support() + " can change it");
		}
		return role;
	}

	private void setBranches(AppUser user, Boolean all, List<Long> branchIds) {
		boolean everyBranch = all == null || all || user.isOwner();
		Set<Long> ids = new HashSet<>(branchIds == null ? List.of() : branchIds);
		if (!everyBranch && ids.isEmpty()) {
			throw ApiException.field("branchIds", "required", "Choose at least one branch, or all branches");
		}
		for (Long branchId : ids) {
			Integer found = jdbc.queryForObject("select count(*) from branch where id = ?", Integer.class, branchId);
			if (found == null || found == 0) {
				throw ApiException.field("branchIds", "not_found", "Branch not found");
			}
		}
		user.setAllBranches(everyBranch);
		users.saveAndFlush(user);
		jdbc.update("delete from user_branch where user_id = ?", user.getId());
		if (!everyBranch) {
			long shopId = TenantContext.requireShopId();
			for (Long branchId : ids) {
				jdbc.update("insert into user_branch (shop_id, user_id, branch_id) values (?, ?, ?)", shopId, user.getId(),
						branchId);
			}
		}
	}

	private List<Long> branchIds(long userId) {
		return jdbc.queryForList("select branch_id from user_branch where user_id = ? order by branch_id", Long.class,
				userId);
	}

	private Map<Long, List<Long>> userBranches() {
		Map<Long, List<Long>> map = new LinkedHashMap<>();
		jdbc.query("select user_id, branch_id from user_branch order by branch_id",
				rs -> {
					map.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2));
				});
		return map;
	}

	private Map<Long, Role> roleMap() {
		Map<Long, Role> map = new LinkedHashMap<>();
		roles.findAll().forEach(r -> map.put(r.getId(), r));
		return map;
	}

	private UserView view(AppUser u, Map<Long, Role> roleById, Map<Long, List<Long>> branches) {
		Role role = roleById.get(u.getRoleId());
		Instant now = clock.instant();
		return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getPhone(), u.getEmail(), u.getRoleId(),
				role == null ? null : role.getName(), role == null ? null : role.getSystemKey(), u.isOwner(),
				u.isAllBranches(), u.isAllBranches() ? List.of() : branches.getOrDefault(u.getId(), List.of()),
				u.getStatus().name(), u.isLocked(now), u.getPinHash() != null, u.getTotpSecret() != null,
				u.isMustChangePassword(), u.getLastLoginAt(), u.getCreatedAt(), u.getVersion());
	}

	private AppUser find(long id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
	}

	static String username(String value) {
		String s = value == null ? "" : value.trim().toLowerCase();
		if (!s.matches("[a-z0-9._-]{3,60}")) {
			throw ApiException.field("username", "invalid", "Use 3-60 letters, numbers, dots, dashes or underscores");
		}
		return s;
	}

	private static String required(String value, String field, int max) {
		String s = value == null ? "" : value.trim();
		if (s.isEmpty()) {
			throw ApiException.field(field, "required", "This field is required");
		}
		if (s.length() > max) {
			throw ApiException.field(field, "too_long", "Use at most " + max + " characters");
		}
		return s;
	}

	private static String email(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String s = value.trim();
		if (s.length() > 254 || !s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw ApiException.field("email", "invalid", "Enter a valid email address");
		}
		return s;
	}

	private static String phone(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String s = value.trim();
		if (!s.matches("[+0-9 ()-]{7,30}")) {
			throw ApiException.field("phone", "invalid", "Enter a phone number like 0300-1234567");
		}
		return s;
	}
}
