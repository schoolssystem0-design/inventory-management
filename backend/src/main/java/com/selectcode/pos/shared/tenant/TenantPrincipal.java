package com.selectcode.pos.shared.tenant;

/** An authenticated principal that belongs to, or is currently working in, at most one shop. */
public interface TenantPrincipal {

	/** The shop this principal's requests run as, or {@code null} (platform user outside any shop). */
	Long tenantShopId();
}
