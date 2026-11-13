package com.selectcode.pos.shop.app;

import com.selectcode.pos.shared.security.ActorType;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which branches the current user may work in (ACC-03). Users tied to branches only see and act in those; the owner,
 * users with "every branch" and SelectCode support see all. Branch scoping is a query filter, not RLS
 * (docs/ARCHITECTURE.md §3).
 */
@Component
public class BranchAccess {

	public record BranchRef(long id, String name, String kind, boolean active) {
	}

	private final JdbcTemplate jdbc;

	BranchAccess(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Allowed branch ids, or {@code null} when the user may use every branch. */
	public List<Long> allowedIds() {
		if (CurrentActor.type() != ActorType.USER) {
			return null;
		}
		Long userId = CurrentActor.id();
		Map<String, Object> user = jdbc.queryForList("select is_owner, all_branches from app_user where id = ?", userId)
			.stream()
			.findFirst()
			.orElse(null);
		if (user == null || Boolean.TRUE.equals(user.get("is_owner")) || Boolean.TRUE.equals(user.get("all_branches"))) {
			return null;
		}
		return jdbc.queryForList("select branch_id from user_branch where user_id = ?", Long.class, userId);
	}

	public boolean allowed(long branchId) {
		List<Long> ids = allowedIds();
		return ids == null || ids.contains(branchId);
	}

	/** The branch, if it exists in this shop and the user may use it; 404 otherwise. */
	public BranchRef require(Long branchId) {
		if (branchId == null) {
			throw ApiException.field("branchId", "required", "Choose a branch");
		}
		BranchRef branch = jdbc
			.query("select id, name, kind, active from branch where id = ?",
					(rs, i) -> new BranchRef(rs.getLong("id"), rs.getString("name"), rs.getString("kind"),
							rs.getBoolean("active")),
					branchId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Branch"));
		if (!allowed(branch.id())) {
			throw ApiException.notFound("Branch");
		}
		return branch;
	}

	/** Like {@link #require} and the branch must be active. */
	public BranchRef requireActive(Long branchId) {
		BranchRef branch = require(branchId);
		if (!branch.active()) {
			throw ApiException.rule("branch_inactive", branch.name() + " is switched off");
		}
		return branch;
	}

	/** The branch to use when none is given: the default branch if allowed, else the user's first branch. */
	public long defaultBranchId() {
		List<Long> ids = allowedIds();
		Long def = jdbc.queryForObject(
				"select id from branch where active order by is_default desc, id limit 1", Long.class);
		if (ids == null || ids.contains(def)) {
			return def;
		}
		if (ids.isEmpty()) {
			throw ApiException.forbidden("no_branch", "You are not assigned to any branch");
		}
		return ids.getFirst();
	}

	/** SQL condition limiting {@code column} to the allowed branches ("" when all are allowed). */
	public String filter(String column) {
		List<Long> ids = allowedIds();
		if (ids == null) {
			return "";
		}
		if (ids.isEmpty()) {
			return " and false";
		}
		return " and " + column + " in (" + String.join(",", ids.stream().map(String::valueOf).toList()) + ")";
	}
}
