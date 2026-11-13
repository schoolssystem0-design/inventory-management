package com.selectcode.pos.platform.app;

import com.selectcode.pos.platform.PlatformPrincipal;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shop.app.SettingsService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the app needs after sign-in, in one call (docs/architecture/API-CONVENTIONS.md, GET /shop/context): the shop, the user,
 * permissions, enabled modules, the branches the user may use, plan limits and usage, key settings and the banner
 * (trial ending, overdue, read-only, support session).
 */
@Service
public class ShopContextService {

	public record ShopInfo(long id, String code, String name, String typeKey, String typeName, String status,
			String statusReason, LocalDate trialEndsOn, String timezone, Long logoFileId) {
	}

	public record UserInfo(long id, String actorType, String username, String displayName, boolean owner,
			String roleName, boolean allBranches, boolean pinSet, boolean totpEnabled, boolean mustChangePassword) {
	}

	public record BranchInfo(long id, String name, String code, String kind, boolean isDefault) {
	}

	public record ModuleInfo(String key, String label) {
	}

	public record PlanInfo(String key, String name, ShopAdminService.Limits limits, ShopAdminService.Usage usage) {
	}

	public record SettingsInfo(String businessName, String currency, String taxMode, String taxRate, String receiptSize,
			String receiptLanguage, int sessionIdleMinutes, boolean allowNegativeStock, String discountLimitPct) {
	}

	public record SupportInfo(long sessionId, String staffName, boolean writeApproved) {
	}

	/** {@code kind}: TRIAL, GRACE, READ_ONLY, PAYMENT_DUE or SUPPORT. */
	public record Banner(String kind, String message, Integer daysLeft) {
	}

	public record ShopContext(ShopInfo shop, UserInfo user, List<String> permissions, List<ModuleInfo> modules,
			List<BranchInfo> branches, PlanInfo plan, SettingsInfo settings, SupportInfo support, List<Banner> banners) {
	}

	private final JdbcTemplate jdbc;

	private final ShopModuleService modules;

	private final SettingsService settings;

	private final ShopAdminService shops;

	private final java.time.Clock clock;

	private final com.selectcode.pos.iam.app.UserAdminService userAdmin;

