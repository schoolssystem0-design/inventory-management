package com.selectcode.pos.iam;

import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.ActorType;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The signed-in shop user, stored in the server-side session. Permissions and account status are not stored here; they
 * are re-read on every request. {@link #getName()} is unique across shops so all sessions of one user can be found
 * and ended.
 */
public record ShopUserPrincipal(long userId, long shopId, String shopCodeValue, String shopNameValue, String username,
		String displayName) implements ActorPrincipal, AuthenticatedPrincipal, Serializable {

	public static String sessionName(long shopId, long userId) {
		return "s" + shopId + ":u" + userId;
	}

	@Override
	public String getName() {
		return sessionName(shopId, userId);
	}

	@Override
	public Long tenantShopId() {
		return shopId;
	}

	@Override
	public ActorType actorType() {
		return ActorType.USER;
	}

	@Override
	public long actorId() {
		return userId;
	}

	@Override
	public String loginName() {
		return username;
	}

	@Override
	public String shopCode() {
		return shopCodeValue;
	}

	@Override
	public String shopName() {
		return shopNameValue;
	}
}
