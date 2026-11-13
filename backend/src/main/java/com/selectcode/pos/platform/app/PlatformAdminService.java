package com.selectcode.pos.platform.app;

import com.selectcode.pos.platform.domain.PlatformUser;
import com.selectcode.pos.platform.infra.PlatformUserRepository;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform overview (PLT-04), shop types and plans (PLT-19, BIL-01), platform users (PLT-23), platform settings
 * (PLT-24) and the platform audit log (PLT-27).
 */
@Service
public class PlatformAdminService {

	public record Count(String key, String label, long count) {
	}

	public record ExpiringShop(long id, String code, String name, String status, LocalDate date, String kind) {
	}

	public record Overview(long totalShops, List<Count> byStatus, List<Count> byType, List<Count> byCity,
			String monthlyRecurringRevenue, long overdueShops, String overdueAmount, List<ExpiringShop> expiringSoon,
			long newShopsThisMonth) {
	}

	public record ShopTypeView(long id, String key, String name, String description, String icon,
			List<String> defaultModules, List<String> offeredModules, String template, int sortOrder, boolean active) {
	}

	public record PlanView(long id, String key, String name, String priceMonth, String priceYear, String setupFee,
			Integer maxUsers, Integer maxBranches, Integer maxProducts, Integer maxTills, int smsPerMonth, int storageMb,
			boolean phase1ModulesOnly, boolean customDomain, boolean active) {
	}

	public record PlatformUserView(long id, String email, String name, String role, String status, boolean totpEnrolled,
			Instant lastLoginAt, Instant createdAt) {
	}

	public record PlatformUserInput(String email, String name, String role, String status) {
	}

	public record PlatformUserCreated(PlatformUserView user, String temporaryPassword) {
	}

	public record PlatformAuditEntry(long id, String actorName, String action, Long shopId, String shopCode,
			String details, String ip, Instant at) {
	}

	private static final Set<String> SETTINGS = Set.of("company_name", "product_name", "support_phone", "support_email",
			"trial_days", "grace_days", "setup_link_hours", "terms_version");

	private final JdbcTemplate jdbc;

	private final PlatformUserRepository users;

	private final PasswordEncoder encoder;

	private final AuditService audit;

	private final Clock clock;