	ShopContextService(JdbcTemplate jdbc, ShopModuleService modules, SettingsService settings, ShopAdminService shops,
			java.time.Clock clock, com.selectcode.pos.iam.app.UserAdminService userAdmin) {
		this.userAdmin = userAdmin;
		this.jdbc = jdbc;
		this.modules = modules;
		this.settings = settings;
		this.shops = shops;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ShopContext context() {
		ActorPrincipal actor = CurrentActor.require();
		long shopId = TenantContext.requireShopId();
		Map<String, Object> shop = jdbc.queryForMap("""
				select s.*, t.type_key, t.name as type_name, p.plan_key, p.name as plan_name, p.max_users, p.max_branches,
				       p.max_products, p.max_tills, p.sms_per_month, p.storage_mb, sub.next_due_on, st.logo_file_id
				from shop s join shop_type t on t.id = s.shop_type_id join plan p on p.id = s.plan_id
				left join subscription sub on sub.shop_id = s.id left join shop_settings st on st.shop_id = s.id
				where s.id = ?""", shopId);
		ShopInfo shopInfo = new ShopInfo(shopId, (String) shop.get("code"), (String) shop.get("name"),
				(String) shop.get("type_key"), (String) shop.get("type_name"), (String) shop.get("status"),
				(String) shop.get("status_reason"), date(shop.get("trial_ends_on")), (String) shop.get("timezone"),
				(Long) shop.get("logo_file_id"));

		List<String> permissions = SecurityContextHolder.getContext()
			.getAuthentication()
			.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(Permissions::exists)
			.sorted()
			.toList();

		UserInfo user;
		List<BranchInfo> branches;
		String branchSql = "select * from branch where active order by is_default desc, name";
		if (actor.actorType() == ActorType.USER) {
			Map<String, Object> u = jdbc.queryForList("""
					select u.*, r.name as role_name from app_user u join role r on r.id = u.role_id where u.id = ?""",
					actor.actorId()).stream().findFirst().orElseThrow(() -> ApiException.notFound("User"));
			boolean all = Boolean.TRUE.equals(u.get("all_branches")) || Boolean.TRUE.equals(u.get("is_owner"));
			user = new UserInfo(actor.actorId(), "USER", (String) u.get("username"), (String) u.get("display_name"),
					Boolean.TRUE.equals(u.get("is_owner")), (String) u.get("role_name"), all, u.get("pin_hash") != null,
					u.get("totp_secret") != null, Boolean.TRUE.equals(u.get("must_change_password")));
			branches = jdbc.query(all ? branchSql
					: "select b.* from branch b join user_branch ub on ub.branch_id = b.id where b.active and ub.user_id = "
							+ actor.actorId() + " order by b.is_default desc, b.name", (rs, i) -> branch(rs));
		}
		else {
			user = new UserInfo(actor.actorId(), "PLATFORM", actor.loginName(), actor.displayName(), false,
					"SelectCode support", true, false, false, false);
			branches = jdbc.query(branchSql, (rs, i) -> branch(rs));
		}

		List<ModuleInfo> moduleInfo = modules.enabledFor(shopId)
			.stream()
			.sorted()
			.map(m -> new ModuleInfo(m.key(), m.label()))
			.toList();

		PlanInfo plan = new PlanInfo((String) shop.get("plan_key"), (String) shop.get("plan_name"),
				new ShopAdminService.Limits((Integer) shop.get("max_users"), (Integer) shop.get("max_branches"),
						(Integer) shop.get("max_products"), (Integer) shop.get("max_tills"),
						(Integer) shop.get("sms_per_month"), (Integer) shop.get("storage_mb")),
				shops.usage());

		SettingsService.SettingsView s = settings.get();
		SettingsInfo settingsInfo = new SettingsInfo(s.businessName(), "PKR", s.taxMode(), s.taxRate(), s.receiptSize(),
				s.receiptLanguage(), s.sessionIdleMinutes(), s.allowNegativeStock(), s.discountLimitPct());

		SupportInfo support = null;
		java.util.List<Banner> banners = new java.util.ArrayList<>();
		if (actor instanceof PlatformPrincipal platform && platform.supportSessionId() != null) {
			Boolean approved = jdbc.queryForObject(
					"select write_approved_at is not null from support_session where id = ?", Boolean.class,
					platform.supportSessionId());
			support = new SupportInfo(platform.supportSessionId(), platform.name(), Boolean.TRUE.equals(approved));
		}
		Long openSupport = jdbc.queryForObject(
				"select count(*) from support_session where shop_id = ? and ended_at is null", Long.class, shopId);
		if (openSupport != null && openSupport > 0) {
			banners.add(new Banner("SUPPORT", "SelectCode support is viewing this shop", null));
		}
		LocalDate today = LocalDate.now(clock.withZone(ShopAdminService.ZONE));
		String status = shopInfo.status();
		if ("TRIAL".equals(status) && shopInfo.trialEndsOn() != null) {
			long days = ChronoUnit.DAYS.between(today, shopInfo.trialEndsOn());
			banners.add(new Banner("TRIAL", "Free trial: " + days(Math.max(days, 0)) + " left", (int) Math.max(days, 0)));
		}
		if ("GRACE".equals(status)) {
			banners.add(new Banner("GRACE", "Your subscription payment is overdue. Please pay to avoid read-only mode.",
					null));
		}
		if ("READ_ONLY".equals(status)) {
			banners.add(new Banner("READ_ONLY",
					"The subscription is overdue, so the shop is read-only. You can view, print and export.", null));
		}
		LocalDate due = date(shop.get("next_due_on"));
		if ("ACTIVE".equals(status) && due != null && !due.isAfter(today.plusDays(7))) {
			long days = ChronoUnit.DAYS.between(today, due);
			banners.add(new Banner("PAYMENT_DUE",
					days >= 0 ? (days == 0 ? "Subscription payment is due today" : "Subscription payment due in " + days(days)) : "Subscription payment is overdue",
					(int) days));
		}
		return new ShopContext(shopInfo, user, permissions, moduleInfo, branches, plan, settingsInfo, support, banners);
	}

	/** The shop owner's view of modules: what the type offers and what they may switch themselves. */
	@Transactional(readOnly = true)
	public List<ShopModuleService.ModuleState> ownerModules() {
		long shopId = TenantContext.requireShopId();
		return modules.states(shopId).stream().filter(m -> m.standard() || m.offered() || m.enabled()).toList();
	}

	@Transactional
	public List<ShopModuleService.ModuleState> ownerToggle(String key, boolean enabled) {
		long shopId = TenantContext.requireShopId();
		ModuleKey module = ModuleKey.of(key).orElseThrow(() -> ApiException.notFound("Module"));
		ShopModuleService.ModuleState state = modules.states(shopId)
			.stream()
			.filter(m -> m.key().equals(key))
			.findFirst()
			.orElseThrow();
		if (!(state.standard() || state.offered()) || !module.ownerCanToggle()) {
			throw ApiException.forbidden("module_locked",
					"\"" + module.label() + "\" can only be changed by SelectCode support");
		}
		if (enabled && !state.allowedByPlan()) {
			throw new ApiException(org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "plan_limit",
					"\"" + module.label() + "\" is not included in your plan. Upgrade to use it.",
					Map.of("limit", "modules"));
		}
		var now = modules.set(shopId, Map.of(key, enabled));
		userAdmin.ensureSystemRoles(now);
		return ownerModules();
	}

	private static BranchInfo branch(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new BranchInfo(rs.getLong("id"), rs.getString("name"), rs.getString("code"), rs.getString("kind"),
				rs.getBoolean("is_default"));
	}

	private static LocalDate date(Object value) {
		return value instanceof java.sql.Date d ? d.toLocalDate() : null;
	}

	private static String days(long n) {
		return n == 1 ? "1 day" : n + " days";
	}

}
