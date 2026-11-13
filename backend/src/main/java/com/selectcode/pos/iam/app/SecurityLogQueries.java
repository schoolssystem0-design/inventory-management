package com.selectcode.pos.iam.app;

import com.selectcode.pos.iam.domain.Role;
import com.selectcode.pos.iam.infra.RoleRepository;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.PageResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login history (ACC-06), activity history (ACC-07) and the role list with the permission catalogue. */
@Service
public class SecurityLogQueries {

	public record LoginEventView(long id, Long userId, String username, String result, String ip, String userAgent,
			Instant at) {
	}

	public record AuditEntry(long id, String actorType, String actorName, boolean support, String action,
			String entityType, Long entityId, String before, String after, String reason, Instant at) {
	}

	public record PermissionView(String code, String label) {
	}

	public record PermissionGroup(String group, List<PermissionView> permissions) {
	}

	public record RoleView(long id, String name, String systemKey, String description, List<String> permissions,
			long activeUsers) {
	}

	private final JdbcTemplate jdbc;

	private final RoleRepository roles;

	SecurityLogQueries(JdbcTemplate jdbc, RoleRepository roles) {
		this.jdbc = jdbc;
		this.roles = roles;
	}

	@Transactional(readOnly = true)
	public PageResponse<LoginEventView> logins(Long userId, String result, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (userId != null) {
			where.append(" and user_id = ?");
			args.add(userId);
		}
		if (result != null && !result.isBlank()) {
			where.append(" and result = ?");
			args.add(result.trim().toUpperCase());
		}
		Long total = jdbc.queryForObject("select count(*) from login_event" + where, Long.class, args.toArray());
		args.add(size);
		args.add(page * size);
		List<LoginEventView> items = jdbc.query("select * from login_event" + where
				+ " order by at desc, id desc limit ? offset ?", (rs, i) -> new LoginEventView(rs.getLong("id"),
						(Long) rs.getObject("user_id"), rs.getString("username"), rs.getString("result"),
						rs.getString("ip"), rs.getString("user_agent"), rs.getTimestamp("at").toInstant()),
				args.toArray());
		return PageResponse.of(items, page, size, total == null ? 0 : total);
	}

	@Transactional(readOnly = true)
	public PageResponse<AuditEntry> audit(String entityType, Long entityId, String q, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (entityType != null && !entityType.isBlank()) {
			where.append(" and entity_type = ?");
			args.add(entityType.trim());
		}
		if (entityId != null) {
			where.append(" and entity_id = ?");
			args.add(entityId);
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(action) like ? or lower(coalesce(actor_name, '')) like ? "
					+ "or lower(coalesce(after_data::text, '')) like ?)");
			String like = "%" + q.trim().toLowerCase() + "%";
			args.addAll(List.of(like, like, like));
		}
		Long total = jdbc.queryForObject("select count(*) from audit_log" + where, Long.class, args.toArray());
		args.add(size);
		args.add(page * size);
		List<AuditEntry> items = jdbc.query("select * from audit_log" + where + " order by at desc, id desc limit ? offset ?",
				(rs, i) -> new AuditEntry(rs.getLong("id"), rs.getString("actor_type"), rs.getString("actor_name"),
						rs.getObject("support_session_id") != null, rs.getString("action"), rs.getString("entity_type"),
						(Long) rs.getObject("entity_id"), rs.getString("before_data"), rs.getString("after_data"),
						rs.getString("reason"), rs.getTimestamp("at").toInstant()),
				args.toArray());
		return PageResponse.of(items, page, size, total == null ? 0 : total);
	}

	@Transactional(readOnly = true)
	public List<RoleView> roles() {
		Map<Long, Long> counts = new java.util.HashMap<>();
		jdbc.query("select role_id, count(*) from app_user where status = 'ACTIVE' group by role_id",
				rs -> {
					counts.put(rs.getLong(1), rs.getLong(2));
				});
		return roles.findAllOrdered()
			.stream()
			.map((Role r) -> new RoleView(r.getId(), r.getName(), r.getSystemKey(), r.getDescription(), r.getPermissions(),
					counts.getOrDefault(r.getId(), 0L)))
			.toList();
	}

	public List<PermissionGroup> permissionCatalogue() {
		return Permissions.CATALOGUE.entrySet()
			.stream()
			.map(e -> new PermissionGroup(e.getKey(),
					e.getValue().entrySet().stream().map(p -> new PermissionView(p.getKey(), p.getValue())).toList()))
			.toList();
	}
}
