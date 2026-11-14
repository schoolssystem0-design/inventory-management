package com.selectcode.pos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.shared.security.Totp;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The super admin portal (PLT-01..27, BIL-01..10, ONB-01, ONB-04, ONB-07; QA TC-PLT-*, TC-BIL-02, TC-ONB-01). */
class PlatformIT extends IntegrationTest {

	private static final List<String> TYPES = List.of("mobile", "super_store", "medical", "garments", "electronics",
			"hardware", "cosmetics", "books", "auto_parts", "general");

	// ------------------------------------------------------------------ sign-in

	@Test
	void firstSignInEnrolsTheAuthenticatorThenAlwaysAsksForACode() throws Exception {
		String email = unique("first") + "@selectcode.test";
		TestDatabase.superuser().update("insert into platform_user (email, name, password_hash) values (?, 'Owner', ?)",
				email, new BCryptPasswordEncoder(4).encode(PASSWORD));
		ApiClient client = client();
		var first = client.post("/api/v1/platform/auth/login",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isOk());
		assertThat((Boolean) json(first, "$.enrolmentRequired")).isTrue();
		String secret = json(first, "$.secret");
		client.post("/api/v1/platform/auth/totp/enroll", "{\"code\":\"000000\"}").andExpect(status().isUnprocessableContent());
		client.post("/api/v1/platform/auth/totp/enroll", "{\"code\":\"" + Totp.codeAt(secret, Instant.now()) + "\"}")
			.andExpect(status().isOk());
		client.get("/api/v1/platform/auth/me").andExpect(status().isOk());

		ApiClient again = client();
		again.post("/api/v1/platform/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isUnauthorized());
		again.platformLogin(email, "wrong-password-1", secret).andExpect(status().isUnauthorized());
		again.platformLogin(email, PASSWORD, secret).andExpect(status().isOk());
	}

	@Test
	void shopUsersCannotUseThePortalAndPlatformUsersCannotUseShopApisOutsideSupport() throws Exception {
		TestShop shop = newShop("general", "starter");
		shop.owner().get("/api/v1/platform/shops").andExpect(status().isForbidden());
		ApiClient admin = platformUser("SUPER_ADMIN");
		var noShop = admin.get("/api/v1/products").andExpect(status().isForbidden());
		assertThat((String) json(noShop, "$.code")).isEqualTo("no_shop");
	}

	@Test
	void rolesLimitWhatPlatformUsersMayDo() throws Exception {
		ApiClient support = platformUser("SUPPORT");
		support.get("/api/v1/platform/shops").andExpect(status().isOk());
		support.post("/api/v1/platform/shops", "{}").andExpect(status().isForbidden());
		support.get("/api/v1/platform/users").andExpect(status().isForbidden());
		ApiClient billing = platformUser("BILLING");
		billing.post("/api/v1/platform/support-session", "{\"shopId\":1,\"reason\":\"x\"}").andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ creating shops

	@Test
	void everyShopTypeCanBeCreatedWithItsModulesCategoriesUnitsRolesAndOwnerLink() throws Exception {
		ApiClient admin = platformUser("SUPER_ADMIN");
		for (String type : TYPES) {
			String code = unique("c");
			outbox.clear();
			var created = admin.post("/api/v1/platform/shops", """
					{"code":"%s","name":"%s shop","city":"Karachi","typeKey":"%s","planKey":"business","cycle":"YEAR",
					 "trialDays":14,"branches":["Main","Godown","Branch 2"],"taxMode":"EXCLUSIVE","taxRate":"18",
					 "receiptLanguage":"BOTH","ownerName":"Ali","ownerUsername":"ali","ownerEmail":"ali@%s.test"}"""
				.formatted(code, type, type, code)).andExpect(status().isCreated());
			long id = ((Number) json(created, "$.shopId")).longValue();
			assertThat((String) json(created, "$.status")).isEqualTo("TRIAL");
			assertThat((String) json(created, "$.ownerSetup.link")).startsWith("http://" + code + ".pos.test/setup?token=");
			assertThat((Boolean) json(created, "$.ownerSetup.emailed")).isTrue();
			assertThat(outbox.entries()).anySatisfy(e -> assertThat(e.message().recipient()).isEqualTo("ali@" + code + ".test"));

			List<String> defaults = TestDatabase.superuser().queryForList(
					"select unnest(default_modules) from shop_type where type_key = ?", String.class, type);
			List<String> enabled = json(created, "$.modules");
			assertThat(enabled).containsExactlyInAnyOrderElementsOf(defaults);

			var profile = admin.get("/api/v1/platform/shops/" + id).andExpect(status().isOk());
			assertThat((Integer) json(profile, "$.usage.branches")).isEqualTo(3);
			assertThat((Integer) json(profile, "$.usage.tills")).isEqualTo(2);
			assertThat((String) json(profile, "$.subscription.price")).isEqualTo("55000.00");
			assertThat((String) json(profile, "$.owner.username")).isEqualTo("ali");
			Long categories = TestDatabase.superuser().queryForObject("select count(*) from category where shop_id = ?",
					Long.class, id);
			assertThat(categories).as(type + " categories").isPositive();
			Long units = TestDatabase.superuser().queryForObject("select count(*) from unit where shop_id = ?", Long.class, id);
			assertThat(units).as(type + " units").isPositive();
			List<String> roles = TestDatabase.superuser().queryForList(
					"select system_key from role where shop_id = ? order by system_key", String.class, id);
			assertThat(roles).contains("OWNER", "MANAGER", "CASHIER", "STOCK_KEEPER", "ACCOUNTANT", "PARTNER");
			if (type.equals("medical")) {
				assertThat(roles).contains("PHARMACIST");
			}
			if (type.equals("mobile")) {
				assertThat(roles).contains("TECHNICIAN");
			}
			Map<String, Object> settings = TestDatabase.superuser()
				.queryForMap("select tax_mode, tax_rate, receipt_language from shop_settings where shop_id = ?", id);
			assertThat(settings.get("tax_mode")).isEqualTo("EXCLUSIVE");
			assertThat(settings.get("receipt_language")).isEqualTo("BOTH");
		}
	}

	@Test
	void ownerCompletesTheSetupLinkOnceAndSignsIn() throws Exception {
		ApiClient admin = platformUser("SUPER_ADMIN");
		String code = unique("o");
		var created = admin.post("/api/v1/platform/shops", """
				{"code":"%s","name":"Owner test","typeKey":"mobile","planKey":"starter","ownerName":"Sara",
				 "ownerUsername":"sara"}""".formatted(code)).andExpect(status().isCreated());
		String link = json(created, "$.ownerSetup.link");
		String token = link.substring(link.indexOf("token=") + 6);
		ApiClient owner = client();
		owner.post("/api/v1/auth/setup", "{\"token\":\"" + token + "\",\"newPassword\":\"short\"}")
			.andExpect(status().isBadRequest());
		owner.post("/api/v1/auth/setup", "{\"token\":\"" + token + "\",\"newPassword\":\"Mobile-Shop-2026\"}")
			.andExpect(status().isNoContent());
		owner.post("/api/v1/auth/setup", "{\"token\":\"" + token + "\",\"newPassword\":\"Mobile-Shop-2027\"}")
			.andExpect(status().isUnprocessableContent());
		owner.login(code, "sara", "Mobile-Shop-2026").andExpect(status().isOk());
		assertThat((Boolean) json(owner.get("/api/v1/shop/context"), "$.user.owner")).isTrue();

		// A new link can be sent; it replaces the old one.
		var again = admin.post("/api/v1/platform/shops/" + json(created, "$.shopId") + "/owner/setup-link")
			.andExpect(status().isOk());
		assertThat((String) json(again, "$.link")).contains("token=");
	}

	@Test
	void shopCodesAreCheckedAndSuggested() throws Exception {
		ApiClient admin = platformUser("ONBOARDING");
		TestShop existing = newShop("general", "starter");
		var check = admin.get("/api/v1/platform/shops/code-check?code=" + existing.code() + "&name=Ali Mobiles")
			.andExpect(status().isOk());
		assertThat((Boolean) json(check, "$.available")).isFalse();
		assertThat((String) json(check, "$.suggestion")).startsWith("alimobiles");
		assertThat((Boolean) json(admin.get("/api/v1/platform/shops/code-check?code=admin"), "$.available")).isFalse();
		assertThat((Boolean) json(admin.get("/api/v1/platform/shops/code-check?code=Bad_Code"), "$.available")).isFalse();
		var taken = admin.post("/api/v1/platform/shops", """
				{"code":"%s","name":"Dup","typeKey":"general","planKey":"starter","ownerName":"X"}"""
			.formatted(existing.code())).andExpect(status().isConflict());
		assertThat((String) json(taken, "$.code")).isEqualTo("code_taken");
		admin.post("/api/v1/platform/shops", """
				{"code":"%s","name":"Bad type","typeKey":"bakery-x","planKey":"starter","ownerName":"X"}"""
			.formatted(unique("b"))).andExpect(status().isBadRequest());
	}

	@Test
	void starterPlanKeepsOnlyFirstReleaseModulesAndLimitsUsers() throws Exception {
		TestShop shop = newShop("mobile", "starter");
		List<String> modules = json(shop.owner().get("/api/v1/shop/context"), "$.modules[*].key");
		assertThat(modules).contains("serials", "used_buyback", "warranty").doesNotContain("repairs");
		// Starter allows 2 users: the owner and one more.
		long roleId = TestDatabase.superuser().queryForObject(
				"select id from role where shop_id = ? and system_key = 'CASHIER'", Long.class, shop.id());
		shop.owner().post("/api/v1/users", """
				{"username":"cashier1","displayName":"C1","roleId":%d}""".formatted(roleId)).andExpect(status().isCreated());
		var limit = shop.owner().post("/api/v1/users", """
				{"username":"cashier2","displayName":"C2","roleId":%d}""".formatted(roleId)).andExpect(status().isPaymentRequired());
		assertThat((String) json(limit, "$.code")).isEqualTo("plan_limit");
		assertThat((String) json(limit, "$.limit")).isEqualTo("users");
		// Starter allows 1 branch; the test shop already has two (creation is not limited), so no more.
		shop.owner().post("/api/v1/branches", "{\"name\":\"Third\"}").andExpect(status().isPaymentRequired());
	}

	// ------------------------------------------------------------------ managing shops

	@Test
	void modulesCanBeSwitchedAndTheApiFollows() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient admin = platformUser("SUPER_ADMIN");
		Integer unitId = json(shop.owner().get("/api/v1/units"), "$[0].id");
		String body = "{\"name\":\"Para\",\"price\":\"10\",\"unitId\":" + unitId + ",\"pharma\":{\"formula\":\"Paracetamol\"}}";
		var refused = shop.owner().post("/api/v1/products", body).andExpect(status().isForbidden());
		assertThat((String) json(refused, "$.code")).isEqualTo("module_disabled");

		admin.put("/api/v1/platform/shops/" + shop.id() + "/modules", "{\"formula\":true}").andExpect(status().isOk());
		shop.owner().post("/api/v1/products", body).andExpect(status().isCreated());
		List<String> roles = TestDatabase.superuser().queryForList("select system_key from role where shop_id = ?",
				String.class, shop.id());
		assertThat(roles).contains("PHARMACIST");

		admin.put("/api/v1/platform/shops/" + shop.id() + "/modules", "{\"formula\":false}").andExpect(status().isOk());
		shop.owner().post("/api/v1/products", body.replace("Para", "Para 2")).andExpect(status().isForbidden());
		// Data is kept while the module is off.
		assertThat(TestDatabase.superuser().queryForObject(
				"select count(*) from product_pharma where shop_id = ?", Long.class, shop.id())).isEqualTo(1L);
		admin.put("/api/v1/platform/shops/" + shop.id() + "/modules", "{\"nonsense\":true}").andExpect(status().isBadRequest());
	}

	@Test
	void ownerMaySwitchOfferedModulesButNotLockedOnes() throws Exception {
		TestShop shop = newShop("general", "business");
		shop.owner().put("/api/v1/modules/weighed", "{\"enabled\":true}").andExpect(status().isOk());
		List<String> modules = json(shop.owner().get("/api/v1/shop/context"), "$.modules[*].key");
		assertThat(modules).contains("weighed");
		var locked = shop.owner().put("/api/v1/modules/batches", "{\"enabled\":true}").andExpect(status().isForbidden());
		assertThat((String) json(locked, "$.code")).isEqualTo("module_locked");
		shop.owner().put("/api/v1/modules/formula", "{\"enabled\":true}").andExpect(status().isForbidden());
	}

	@Test
	void suspendingSignsUsersOutAndBlocksSignInUntilReactivated() throws Exception {
		TestShop shop = newShop("general", "business");
		ApiClient admin = platformUser("SUPER_ADMIN");
		admin.post("/api/v1/platform/shops/" + shop.id() + "/suspend", "{\"reason\":\"\"}").andExpect(status().isBadRequest());
		admin.post("/api/v1/platform/shops/" + shop.id() + "/suspend", "{\"reason\":\"Not paid for 2 months\"}")
			.andExpect(status().isOk());
		shop.owner().get("/api/v1/shop/context").andExpect(status().isUnauthorized());
		var refused = client().login(shop.code(), "owner", PASSWORD).andExpect(status().isLocked());
		assertThat((String) json(refused, "$.code")).isEqualTo("shop_suspended");
		var pub = client().get("/api/v1/public/shops/" + shop.code()).andExpect(status().isOk());
		assertThat((String) json(pub, "$.status")).isEqualTo("SUSPENDED");

		admin.post("/api/v1/platform/shops/" + shop.id() + "/reactivate").andExpect(status().isOk());
		client().login(shop.code(), "owner", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void readOnlyShopsMayViewButNotChange() throws Exception {
		TestShop shop = newShop("general", "business");
		TestDatabase.superuser().update("update shop set status = 'READ_ONLY' where id = ?", shop.id());
		shop.owner().get("/api/v1/products").andExpect(status().isOk());
		var blocked = shop.owner().post("/api/v1/brands", "{\"name\":\"X\"}").andExpect(status().isPaymentRequired());
		assertThat((String) json(blocked, "$.code")).isEqualTo("subscription_read_only");
		List<String> banners = json(shop.owner().get("/api/v1/shop/context"), "$.banners[*].kind");
		assertThat(banners).contains("READ_ONLY");
		// Password change still works.
		shop.owner().post("/api/v1/auth/change-password",
				"{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Another-Long-99\"}")
			.andExpect(status().isNoContent());
	}

	@Test
	void closingNeedsTheShopCodeAndKeepsDataFor90Days() throws Exception {
		TestShop shop = newShop("general", "business");
		ApiClient admin = platformUser("SUPER_ADMIN");
		admin.post("/api/v1/platform/shops/" + shop.id() + "/close", "{\"confirmCode\":\"wrong\",\"reason\":\"Left\"}")
			.andExpect(status().isBadRequest());
		var closed = admin.post("/api/v1/platform/shops/" + shop.id() + "/close",
				"{\"confirmCode\":\"" + shop.code() + "\",\"reason\":\"Business closed\"}").andExpect(status().isOk());
		assertThat((String) json(closed, "$.status")).isEqualTo("CLOSED");
		assertThat((String) json(closed, "$.deleteAfter")).isNotNull();
		client().login(shop.code(), "owner", PASSWORD).andExpect(status().isLocked());
		ApiClient support = platformUser("SUPPORT");
		support.post("/api/v1/platform/shops/" + shop.id() + "/close",
				"{\"confirmCode\":\"" + shop.code() + "\",\"reason\":\"x\"}").andExpect(status().isForbidden());
	}

	@Test
	void subscriptionPlanChangesApplyModuleRules() throws Exception {
		TestShop shop = newShop("mobile", "business");
		ApiClient admin = platformUser("BILLING");
		List<String> before = json(shop.owner().get("/api/v1/shop/context"), "$.modules[*].key");
		assertThat(before).contains("repairs");
		var profile = admin.patch("/api/v1/platform/shops/" + shop.id() + "/subscription",
				"{\"planKey\":\"starter\",\"cycle\":\"MONTH\"}").andExpect(status().isOk());
		assertThat((String) json(profile, "$.subscription.price")).isEqualTo("2500.00");
		List<String> after = json(shop.owner().get("/api/v1/shop/context"), "$.modules[*].key");
		assertThat(after).doesNotContain("repairs").contains("serials");
		admin.patch("/api/v1/platform/shops/" + shop.id() + "/subscription", "{\"planKey\":\"business\"}")
			.andExpect(status().isOk());
		List<String> back = json(shop.owner().get("/api/v1/shop/context"), "$.modules[*].key");
		assertThat(back).contains("repairs");
	}

	@Test
	void platformCanListEditAndResetShopUsers() throws Exception {
		TestShop shop = newShop("general", "business");
		long cashier = createUser(shop, "CASHIER", "desk1");
		ApiClient admin = platformUser("SUPER_ADMIN");
		var list = admin.get("/api/v1/platform/shops/" + shop.id() + "/users").andExpect(status().isOk());
		assertThat(((Number) json(list, "$.total")).intValue()).isGreaterThanOrEqualTo(2);
		admin.patch("/api/v1/platform/shops/" + shop.id() + "/users/" + cashier,
				"{\"displayName\":\"Desk One\",\"phone\":\"03001234567\",\"email\":\"desk1@example.test\"}")
			.andExpect(status().isOk());
		var reset = admin.post("/api/v1/platform/shops/" + shop.id() + "/users/" + cashier + "/reset-password", "{}")
			.andExpect(status().isOk());
		assertThat((String) json(reset, "$.temporaryPassword")).hasSize(16);
		long ownerId = ((Number) json(shop.owner().get("/api/v1/shop/context"), "$.user.id")).longValue();
		admin.post("/api/v1/platform/shops/" + shop.id() + "/users/" + ownerId + "/reset-password", "{}")
			.andExpect(status().isOk());
	}

	@Test
	void ownerCanBeChanged() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		long manager = createUser(shop, "MANAGER", "newboss");
		ApiClient admin = platformUser("SUPER_ADMIN");
		admin.post("/api/v1/platform/shops/" + shop.id() + "/owner/change", "{\"userId\":" + manager + "}")
			.andExpect(status().isBadRequest());
		var owner = admin.post("/api/v1/platform/shops/" + shop.id() + "/owner/change",
				"{\"userId\":" + manager + ",\"reason\":\"Shop sold\"}").andExpect(status().isOk());
		assertThat((String) json(owner, "$.username")).isEqualTo("newboss");
		assertThat((Boolean) json(shop.owner().get("/api/v1/shop/context"), "$.user.owner")).isFalse();
	}

	// ------------------------------------------------------------------ support mode

	@Test
	void supportSessionsAreReadOnlyUntilTheOwnerApproves() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient support = platformUser("SUPPORT");
		support.post("/api/v1/platform/support-session", "{\"shopId\":" + shop.id() + ",\"reason\":\"\"}")
			.andExpect(status().isBadRequest());
		support.post("/api/v1/platform/support-session",
				"{\"shopId\":" + shop.id() + ",\"reason\":\"Owner asked for help with import\"}").andExpect(status().isOk());
		support.get("/api/v1/products").andExpect(status().isOk());
		var context = support.get("/api/v1/shop/context").andExpect(status().isOk());
		assertThat((Boolean) json(context, "$.support.writeApproved")).isFalse();
		var blocked = support.post("/api/v1/brands", "{\"name\":\"Support brand\"}").andExpect(status().isForbidden());
		assertThat((String) json(blocked, "$.code")).isEqualTo("support_read_only");
		List<String> ownerBanners = json(shop.owner().get("/api/v1/shop/context"), "$.banners[*].kind");
		assertThat(ownerBanners).contains("SUPPORT");

		support.post("/api/v1/platform/support-session/approve", "{\"code\":\"123456\"}").andExpect(status().isBadRequest());
		String code = json(shop.owner().post("/api/v1/settings/support-code").andExpect(status().isOk()), "$.code");
		support.post("/api/v1/platform/support-session/approve", "{\"code\":\"" + code + "\"}").andExpect(status().isOk());
		Integer brandId = json(support.post("/api/v1/brands", "{\"name\":\"Support brand\"}").andExpect(status().isCreated()),
				"$.id");
		Map<String, Object> audit = TestDatabase.superuser().queryForMap(
				"select actor_type, support_session_id from audit_log where shop_id = ? and entity_type = 'brand' and entity_id = ?",
				shop.id(), brandId);
		assertThat(audit.get("actor_type")).isEqualTo("PLATFORM");
		assertThat(audit.get("support_session_id")).isNotNull();
		// The code is used up.
		support.post("/api/v1/platform/support-session/approve", "{\"code\":\"" + code + "\"}").andExpect(status().isBadRequest());

		support.delete("/api/v1/platform/support-session").andExpect(status().isNoContent());
		support.get("/api/v1/products").andExpect(status().isForbidden());
	}

	// ------------------------------------------------------------------ overview, types, plans, users, settings, audit

	@Test
	void overviewTypesPlansSettingsUsersAndAudit() throws Exception {
		newShop("medical", "business");
		ApiClient admin = platformUser("SUPER_ADMIN");
		var overview = admin.get("/api/v1/platform/overview").andExpect(status().isOk());
		assertThat(((Number) json(overview, "$.totalShops")).longValue()).isPositive();
		assertThat((List<?>) json(overview, "$.byType")).hasSize(11);
		assertThat((List<?>) json(admin.get("/api/v1/platform/shop-types"), "$")).hasSize(11);
		List<String> prices = json(admin.get("/api/v1/platform/plans"), "$[*].priceMonth");
		assertThat(prices).containsExactly("2500.00", "5500.00", "12000.00");
		assertThat((List<?>) json(admin.get("/api/v1/platform/shops?type=medical"), "$.items")).isNotEmpty();

		admin.patch("/api/v1/platform/settings", "{\"trial_days\":\"7\"}").andExpect(status().isOk());
		assertThat((String) json(admin.get("/api/v1/platform/settings"), "$.trial_days")).isEqualTo("7");
		admin.patch("/api/v1/platform/settings", "{\"trial_days\":\"seven\"}").andExpect(status().isBadRequest());
		admin.patch("/api/v1/platform/settings", "{\"unknown\":\"x\"}").andExpect(status().isBadRequest());
		admin.patch("/api/v1/platform/settings", "{\"trial_days\":\"14\"}").andExpect(status().isOk());

		var created = admin.post("/api/v1/platform/users",
				"{\"email\":\"" + unique("s") + "@selectcode.test\",\"name\":\"Support person\",\"role\":\"SUPPORT\"}")
			.andExpect(status().isCreated());
		assertThat((String) json(created, "$.temporaryPassword")).hasSize(16);
		Integer userId = json(created, "$.user.id");
		admin.patch("/api/v1/platform/users/" + userId, "{\"status\":\"INACTIVE\"}").andExpect(status().isOk());
		admin.post("/api/v1/platform/users/" + userId + "/reset").andExpect(status().isOk());

		List<String> actions = json(admin.get("/api/v1/platform/audit?size=200"), "$.items[*].action");
		assertThat(actions).contains("SHOP_CREATED", "PLATFORM_USER_CREATED", "PLATFORM_SETTINGS_CHANGED");
	}
}
