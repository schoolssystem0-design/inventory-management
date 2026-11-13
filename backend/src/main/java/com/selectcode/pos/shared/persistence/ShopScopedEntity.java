package com.selectcode.pos.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every shop-owned entity. {@code shop_id} is filled from the current tenant on insert and added to
 * every query by Hibernate; PostgreSQL row-level security enforces the same rule underneath.
 */
@MappedSuperclass
public abstract class ShopScopedEntity {

	@TenantId
	@Column(name = "shop_id", nullable = false, updatable = false)
	private Long shopId;

	public Long getShopId() {
		return shopId;
	}
}