	PlatformAdminService(JdbcTemplate jdbc, PlatformUserRepository users, PasswordEncoder encoder, AuditService audit,
			Clock clock) {
		this.jdbc = jdbc;
		this.users = users;
		this.encoder = encoder;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ overview

	@Transactional(readOnly = true)
	public Overview overview() {
		LocalDate today = LocalDate.now(clock.withZone(ShopAdminService.ZONE));
		Long total = jdbc.queryForObject("select count(*) from shop", Long.class);
		List<Count> byStatus = jdbc.query("select status, count(*) from shop group by status order by status",
				(rs, i) -> new Count(rs.getString(1), label(rs.getString(1)), rs.getLong(2)));
		List<Count> byType = jdbc.query("""
				select t.type_key, t.name, count(s.id) from shop_type t left join shop s on s.shop_type_id = t.id
				group by t.type_key, t.name, t.sort_order order by t.sort_order""",
				(rs, i) -> new Count(rs.getString(1), rs.getString(2), rs.getLong(3)));
		List<Count> byCity = jdbc.query("""
				select coalesce(nullif(trim(city), ''), 'Not set') as city, count(*) from shop
				where status <> 'CLOSED' group by 1 order by 2 desc, 1 limit 10""",
				(rs, i) -> new Count(rs.getString(1), rs.getString(1), rs.getLong(2)));
		BigDecimal mrr = jdbc.queryForObject("""
				select coalesce(sum(case when sub.cycle = 'YEAR' then (sub.price - sub.discount) / 12
				                         else sub.price - sub.discount end), 0)
				from subscription sub join shop s on s.id = sub.shop_id where s.status in ('ACTIVE', 'GRACE')""",
				BigDecimal.class);
		Map<String, Object> overdue = jdbc.queryForMap("""
				select count(*) as n, coalesce(sum(sub.price - sub.discount), 0) as amount
				from subscription sub join shop s on s.id = sub.shop_id
				where sub.next_due_on < ? and s.status in ('ACTIVE', 'GRACE', 'READ_ONLY')""",
				java.sql.Date.valueOf(today));
		List<ExpiringShop> expiring = new ArrayList<>(jdbc.query("""
				select id, code, name, status, trial_ends_on from shop
				where status = 'TRIAL' and trial_ends_on between ? and ? order by trial_ends_on""",
				(rs, i) -> new ExpiringShop(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getDate(5).toLocalDate(), "TRIAL_ENDS"),
				java.sql.Date.valueOf(today), java.sql.Date.valueOf(today.plusDays(7))));
		expiring.addAll(jdbc.query("""
				select s.id, s.code, s.name, s.status, sub.next_due_on from shop s join subscription sub on sub.shop_id = s.id
				where s.status in ('ACTIVE', 'GRACE') and sub.next_due_on between ? and ? order by sub.next_due_on""",
				(rs, i) -> new ExpiringShop(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getDate(5).toLocalDate(), "PAYMENT_DUE"),
				java.sql.Date.valueOf(today), java.sql.Date.valueOf(today.plusDays(7))));
		Long newShops = jdbc.queryForObject("select count(*) from shop where created_at >= ?", Long.class,
				java.sql.Timestamp.from(today.withDayOfMonth(1).atStartOfDay(ShopAdminService.ZONE).toInstant()));
		return new Overview(total == null ? 0 : total, byStatus, byType, byCity,
				Amounts.text(mrr.setScale(2, RoundingMode.HALF_UP)), ((Number) overdue.get("n")).longValue(),
				Amounts.text((BigDecimal) overdue.get("amount")), expiring, newShops == null ? 0 : newShops);
	}

	// ------------------------------------------------------------------ types and plans

	@Transactional(readOnly = true)
	public List<ShopTypeView> shopTypes() {
		return jdbc.query("select * from shop_type order by sort_order", (rs, i) -> new ShopTypeView(rs.getLong("id"),
				rs.getString("type_key"), rs.getString("name"), rs.getString("description"), rs.getString("icon"),
				list(rs.getArray("default_modules")), list(rs.getArray("offered_modules")), rs.getString("template"),
				rs.getInt("sort_order"), rs.getBoolean("active")));
	}

	@Transactional(readOnly = true)
	public List<PlanView> plans() {
		return jdbc.query("select * from plan order by sort_order", (rs, i) -> new PlanView(rs.getLong("id"),
				rs.getString("plan_key"), rs.getString("name"), Amounts.text(rs.getBigDecimal("price_month")),
				Amounts.text(rs.getBigDecimal("price_year")), Amounts.text(rs.getBigDecimal("setup_fee")),
				(Integer) rs.getObject("max_users"), (Integer) rs.getObject("max_branches"),
				(Integer) rs.getObject("max_products"), (Integer) rs.getObject("max_tills"), rs.getInt("sms_per_month"),
				rs.getInt("storage_mb"), rs.getBoolean("phase1_modules_only"), rs.getBoolean("custom_domain"),
				rs.getBoolean("active")));
	}

	// ------------------------------------------------------------------ platform users

	@Transactional(readOnly = true)
	public List<PlatformUserView> platformUsers() {
		return users.findAllByOrderByNameAsc().stream().map(PlatformAdminService::view).toList();
	}

	@Transactional
	public PlatformUserCreated createPlatformUser(PlatformUserInput in) {
		String email = in.email() == null ? "" : in.email().trim();
		if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length() > 254) {
			throw ApiException.field("email", "invalid", "Enter a valid email address");
		}
		if (users.findByEmailIgnoreCase(email).isPresent()) {
			throw ApiException.conflict("duplicate_email", "A platform user with this email already exists");
		}
		String name = in.name() == null ? "" : in.name().trim();
		if (name.isEmpty() || name.length() > 200) {
			throw ApiException.field("name", "required", "Enter a name");
		}
		String temporary = temporaryPassword();
		PlatformUser user = users.save(new PlatformUser(email, name, role(in.role()), encoder.encode(temporary)));
		audit.recordPlatform("PLATFORM_USER_CREATED", null, Map.of("email", email, "role", user.getRole().name()));
		return new PlatformUserCreated(view(user), temporary);
	}

