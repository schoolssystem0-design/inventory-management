package com.selectcode.pos.sales.app;

import com.selectcode.pos.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Service items the modules bill with (repair labour). Made the first time they are needed with price 0; the price
 * is set on each bill. The shop can rename them but should keep the SKU.
 */
final class ServiceItems {

	private ServiceItems() {
	}

	static long ensure(JdbcTemplate jdbc, String sku, String name) {
		List<Long> found = jdbc.queryForList("select id from product where lower(sku) = lower(?)", Long.class, sku);
		if (!found.isEmpty()) {
			return found.getFirst();
		}
		Long unit = jdbc.queryForList("""
				select id from unit where active order by decimals_allowed, (lower(name) in ('piece', 'pcs', 'service')) desc,
				id limit 1""", Long.class).stream().findFirst()
			.orElseThrow(() -> new IllegalStateException("The shop has no units"));
		return jdbc.queryForObject("""
				insert into product (shop_id, sku, name, unit_id, kind, price, description)
				values (?, ?, ?, ?, 'SERVICE', 0, 'Made by SelectCode POS for billing; keep the SKU') returning id""",
				Long.class, TenantContext.requireShopId(), sku, name, unit);
	}
}
