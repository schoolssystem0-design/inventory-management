package com.selectcode.pos.platform;

import com.selectcode.pos.shared.security.ActorPrincipal;
import com.selectcode.pos.shared.security.ActorType;
import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * A signed-in platform user (SelectCode staff). Outside a shop {@code activeShopId} is null; inside a support session it
 * is set and every shop API works in that shop (read-only until the owner approves changes, PLT-17).
 */
public record PlatformPrincipal(long platformUserId, String email, String name, String role, Long activeShopId,
		String activeShopCode, String activeShopName, Long supportSession)
		implements ActorPrincipal, AuthenticatedPrincipal, Serializable {

	public PlatformPrincipal inSupportSession(long sessionId, long shopId, String code, String shopName) {
		return new PlatformPrincipal(platformUserId, email, name, role, shopId, code, shopName, sessionId);
	}

	public PlatformPrincipal outsideShops() {
		return new PlatformPrincipal(platformUserId, email, name, role, null, null, null, null);
	}

	public static String sessionName(long platformUserId) {
		return "p" + platformUserId;
	}

	@Override
	public String getName() {
		return sessionName(platformUserId);
	}

	@Override
	public Long tenantShopId() {
		return activeShopId;
	}

	@Override
	public ActorType actorType() {
		return ActorType.PLATFORM;
	}

	@Override
	public long actorId() {
		return platformUserId;
	}

	@Override
	public String displayName() {
		return name;
	}

	@Override
	public String loginName() {
		return email;
	}

	@Override
	public String shopCode() {
		return activeShopCode;
	}

	@Override
	public String shopName() {
		return activeShopName;
	}

	@Override
	public Long supportSessionId() {
		return supportSession;
	}
}
