package com.selectcode.pos.shared.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Feeds the current shop to Hibernate's {@code @TenantId} discriminator, so every JPA query on a
 * {@code ShopScopedEntity} is filtered by shop and every insert is stamped with it.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long> {

	/** Matches no shop: tenant entities are invisible when no tenant is set. */
	static final Long NO_SHOP = -1L;

	/** Root tenant: disables the filter. Only used across all shops (platform overview). */
	static final Long ALL_SHOPS = 0L;

	@Override
	public Long resolveCurrentTenantIdentifier() {
		if (TenantContext.isAllShops()) {
			return ALL_SHOPS;
		}
		return TenantContext.shopId().orElse(NO_SHOP);
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return true;
	}

	@Override
	public boolean isRoot(Long tenantId) {
		return ALL_SHOPS.equals(tenantId);
	}
}
