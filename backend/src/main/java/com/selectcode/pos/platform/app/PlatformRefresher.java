package com.selectcode.pos.platform.app;

import com.selectcode.pos.platform.PlatformPrincipal;
import com.selectcode.pos.platform.domain.PlatformUser;
import com.selectcode.pos.platform.infra.PlatformUserRepository;
import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.security.PlatformAuthorities;
import com.selectcode.pos.shared.security.PrincipalRefresher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per request for platform users: account still active, capabilities from the current platform role. Inside a support
 * session: every shop permission, but changes are refused until the owner's approval code was entered (PLT-17).
 */
@Component
class PlatformRefresher implements PrincipalRefresher {

	private final PlatformUserRepository users;

	private final JdbcTemplate jdbc;

	PlatformRefresher(PlatformUserRepository users, JdbcTemplate jdbc) {
		this.users = users;
		this.jdbc = jdbc;
	}

	@Override
	public boolean supports(ActorPrincipal principal) {
		return principal instanceof PlatformPrincipal;
	}

	@Override
	@Transactional(readOnly = true)
	public Refreshed refresh(ActorPrincipal principal) {
		PlatformPrincipal admin = (PlatformPrincipal) principal;
		PlatformUser user = users.findById(admin.platformUserId()).filter(PlatformUser::isActive).orElse(null);
		if (user == null) {
			return Refreshed.invalid();
		}
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority(PlatformAuthorities.ROLE_PLATFORM));
		PlatformAuthorities.BY_ROLE.getOrDefault(user.getRole().name(), List.of())
			.forEach(a -> authorities.add(new SimpleGrantedAuthority(a)));
		WriteBlock block = null;
		if (admin.activeShopId() != null) {
			List<Map<String, Object>> rows = jdbc.queryForList(
					"select ended_at, write_approved_at from support_session where id = ? and shop_id = ? "
							+ "and platform_user_id = ?",
					admin.supportSessionId(), admin.activeShopId(), admin.platformUserId());
			if (rows.isEmpty() || rows.getFirst().get("ended_at") != null) {
				return Refreshed.invalid();
			}
			Permissions.all().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
			if (rows.getFirst().get("write_approved_at") == null) {
				block = new WriteBlock(403, "support_read_only",
						"Support sessions are read-only. Ask the shop owner for an approval code to make changes.");
			}
		}
		return new Refreshed(true, authorities, false, block);
	}
}