	@Transactional
	public PlatformUserView updatePlatformUser(long id, PlatformUserInput in) {
		PlatformUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("Platform user"));
		boolean self = CurrentActor.id() != null && CurrentActor.id() == id;
		Instant now = clock.instant();
		if (in.name() != null && !in.name().isBlank()) {
			user.rename(in.name().trim(), now);
		}
		if (in.role() != null) {
			if (self && role(in.role()) != user.getRole()) {
				throw ApiException.rule("self", "You cannot change your own role");
			}
			user.setRole(role(in.role()), now);
		}
		if (in.status() != null) {
			PlatformUser.Status status;
			try {
				status = PlatformUser.Status.valueOf(in.status().trim().toUpperCase());
			}
			catch (IllegalArgumentException e) {
				throw ApiException.field("status", "invalid", "Choose ACTIVE or INACTIVE");
			}
			if (self && status == PlatformUser.Status.INACTIVE) {
				throw ApiException.rule("self", "You cannot deactivate yourself");
			}
			user.setStatus(status, now);
		}
		audit.recordPlatform("PLATFORM_USER_UPDATED", null, Map.of("email", user.getEmail(), "role",
				user.getRole().name(), "status", user.getStatus().name()));
		return view(user);
	}

	/** New temporary password; the authenticator app must be enrolled again at next sign-in. */
	@Transactional
	public PlatformUserCreated resetPlatformUser(long id) {
		PlatformUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("Platform user"));
		String temporary = temporaryPassword();
		user.resetCredentials(encoder.encode(temporary), clock.instant());
		audit.recordPlatform("PLATFORM_USER_RESET", null, Map.of("email", user.getEmail()));
		return new PlatformUserCreated(view(user), temporary);
	}

	// ------------------------------------------------------------------ settings and audit

	@Transactional(readOnly = true)
	public Map<String, String> settings() {
		Map<String, String> map = new LinkedHashMap<>();
		jdbc.query("select setting_key, value from platform_setting order by setting_key", rs -> {
			map.put(rs.getString(1), rs.getString(2));
		});
		return map;
	}

	@Transactional
	public Map<String, String> updateSettings(Map<String, String> changes) {
		for (Map.Entry<String, String> change : changes.entrySet()) {
			if (!SETTINGS.contains(change.getKey())) {
				throw ApiException.field(change.getKey(), "unknown", "Unknown setting");
			}
			String value = change.getValue() == null ? "" : change.getValue().trim();
			if (Set.of("trial_days", "grace_days", "setup_link_hours").contains(change.getKey())
					&& !value.matches("\\d{1,3}")) {
				throw ApiException.field(change.getKey(), "invalid", "Enter a whole number");
			}
			if (value.length() > 500) {
				throw ApiException.field(change.getKey(), "too_long", "Use at most 500 characters");
			}
			jdbc.update("""
					insert into platform_setting (setting_key, value, updated_at) values (?, ?, now())
					on conflict (setting_key) do update set value = excluded.value, updated_at = now()""",
					change.getKey(), value);
		}
		audit.recordPlatform("PLATFORM_SETTINGS_CHANGED", null, changes.keySet());
		return settings();
	}

	@Transactional(readOnly = true)
	public PageResponse<PlatformAuditEntry> audit(Long shopId, String action, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (shopId != null) {
			where.append(" and a.shop_id = ?");
			args.add(shopId);
		}
		if (action != null && !action.isBlank()) {
			where.append(" and a.action = ?");
			args.add(action.trim().toUpperCase());
		}
		Long total = jdbc.queryForObject("select count(*) from platform_audit_log a" + where, Long.class, args.toArray());
		args.add(size);
		args.add(page * size);
		List<PlatformAuditEntry> items = jdbc.query("""
				select a.*, u.name as actor_name, s.code as shop_code from platform_audit_log a
				left join platform_user u on u.id = a.actor_id left join shop s on s.id = a.shop_id"""
				+ where + " order by a.at desc, a.id desc limit ? offset ?",
				(rs, i) -> new PlatformAuditEntry(rs.getLong("id"), rs.getString("actor_name"), rs.getString("action"),
						(Long) rs.getObject("shop_id"), rs.getString("shop_code"), rs.getString("details"),
						rs.getString("ip"), rs.getTimestamp("at").toInstant()),
				args.toArray());
		return PageResponse.of(items, page, size, total == null ? 0 : total);
	}

	// ------------------------------------------------------------------ helpers

	private static PlatformUserView view(PlatformUser u) {
		return new PlatformUserView(u.getId(), u.getEmail(), u.getName(), u.getRole().name(), u.getStatus().name(),
				u.getTotpSecret() != null, u.getLastLoginAt(), u.getCreatedAt());
	}

	private static PlatformUser.Role role(String value) {
		try {
			return value == null || value.isBlank() ? PlatformUser.Role.SUPPORT
					: PlatformUser.Role.valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw ApiException.field("role", "invalid", "Choose SUPER_ADMIN, SUPPORT, BILLING or ONBOARDING");
		}
	}

	private static String temporaryPassword() {
		String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
		java.security.SecureRandom random = new java.security.SecureRandom();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < 16; i++) {
			out.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return out.toString();
	}

	private static List<String> list(Array array) throws java.sql.SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

	private static String label(String status) {
		return switch (status) {
			case "TRIAL" -> "Trial";
			case "ACTIVE" -> "Active";
			case "GRACE" -> "Grace";
			case "READ_ONLY" -> "Read-only";
			case "SUSPENDED" -> "Suspended";
			case "CLOSED" -> "Closed";
			default -> status;
		};
	}
}
