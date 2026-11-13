package com.selectcode.pos.platform.app;

import com.selectcode.pos.platform.app.DemoSeeder.Seeded;
import com.selectcode.pos.platform.app.ShopAdminService.NewShop;
import com.selectcode.pos.platform.app.ShopAdminService.ShopCreated;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.jobs.Jobs;
import com.selectcode.pos.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Demo shops, one per shop type, rebuilt every night with fresh sample data (OPS-01). A demo shop is marked
 * {@code is_demo}; only such shops can be wiped, so a real shop is never touched.
 */
@Service
public class DemoShops {

	private static final Logger log = LoggerFactory.getLogger(DemoShops.class);

	public enum Size {
		SMALL, FULL
	}

	private final JdbcTemplate jdbc;

	private final ShopAdminService shops;

	private final DemoSeeder seeder;

	private final AuditService audit;

	private final Jobs jobs;

	private final com.selectcode.pos.shared.files.FileStorage storage;

	DemoShops(JdbcTemplate jdbc, ShopAdminService shops, DemoSeeder seeder, AuditService audit, Jobs jobs,
			com.selectcode.pos.shared.files.FileStorage storage) {
		this.storage = storage;
		this.jdbc = jdbc;
		this.shops = shops;
		this.seeder = seeder;
		this.audit = audit;
		this.jobs = jobs;
	}

	/** Wipes and rebuilds the demo shop of a type ({@code demo-<type>}). */
	public Map<String, Object> reset(String typeKey, Size size) {
		String type = typeKey == null ? "" : typeKey.trim().toLowerCase();
		if (jdbc.queryForList("select 1 from shop_type where type_key = ?", type).isEmpty()) {
			throw ApiException.notFound("Shop type");
		}
		String code = "demo-" + type.replace('_', '-');
		List<Map<String, Object>> existing = jdbc.queryForList("select id, is_demo from shop where code = ?", code);
		if (!existing.isEmpty()) {
			if (!Boolean.TRUE.equals(existing.getFirst().get("is_demo"))) {
				throw ApiException.rule("not_demo", "Shop " + code + " is not a demo shop and will not be wiped");
			}
			long oldId = ((Number) existing.getFirst().get("id")).longValue();
			jdbc.queryForObject("select purge_demo_shop(?)", Integer.class, oldId);
			storage.deletePrefix(com.selectcode.pos.shared.files.FileStorage.shopPrefix(oldId));
		}
		String name = jdbc.queryForObject("select name from shop_type where type_key = ?", String.class, type);
		ShopCreated created = shops.create(new NewShop(code, "Demo " + name, "Lahore", "Demo street", "0300-0000000",
				null, null, type, null, null, "enterprise", "MONTH", null, null, null, 30, null,
				List.of("Main shop", "Godown"), "NONE", null, "BOTH", true, "Demo Owner", "owner", null, null,
				"Demo shop, rebuilt every night"));
		jdbc.update("update shop set is_demo = true where id = ?", created.shopId());
		Seeded seeded = seeder.seed(created.shopId(), type,
				size == Size.FULL ? DemoSeeder.Size.FULL : DemoSeeder.Size.SMALL);
		audit.recordPlatform("DEMO_SHOP_RESET", created.shopId(), Map.of("type", type));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("shopId", created.shopId());
		result.put("code", code);
		result.put("ownerSetupLink", created.ownerSetup().link());
		result.put("products", seeded.products());
		result.put("sales", seeded.sales());
		result.put("users", seeded.users());
		return result;
	}

	/** 04:00 every night: rebuild the demo shops that exist. */
	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Karachi")
	public void nightly() {
		if (!jobs.enabled()) {
			return;
		}
		jobs.exclusive("demo-shops", () -> {
			for (String type : jdbc.queryForList("""
					select t.type_key from shop s join shop_type t on t.id = s.shop_type_id where s.is_demo""", String.class)) {
				try {
					reset(type, Size.FULL);
				}
				catch (RuntimeException e) {
					log.warn("demo shop {} could not be rebuilt: {}", type, e.getMessage(), e);
				}
			}
		});
	}
}
