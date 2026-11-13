package com.selectcode.pos;

import com.selectcode.pos.platform.app.DemoSeeder;
import com.selectcode.pos.platform.app.ShopAdminService;
import com.selectcode.pos.platform.app.ShopAdminService.NewShop;
import com.selectcode.pos.support.TestDatabase;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Runs the API locally on an embedded PostgreSQL (no Docker or installed database needed):
 * {@code mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.selectcode.pos.LocalDevApplication"}.
 *
 * <p>Seeds a super admin and five demo shops created through the real creation wizard. Data is discarded when the
 * process stops. Emails (setup and reset links) appear at http://localhost:8080/api/v1/dev/outbox.
 */
public class LocalDevApplication {

	static final String DEMO_PASSWORD = "Demo-Shop-2026";

	static final String ADMIN_EMAIL = "admin@local.test";

	static final String ADMIN_PASSWORD = "Admin-Local-2026";

	/** Fixed authenticator secret for local development only: add it to an authenticator app by hand. */
	static final String ADMIN_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

	public static void main(String[] args) {
		String url = TestDatabase.jdbcUrl();
		System.setProperty("spring.datasource.url", url);
		System.setProperty("spring.datasource.username", TestDatabase.APP_USER);
		System.setProperty("spring.datasource.password", TestDatabase.APP_PASSWORD);
		System.setProperty("spring.flyway.url", url);
		System.setProperty("spring.flyway.user", "postgres");
		System.setProperty("spring.flyway.password", "postgres");
		System.setProperty("app.db.app-user", TestDatabase.APP_USER);
		System.setProperty("app.db.app-password", TestDatabase.APP_PASSWORD);
		System.setProperty("app.security.signin-limit-per-network", "300");
		System.setProperty("app.root-domain", "localhost:3000");

		ConfigurableApplicationContext context = SpringApplication.from(PosApplication::main)
			.withAdditionalProfiles("local")
			.run(args)
			.getApplicationContext();
		seed(context.getBean(ShopAdminService.class), context.getBean(DemoSeeder.class));
	}

	private static void seed(ShopAdminService shops, DemoSeeder seeder) {
		JdbcTemplate db = TestDatabase.superuser();
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
		db.update("""
				insert into platform_user (email, name, role, password_hash, totp_secret)
				values (?, 'Local Super Admin', 'SUPER_ADMIN', ?, ?) on conflict do nothing""", ADMIN_EMAIL,
				encoder.encode(ADMIN_PASSWORD), ADMIN_TOTP_SECRET);
		String hash = encoder.encode(DEMO_PASSWORD);
		seedShop(shops, seeder, db, "demo-mobile", "Ali Mobiles (Demo)", "mobile", "business", hash);
		seedShop(shops, seeder, db, "demo-mart", "City Mart (Demo)", "super_store", "enterprise", hash);
		seedShop(shops, seeder, db, "demo-pharmacy", "Shifa Pharmacy (Demo)", "medical", "business", hash);
		seedShop(shops, seeder, db, "demo-electronics", "Star Electronics (Demo)", "electronics", "enterprise", hash);
		seedShop(shops, seeder, db, "demo-hardware", "Build Mart Hardware (Demo)", "hardware", "enterprise", hash);
		System.out.println("""

				  Local API ready on http://localhost:8080   (OpenAPI: http://localhost:8080/v3/api-docs)
				  Shops        : demo-mobile, demo-mart, demo-pharmacy, demo-electronics, demo-hardware / username owner / password %s
				  Super admin  : %s / %s / authenticator secret %s
				  Dev outbox   : http://localhost:8080/api/v1/dev/outbox (setup and reset links)
				""".formatted(DEMO_PASSWORD, ADMIN_EMAIL, ADMIN_PASSWORD, ADMIN_TOTP_SECRET));
	}

	private static void seedShop(ShopAdminService shops, DemoSeeder seeder, JdbcTemplate db, String code, String name,
			String type, String plan, String passwordHash) {
		if (db.queryForObject("select count(*) from shop where code = ?", Long.class, code) > 0) {
			return;
		}
		var created = shops.create(new NewShop(code, name, "Lahore", "Main Boulevard, Gulberg", "0300-1234567", null,
				null, type, null, null, plan, "MONTH", null, null, null, 14, null, List.of("Main shop", "Godown"), "NONE",
				null, "BOTH", true, "Demo Owner", "owner", "owner@" + code + ".local.test", null, null));
		// Demo shortcut: a known password instead of the setup link.
		db.update("update app_user set password_hash = ?, must_change_password = false where id = ?", passwordHash,
				created.ownerSetup().userId());
		// Suppliers, customers, staff, stock and a few days of sales, so every screen has something to show.
		seeder.seed(created.shopId(), type, DemoSeeder.Size.SMALL);
	}
}
