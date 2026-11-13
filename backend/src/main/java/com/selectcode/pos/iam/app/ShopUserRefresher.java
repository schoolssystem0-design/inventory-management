package com.selectcode.pos.iam.app;

import com.selectcode.pos.iam.ShopUserPrincipal;
import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.security.PrincipalRefresher;
import com.selectcode.pos.shared.tenant.ShopLookup;
import com.selectcode.pos.shared.tenant.ShopLookup.ShopRef;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per request: the user must still be active and the shop open; authorities are the user's current permissions. A role
 * change, deactivation or suspension therefore applies on the user's next click. Read-only shops (overdue
 * subscription) may view, print and export but not change anything (ONB-05).
 */
@Component
class ShopUserRefresher implements PrincipalRefresher {

	static final String SHOP_USER_AUTHORITY = "ROLE_SHOP_USER";

	private final JdbcTemplate jdbc;

	private final ShopLookup shops;

	ShopUserRefresher(JdbcTemplate jdbc, ShopLookup shops) {
		this.jdbc = jdbc;
		this.shops = shops;
	}

	@Override
	public boolean supports(ActorPrincipal principal) {
		return principal instanceof ShopUserPrincipal;
	}

	@Override
	@Transactional(readOnly = true)
	public Refreshed refresh(ActorPrincipal principal) {
		ShopUserPrincipal user = (ShopUserPrincipal) principal;
		ShopRef shop = shops.findById(user.shopId()).orElse(null);
		if (shop == null || !shop.canSignIn()) {
			return Refreshed.invalid();
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				select u.status, u.must_change_password, u.is_owner, r.permissions
				from app_user u join role r on r.id = u.role_id
				where u.id = ? and u.shop_id = ?""", user.userId(), user.shopId());
		if (rows.isEmpty() || !"ACTIVE".equals(rows.getFirst().get("status"))) {
			return Refreshed.invalid();
		}
		Map<String, Object> row = rows.getFirst();
		List<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add(new SimpleGrantedAuthority(SHOP_USER_AUTHORITY));
		List<String> permissions = Boolean.TRUE.equals(row.get("is_owner")) ? Permissions.all() : array(row.get("permissions"));
		for (String permission : permissions) {
			authorities.add(new SimpleGrantedAuthority(permission));
		}
		WriteBlock block = shop.writable() ? null
				: new WriteBlock(402, "subscription_read_only",
						"The subscription is overdue, so the shop is read-only. Pay the subscription to make changes.");
		return new Refreshed(true, authorities, Boolean.TRUE.equals(row.get("must_change_password")), block);
	}

	private static List<String> array(Object value) {
		try {
			if (value instanceof Array array) {
				return List.of((String[]) array.getArray());
			}
			if (value instanceof String[] strings) {
				return List.of(strings);
			}
			return List.of();
		}
		catch (java.sql.SQLException e) {
			throw new IllegalStateException(e);
		}
	}
}
