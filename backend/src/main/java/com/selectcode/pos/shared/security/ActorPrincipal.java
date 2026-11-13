package com.selectcode.pos.shared.security;

import com.selectcode.pos.shared.tenant.TenantPrincipal;

/** Common view of any signed-in principal (shop user or platform user), stored in the server-side session. */
public interface ActorPrincipal extends TenantPrincipal {

	ActorType actorType();

	long actorId();

	String displayName();

	/** Username for shop users, email for platform users. */
	String loginName();

	/** Code of the shop the principal is working in, or {@code null}. */
	String shopCode();

	/** Name of the shop the principal is working in, or {@code null}. */
	String shopName();

	/** The support session a platform user is working under inside a shop, or {@code null}. */
	default Long supportSessionId() {
		return null;
	}
}
