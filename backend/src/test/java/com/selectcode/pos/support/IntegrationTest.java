package com.selectcode.pos.support;

import com.jayway.jsonpath.JsonPath;
import com.selectcode.pos.platform.app.ShopAdminService;
import com.selectcode.pos.platform.app.ShopAdminService.NewShop;
import com.selectcode.pos.shared.messaging.DevOutbox;
import com.selectcode.pos.shared.security.Totp;
import com.selectcode.pos.shared.web.RateLimiter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base for integration tests: full application on a real PostgreSQL. The app connects as the restricted runtime role
 * (RLS applies); Flyway migrates as the superuser, exactly like production. Shops are created through the real
 * creation wizard code, so every test also exercises shop provisioning.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

	public static final String PASSWORD = "Correct-Horse-42";

	private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected ShopAdminService shopAdmin;

	@Autowired
	protected DevOutbox outbox;

	@Autowired
	private RateLimiter rateLimiter;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
		registry.add("spring.datasource.username", () -> TestDatabase.APP_USER);
		registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
		registry.add("spring.flyway.url", TestDatabase::jdbcUrl);
		registry.add("spring.flyway.user", () -> "postgres");
		registry.add("spring.flyway.password", () -> "postgres");
		registry.add("app.db.app-user", () -> TestDatabase.APP_USER);
		registry.add("app.db.app-password", () -> TestDatabase.APP_PASSWORD);
		registry.add("app.security.cookie-secure", () -> "false");
	}

	/** All requests in tests come from 127.0.0.1; don't let one test's sign-ins throttle the next. */
	@BeforeEach
	void resetRateLimits() {
		rateLimiter.reset();
	}

	// ------------------------------------------------------------------ helpers

	public record TestShop(long id, String code, long ownerId, ApiClient owner) {
	}

	protected ApiClient client() {
		return new ApiClient(mvc);
	}

	protected static JdbcTemplate db() {
		return TestDatabase.superuser();
	}

	protected static String unique(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8);
	}

	/** A shop of the given type and plan, created through the wizard, with a signed-in owner. */
	protected TestShop newShop(String type, String plan) throws Exception {
		return newShop(type, plan, 14);
	}

	protected TestShop newShop(String type, String plan, int trialDays) throws Exception {
		String code = unique("t");
		var created = shopAdmin.create(new NewShop(code, "Test " + type + " " + code, "Lahore", "Mall Road",
				"0300-1234567", null, null, type, null, null, plan, "MONTH", null, null, null, trialDays, null,
				List.of("Main shop", "Godown"), "NONE", null, "EN", true, "Owner " + code, "owner",
				"owner@" + code + ".test", null, null));
		long ownerId = created.ownerSetup().userId();
		db().update("update app_user set password_hash = ?, must_change_password = false where id = ?",
				ENCODER.encode(PASSWORD), ownerId);
		ApiClient owner = client();
		owner.login(code, "owner", PASSWORD).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		return new TestShop(created.shopId(), code, ownerId, owner);
	}

	/** A user with the given system role (e.g. CASHIER) and the test password, signed in. */
	protected ApiClient userWithRole(TestShop shop, String roleKey, String username) throws Exception {
		createUser(shop, roleKey, username);
		ApiClient client = client();
		client.login(shop.code(), username, PASSWORD).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		return client;
	}

	protected long createUser(TestShop shop, String roleKey, String username) {
		Long roleId = db().queryForObject("select id from role where shop_id = ? and system_key = ?", Long.class,
				shop.id(), roleKey);
		return db().queryForObject("""
				insert into app_user (shop_id, username, display_name, role_id, password_hash)
				values (?, ?, ?, ?, ?) returning id""", Long.class, shop.id(), username, "User " + username, roleId,
				ENCODER.encode(PASSWORD));
	}

	/** A platform user with an enrolled authenticator, signed in. */
	protected ApiClient platformUser(String role) throws Exception {
		String email = unique("p") + "@selectcode.test";
		String secret = Totp.newSecret();
		db().update("insert into platform_user (email, name, role, password_hash, totp_secret) values (?, ?, ?, ?, ?)",
				email, "Platform " + role, role, ENCODER.encode(PASSWORD), secret);
		ApiClient client = client();
		client.platformLogin(email, PASSWORD, secret).andExpect(
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		return client;
	}

	protected static <T> T json(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
	}

	protected static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}
}
