package com.selectcode.pos.platform.app;

import com.selectcode.pos.catalog.app.TaxonomyService;
import com.selectcode.pos.catalog.app.TaxonomyService.UnitSeed;
import com.selectcode.pos.iam.app.UserAdminService;
import com.selectcode.pos.iam.app.UserAdminService.OwnerInput;
import com.selectcode.pos.iam.app.UserAdminService.OwnerSummary;
import com.selectcode.pos.iam.app.UserAdminService.SetupLink;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.ShopSetupService;
import com.selectcode.pos.shop.app.ShopSetupService.ShopInit;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shops for the super admin portal (PLT-07..15, BIL-04, ONB-01, ONB-04, ONB-07): the five-step creation wizard,
 * the shop list and profile, module switches, plan, suspension, closing and owner access.
 */
@Service
public class ShopAdminService {

	public static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	private static final Pattern CODE = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,28}[a-z0-9])?$");

	private static final Set<String> RESERVED = Set.of("admin", "www", "api", "app", "mail", "staging", "support",
			"help", "status", "platform", "static", "assets", "cdn", "dev", "test", "demo");

	/** All five wizard steps in one request. */
	public record NewShop(
			// 1. Business details
			String code, String name, String city, String address, String phone, String email, String ntn,
			// 2. Shop type and module adjustments
			String typeKey, List<String> extraModules, List<String> removedModules,
			// 3. Plan
			String planKey, String cycle, String price, String discount, String setupFee, Integer trialDays,
			LocalDate startDate,
			// 4. Starting data
			List<String> branches, String taxMode, String taxRate, String receiptLanguage, Boolean seedCategories,
			// 5. Owner login
			String ownerName, String ownerUsername, String ownerEmail, String ownerPhone, String notes) {
	}

	public record ShopCreated(long shopId, String code, String name, String status, SetupLink ownerSetup,
			List<String> modules) {
	}

	public record CodeCheck(String code, boolean available, String reason, String suggestion) {
	}

	public record ShopListItem(long id, String code, String name, String typeKey, String typeName, String planKey,
			String planName, String status, String city, String ownerName, String phone, LocalDate trialEndsOn,
			LocalDate nextDueOn, Instant createdAt) {
	}

	public record ShopQuery(String q, String typeKey, String planKey, String status, String city, int page, int size) {
	}

	public record Usage(long users, long branches, long products, long tills) {
	}

	public record Limits(Integer users, Integer branches, Integer products, Integer tills, int smsPerMonth,
			int storageMb) {
	}

	public record SubscriptionView(String planKey, String planName, String cycle, String price, String discount,
			String setupFee, LocalDate startsOn, LocalDate nextDueOn) {
	}

	public record BranchSummary(long id, String name, String code, String kind, boolean active) {
	}

	public record ShopProfile(long id, String code, String name, String typeKey, String typeName, String status,
			String statusReason, String ownerName, String phone, String email, String city, String address, String ntn,
			String notes, String timezone, LocalDate trialEndsOn, LocalDate deleteAfter, Instant createdAt,
			SubscriptionView subscription, Limits limits, Usage usage, List<ShopModuleService.ModuleState> modules,
			List<BranchSummary> branches, OwnerSummary owner, String shopUrl, long version) {
	}

	public record ShopUpdate(String name, String city, String address, String phone, String email, String ntn,
			String ownerName, String notes) {
	}

	public record SubscriptionUpdate(String planKey, String cycle, String price, String discount, LocalDate nextDueOn) {
	}

	private final JdbcTemplate jdbc;

	private final ShopModuleService modules;

	private final ShopSetupService shopSetup;

	private final TaxonomyService taxonomy;

	private final UserAdminService userAdmin;

	private final AuditService audit;

	private final JsonMapper json;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final com.selectcode.pos.shared.web.AppLinks links;

	ShopAdminService(JdbcTemplate jdbc, ShopModuleService modules, ShopSetupService shopSetup, TaxonomyService taxonomy,
			UserAdminService userAdmin, AuditService audit, JsonMapper json, PlatformTransactionManager transactions,
			Clock clock, com.selectcode.pos.shared.web.AppLinks links) {
		this.jdbc = jdbc;
		this.modules = modules;
		this.shopSetup = shopSetup;
		this.taxonomy = taxonomy;
		this.userAdmin = userAdmin;
		this.audit = audit;
		this.json = json;
		this.tx = new TransactionTemplate(transactions);
		this.clock = clock;
		this.links = links;
	}

	// ------------------------------------------------------------------ creation (PLT-08, PLT-09)

	public CodeCheck checkCode(String code, String name) {
		String clean = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
		String reason = codeProblem(clean);
		if (reason == null && taken(clean)) {
			reason = "Already used by another shop";
		}
		return new CodeCheck(clean, reason == null, reason, suggest(name != null && !name.isBlank() ? name : clean));
	}

	public ShopCreated create(NewShop in) {
		String code = in.code() == null ? "" : in.code().trim().toLowerCase(Locale.ROOT);
		String problem = codeProblem(code);
		if (problem != null) {
			throw ApiException.field("code", "invalid", problem);
		}
		if (taken(code)) {
			throw ApiException.conflict("code_taken", "Shop code " + code + " is already used. Try " + suggest(code));
		}
		String name = required(in.name(), "name", 200);
		String ownerName = required(in.ownerName(), "ownerName", 200);
		Map<String, Object> type = jdbc.queryForList("select * from shop_type where type_key = ? and active",
				in.typeKey() == null ? "" : in.typeKey().trim())
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.field("typeKey", "invalid", "Choose a shop type"));
		Map<String, Object> plan = jdbc.queryForList("select * from plan where plan_key = ? and active",
				in.planKey() == null ? "" : in.planKey().trim())
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.field("planKey", "invalid", "Choose a plan"));
		String cycle = in.cycle() == null || in.cycle().isBlank() ? "MONTH" : in.cycle().trim().toUpperCase();
		if (!Set.of("MONTH", "YEAR").contains(cycle)) {
			throw ApiException.field("cycle", "invalid", "Choose MONTH or YEAR");
		}
		BigDecimal listPrice = (BigDecimal) plan.get(cycle.equals("YEAR") ? "price_year" : "price_month");
		BigDecimal price = in.price() == null || in.price().isBlank() ? listPrice : Amounts.requireMoney(in.price(), "price");
		BigDecimal discount = Amounts.nz(Amounts.parseMoney(in.discount(), "discount"));
		if (discount.compareTo(price) > 0) {
			throw ApiException.field("discount", "too_large", "The discount cannot be more than the price");
		}
		BigDecimal setupFee = in.setupFee() == null || in.setupFee().isBlank() ? (BigDecimal) plan.get("setup_fee")
				: Amounts.requireMoney(in.setupFee(), "setupFee");
		int trialDays = in.trialDays() == null ? defaultTrialDays() : in.trialDays();
		if (trialDays < 0 || trialDays > 90) {
			throw ApiException.field("trialDays", "out_of_range", "Trial is 0 to 90 days");
		}
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		LocalDate start = in.startDate() == null ? today : in.startDate();
		LocalDate trialEnds = trialDays > 0 ? start.plusDays(trialDays) : null;
		String taxMode = in.taxMode() == null || in.taxMode().isBlank() ? "NONE" : in.taxMode().trim().toUpperCase();
		if (!Set.of("NONE", "INCLUSIVE", "EXCLUSIVE").contains(taxMode)) {
			throw ApiException.field("taxMode", "invalid", "Choose NONE, INCLUSIVE or EXCLUSIVE");
		}
		BigDecimal taxRate = Amounts.nz(Amounts.parsePercent(in.taxRate(), "taxRate"));
		String language = in.receiptLanguage() == null || in.receiptLanguage().isBlank() ? "EN"
				: in.receiptLanguage().trim().toUpperCase();
		if (!Set.of("EN", "UR", "BOTH").contains(language)) {
			throw ApiException.field("receiptLanguage", "invalid", "Choose EN, UR or BOTH");
		}
		validateModules(in.extraModules(), "extraModules");
		validateModules(in.removedModules(), "removedModules");
		String email = email(in.email(), "email");
		String ownerEmail = email(in.ownerEmail(), "ownerEmail");

		Long id = jdbc.queryForObject("select nextval(pg_get_serial_sequence('shop', 'id'))", Long.class);
		long typeId = ((Number) type.get("id")).longValue();
		long planId = ((Number) plan.get("id")).longValue();
		String status = trialDays > 0 ? "TRIAL" : "ACTIVE";
		return TenantContext.callAsShop(id, () -> tx.execute(t -> {
			jdbc.update("""
					insert into shop (id, code, name, shop_type_id, plan_id, status, owner_name, phone, email, city,
					                  address, ntn, notes, trial_ends_on, created_by)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, code, name, typeId, planId, status,
					ownerName, trim(in.phone(), 30), email, trim(in.city(), 100), trim(in.address(), 500),
					trim(in.ntn(), 30), in.notes(), trialEnds == null ? null : Date.valueOf(trialEnds),
					com.selectcode.pos.shared.security.CurrentActor.id());
			jdbc.update("""
					insert into subscription (shop_id, plan_id, cycle, price, discount, setup_fee, starts_on, next_due_on)
					values (?, ?, ?, ?, ?, ?, ?, ?)""", id, planId, cycle, price, discount, setupFee, Date.valueOf(start),
					Date.valueOf(trialEnds == null ? start : trialEnds));
			Set<ModuleKey> enabled = modules.initialise(id, in.extraModules(), in.removedModules());
			shopSetup.initialise(new ShopInit(name, trim(in.address(), 500), trim(in.phone(), 30), email,
					trim(in.ntn(), 30), taxMode, taxRate, language, in.branches()));
			JsonNode template = json.readTree(String.valueOf(type.get("template")));
			List<String> categories = new ArrayList<>();
			if (in.seedCategories() == null || in.seedCategories()) {
				template.path("categories").forEach(c -> categories.add(c.asString()));
			}
			List<UnitSeed> units = new ArrayList<>();
			template.path("units").forEach(u -> units.add(new UnitSeed(u.path("name").asString(), u.path("decimals").asBoolean())));
			taxonomy.seed(categories, units);
			SetupLink link = userAdmin.provisionShop(new OwnerInput(ownerName, in.ownerUsername(), ownerEmail,
					trim(in.ownerPhone(), 30)), enabled);
			audit.recordPlatform("SHOP_CREATED", id, Map.of("code", code, "name", name, "type", type.get("type_key"),
					"plan", plan.get("plan_key")));
			return new ShopCreated(id, code, name, status, link, enabled.stream().map(ModuleKey::key).sorted().toList());
		}));
	}

	// ------------------------------------------------------------------ list and profile (PLT-07, PLT-10)

	public PageResponse<ShopListItem> list(ShopQuery query) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (query.q() != null && !query.q().isBlank()) {
			String like = "%" + query.q().trim().toLowerCase() + "%";
			where.append(" and (lower(s.name) like ? or s.code like ? or coalesce(s.phone, '') like ? "
					+ "or lower(coalesce(s.owner_name, '')) like ?)");
			args.addAll(List.of(like, like, like, like));
		}
		if (query.typeKey() != null && !query.typeKey().isBlank()) {
			where.append(" and t.type_key = ?");
			args.add(query.typeKey().trim());
		}
		if (query.planKey() != null && !query.planKey().isBlank()) {
			where.append(" and p.plan_key = ?");
			args.add(query.planKey().trim());
		}
		if (query.status() != null && !query.status().isBlank()) {
			where.append(" and s.status = ?");
			args.add(query.status().trim().toUpperCase());
		}
		if (query.city() != null && !query.city().isBlank()) {
			where.append(" and lower(s.city) = ?");
			args.add(query.city().trim().toLowerCase());
		}
		String from = " from shop s join shop_type t on t.id = s.shop_type_id join plan p on p.id = s.plan_id"
				+ " left join subscription sub on sub.shop_id = s.id";
		Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
		args.add(query.size());
		args.add(query.page() * query.size());
		List<ShopListItem> items = jdbc.query("""
				select s.*, t.type_key, t.name as type_name, p.plan_key, p.name as plan_name, sub.next_due_on"""
				+ from + where + " order by s.created_at desc, s.id desc limit ? offset ?",
				(rs, i) -> new ShopListItem(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
						rs.getString("type_key"), rs.getString("type_name"), rs.getString("plan_key"),
						rs.getString("plan_name"), rs.getString("status"), rs.getString("city"),
						rs.getString("owner_name"), rs.getString("phone"), date(rs.getDate("trial_ends_on")),
						date(rs.getDate("next_due_on")), rs.getTimestamp("created_at").toInstant()),
				args.toArray());
		return PageResponse.of(items, query.page(), query.size(), total == null ? 0 : total);
	}

	public ShopProfile profile(long id) {
		Map<String, Object> s = shopRow(id);
		Map<String, Object> plan = jdbc.queryForMap("select * from plan where id = ?", s.get("plan_id"));
		SubscriptionView subscription = jdbc.query("""
				select sub.*, p.plan_key, p.name as plan_name from subscription sub join plan p on p.id = sub.plan_id
				where sub.shop_id = ?""", (rs, i) -> new SubscriptionView(rs.getString("plan_key"),
				rs.getString("plan_name"), rs.getString("cycle"), Amounts.text(rs.getBigDecimal("price")),
				Amounts.text(rs.getBigDecimal("discount")), Amounts.text(rs.getBigDecimal("setup_fee")),
				date(rs.getDate("starts_on")), date(rs.getDate("next_due_on"))), id).stream().findFirst().orElse(null);
		Limits limits = new Limits((Integer) plan.get("max_users"), (Integer) plan.get("max_branches"),
				(Integer) plan.get("max_products"), (Integer) plan.get("max_tills"), (Integer) plan.get("sms_per_month"),
				(Integer) plan.get("storage_mb"));
		record InShop(Usage usage, List<BranchSummary> branches, OwnerSummary owner) {
		}
		InShop inShop = TenantContext.callAsShop(id, () -> tx.execute(t -> new InShop(usage(),
				jdbc.query("select * from branch order by is_default desc, name", (rs, i) -> new BranchSummary(
						rs.getLong("id"), rs.getString("name"), rs.getString("code"), rs.getString("kind"),
						rs.getBoolean("active"))),
				userAdmin.owner())));
		String code = (String) s.get("code");
		return new ShopProfile(id, code, (String) s.get("name"), (String) s.get("type_key"),
				(String) s.get("type_name"), (String) s.get("status"), (String) s.get("status_reason"),
				(String) s.get("owner_name"), (String) s.get("phone"), (String) s.get("email"), (String) s.get("city"),
				(String) s.get("address"), (String) s.get("ntn"), (String) s.get("notes"), (String) s.get("timezone"),
				date((java.sql.Date) s.get("trial_ends_on")), date((java.sql.Date) s.get("delete_after")),
				((java.sql.Timestamp) s.get("created_at")).toInstant(), subscription, limits, inShop.usage(),
				modules.states(id), inShop.branches(), inShop.owner(), links.shop(code, "/"),
				((Number) s.get("version")).longValue());
	}

	/** Counts used against plan limits; must run with the shop in context. */
	public Usage usage() {
		return new Usage(count("select count(*) from app_user where status = 'ACTIVE'"),
				count("select count(*) from branch where active"), count("select count(*) from product where active"),
				count("select count(*) from till where active"));
	}

	// ------------------------------------------------------------------ changes

	public ShopProfile update(long id, ShopUpdate in) {
		tx.executeWithoutResult(t -> {
			Map<String, Object> s = shopRow(id);
			Map<String, Object> changes = new LinkedHashMap<>();
			put(changes, "name", in.name() == null ? null : required(in.name(), "name", 200));
			put(changes, "city", in.city() == null ? null : trim(in.city(), 100));
			put(changes, "address", in.address() == null ? null : trim(in.address(), 500));
			put(changes, "phone", in.phone() == null ? null : trim(in.phone(), 30));
			put(changes, "email", in.email() == null ? null : email(in.email(), "email"));
			put(changes, "ntn", in.ntn() == null ? null : trim(in.ntn(), 30));
			put(changes, "owner_name", in.ownerName() == null ? null : required(in.ownerName(), "ownerName", 200));
			put(changes, "notes", in.notes());
			if (changes.isEmpty()) {
				return;
			}
			StringBuilder sql = new StringBuilder("update shop set ");
			List<Object> args = new ArrayList<>();
			changes.forEach((k, v) -> {
				sql.append(k).append(" = ?, ");
				args.add(v);
			});
			sql.append("updated_at = now(), version = version + 1 where id = ?");
			args.add(id);
			jdbc.update(sql.toString(), args.toArray());
			audit.recordPlatform("SHOP_UPDATED", id, Map.of("fields", changes.keySet(), "code", s.get("code")));
		});
		return profile(id);
	}

	public ShopProfile setModules(long id, Map<String, Boolean> changes) {
		if (changes == null || changes.isEmpty()) {
			throw ApiException.rule("no_changes", "Choose modules to switch on or off");
		}
		validateModules(List.copyOf(changes.keySet()), "modules");
		shopRow(id);
		Set<ModuleKey> enabled = tx.execute(t -> {
			Set<ModuleKey> now = modules.set(id, changes);
			audit.recordPlatform("SHOP_MODULES_CHANGED", id, changes);
			return now;
		});
		// The shop must be in context before the transaction starts (RLS), so roles are updated in their own step.
		TenantContext.runAsShop(id, () -> tx.executeWithoutResult(t -> userAdmin.ensureSystemRoles(enabled)));
		return profile(id);
	}

	public ShopProfile changeSubscription(long id, SubscriptionUpdate in) {
		tx.executeWithoutResult(t -> {
			shopRow(id);
			Map<String, Object> current = jdbc.queryForMap("select * from subscription where shop_id = ?", id);
			long planId = ((Number) current.get("plan_id")).longValue();
			Map<String, Object> plan = jdbc.queryForMap("select * from plan where id = ?", planId);
			if (in.planKey() != null) {
				plan = jdbc.queryForList("select * from plan where plan_key = ? and active", in.planKey().trim())
					.stream()
					.findFirst()
					.orElseThrow(() -> ApiException.field("planKey", "invalid", "Choose a plan"));
				planId = ((Number) plan.get("id")).longValue();
			}
			String cycle = in.cycle() == null ? (String) current.get("cycle") : in.cycle().trim().toUpperCase();
			if (!Set.of("MONTH", "YEAR").contains(cycle)) {
				throw ApiException.field("cycle", "invalid", "Choose MONTH or YEAR");
			}
			BigDecimal price = in.price() != null ? Amounts.requireMoney(in.price(), "price")
					: (in.planKey() != null || in.cycle() != null)
							? (BigDecimal) plan.get(cycle.equals("YEAR") ? "price_year" : "price_month")
							: (BigDecimal) current.get("price");
			BigDecimal discount = in.discount() == null ? (BigDecimal) current.get("discount")
					: Amounts.nz(Amounts.parseMoney(in.discount(), "discount"));
			LocalDate nextDue = in.nextDueOn() == null ? ((java.sql.Date) current.get("next_due_on")).toLocalDate()
					: in.nextDueOn();
			jdbc.update("""
					update subscription set plan_id = ?, cycle = ?, price = ?, discount = ?, next_due_on = ?, updated_at = now()
					where shop_id = ?""", planId, cycle, price, discount, Date.valueOf(nextDue), id);
			jdbc.update("update shop set plan_id = ?, updated_at = now() where id = ?", planId, id);
			modules.applyPlan(id);
			audit.recordPlatform("SUBSCRIPTION_CHANGED", id, Map.of("plan", plan.get("plan_key"), "cycle", cycle,
					"price", price.toPlainString(), "nextDueOn", nextDue.toString()));
		});
		Set<ModuleKey> enabled = modules.enabledFor(id);
		TenantContext.runAsShop(id, () -> tx.executeWithoutResult(t -> userAdmin.ensureSystemRoles(enabled)));
		return profile(id);
	}

	/** PLT-14: users are signed out at once and the login page shows "contact support". */
	public ShopProfile suspend(long id, String reason) {
		String why = required(reason, "reason", 500);
		tx.executeWithoutResult(t -> {
			Map<String, Object> s = shopRow(id);
			if ("CLOSED".equals(s.get("status"))) {
				throw ApiException.rule("shop_closed", "This shop is closed");
			}
			jdbc.update("update shop set status = 'SUSPENDED', status_reason = ?, updated_at = now() where id = ?", why, id);
			audit.recordPlatform("SHOP_SUSPENDED", id, Map.of("reason", why));
		});
		TenantContext.runAsShop(id, () -> tx.executeWithoutResult(t -> userAdmin.revokeAllSessions()));
		return profile(id);
	}

	/** Back to Trial (if the trial has not ended) or Active. Also reopens a closed shop before it is deleted. */
	public ShopProfile reactivate(long id) {
		tx.executeWithoutResult(t -> {
			Map<String, Object> s = shopRow(id);
			java.sql.Date trialEnds = (java.sql.Date) s.get("trial_ends_on");
			String status = trialEnds != null && !trialEnds.toLocalDate().isBefore(LocalDate.now(clock.withZone(ZONE)))
					? "TRIAL" : "ACTIVE";
			jdbc.update("""
					update shop set status = ?, status_reason = null, closed_at = null, delete_after = null,
					updated_at = now() where id = ?""", status, id);
			audit.recordPlatform("SHOP_REACTIVATED", id, Map.of("status", status));
		});
		return profile(id);
	}

	/** PLT-15: the shop code must be retyped; data is kept for 90 days, then deleted (Stage 3 job). */
	public ShopProfile close(long id, String confirmCode, String reason) {
		String why = required(reason, "reason", 500);
		tx.executeWithoutResult(t -> {
			Map<String, Object> s = shopRow(id);
			if (confirmCode == null || !confirmCode.trim().equalsIgnoreCase((String) s.get("code"))) {
				throw ApiException.field("confirmCode", "mismatch", "Type the shop code exactly to confirm");
			}
			LocalDate deleteAfter = LocalDate.now(clock.withZone(ZONE)).plusDays(90);
			jdbc.update("""
					update shop set status = 'CLOSED', status_reason = ?, closed_at = now(), delete_after = ?,
					updated_at = now() where id = ?""", why, Date.valueOf(deleteAfter), id);
			audit.recordPlatform("SHOP_CLOSED", id, Map.of("reason", why, "deleteAfter", deleteAfter.toString()));
		});
		TenantContext.runAsShop(id, () -> tx.executeWithoutResult(t -> userAdmin.revokeAllSessions()));
		return profile(id);
	}

	// ------------------------------------------------------------------ owner access (PLT-13)

	public SetupLink resendOwnerSetup(long id) {
		shopRow(id);
		SetupLink link = TenantContext.callAsShop(id, () -> tx.execute(t -> userAdmin.issueOwnerSetupLink()));
		audit.recordPlatform("OWNER_SETUP_LINK_SENT", id, Map.of("emailed", link.emailed()));
		return link;
	}

	public OwnerSummary changeOwner(long id, long newOwnerUserId, String reason) {
		String why = required(reason, "reason", 500);
		shopRow(id);
		OwnerSummary owner = TenantContext.callAsShop(id, () -> tx.execute(t -> userAdmin.changeOwner(newOwnerUserId)));
		audit.recordPlatform("OWNER_CHANGED", id, Map.of("userId", newOwnerUserId, "reason", why));
		return owner;
	}

	// ------------------------------------------------------------------ helpers

	Map<String, Object> shopRow(long id) {
		return jdbc.queryForList("""
				select s.*, t.type_key, t.name as type_name from shop s join shop_type t on t.id = s.shop_type_id
				where s.id = ?""", id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Shop"));
	}

	private void validateModules(List<String> keys, String field) {
		if (keys == null) {
			return;
		}
		for (String key : keys) {
			if (ModuleKey.of(key).isEmpty()) {
				throw ApiException.field(field, "invalid", "Unknown module: " + key);
			}
		}
	}

	private int defaultTrialDays() {
		List<String> v = jdbc.queryForList("select value from platform_setting where setting_key = 'trial_days'",
				String.class);
		try {
			return v.isEmpty() ? 14 : Integer.parseInt(v.getFirst().trim());
		}
		catch (NumberFormatException e) {
			return 14;
		}
	}

	private boolean taken(String code) {
		Integer n = jdbc.queryForObject("select count(*) from shop where code = ?", Integer.class, code);
		return n != null && n > 0;
	}

	static String codeProblem(String code) {
		if (code.length() < 2 || code.length() > 30) {
			return "Use 2 to 30 characters";
		}
		if (!CODE.matcher(code).matches()) {
			return "Use small letters, numbers and dashes (not at the start or end)";
		}
		if (RESERVED.contains(code)) {
			return "This code is reserved";
		}
		return null;
	}

	/** A free code based on the shop name, e.g. "Ali Mobiles" → "alimobiles", "alimobiles2"... */
	String suggest(String name) {
		String base = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		if (base.length() < 2) {
			base = "shop" + base;
		}
		if (base.length() > 26) {
			base = base.substring(0, 26);
		}
		String candidate = base;
		int n = 2;
		while (codeProblem(candidate) != null || taken(candidate)) {
			candidate = base + n++;
		}
		return candidate;
	}

	private long count(String sql) {
		Long n = jdbc.queryForObject(sql, Long.class);
		return n == null ? 0 : n;
	}

	private static void put(Map<String, Object> changes, String column, Object value) {
		if (value != null) {
			changes.put(column, value instanceof String s && s.isEmpty() ? null : value);
		}
	}

	private static LocalDate date(java.sql.Date d) {
		return d == null ? null : d.toLocalDate();
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

	private static String trim(String v, int max) {
		if (v == null || v.isBlank()) {
			return null;
		}
		String s = v.trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static String email(String value, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String s = value.trim();
		if (s.length() > 254 || !s.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw ApiException.field(field, "invalid", "Enter a valid email address");
		}
		return s;
	}
}
