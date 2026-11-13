package com.selectcode.pos.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.iam.app.ApprovalService;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.security.Totp;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Shop sign-in, accounts, users and roles (ACC-01..10, ONB-01; QA TC-ACC-*). */
@SuppressWarnings("unchecked")
class AccountIT extends IntegrationTest {

	@Autowired
	ApprovalService approvals;

	@Test
	void wrongPasswordsLockTheAccountAndEverythingIsInLoginHistory() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		createUser(shop, "CASHIER", "ahmed");
		ApiClient c = client();
		for (int i = 0; i < 5; i++) {
			var wrong = c.login(shop.code(), "ahmed", "Wrong-password-" + i).andExpect(status().isUnauthorized());
			assertThat((String) json(wrong, "$.code")).isEqualTo("invalid_credentials");
		}
		var locked = c.login(shop.code(), "ahmed", PASSWORD).andExpect(status().isLocked());
		assertThat((String) json(locked, "$.code")).isEqualTo("account_locked");
		c.login(shop.code(), "nobody", PASSWORD).andExpect(status().isUnauthorized());
		c.login("no-such-shop", "ahmed", PASSWORD).andExpect(status().isUnauthorized());

		List<String> results = json(shop.owner().get("/api/v1/login-events?size=50"), "$.items[*].result");
		assertThat(results).contains("INVALID", "BLOCKED", "SUCCESS");

		// The owner resets the password, which also unlocks the account.
		Long userId = TestDatabase.superuser().queryForObject("select id from app_user where shop_id = ? and username = 'ahmed'",
				Long.class, shop.id());
		String temporary = json(shop.owner().post("/api/v1/users/" + userId + "/reset-password", "{}").andExpect(status().isOk()),
				"$.temporaryPassword");
		ApiClient again = client();
		again.login(shop.code(), "ahmed", temporary).andExpect(status().isOk());
		// A temporary password must be changed before anything else.
		var forced = again.get("/api/v1/products").andExpect(status().isForbidden());
		assertThat((String) json(forced, "$.code")).isEqualTo("password_change_required");
		again.post("/api/v1/auth/change-password",
				"{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"Shop-Counter-77\"}").andExpect(status().isNoContent());
		again.get("/api/v1/products").andExpect(status().isOk());
	}

	@Test
	void passwordResetByEmailLink() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		outbox.clear();
		client().post("/api/v1/auth/password-reset/request", "{\"shopCode\":\"" + shop.code() + "\",\"username\":\"owner\"}")
			.andExpect(status().isAccepted());
		client().post("/api/v1/auth/password-reset/request", "{\"shopCode\":\"" + shop.code() + "\",\"username\":\"ghost\"}")
			.andExpect(status().isAccepted());
		assertThat(outbox.entries()).hasSize(1);
		Matcher m = Pattern.compile("token=([^\\s]+)").matcher(outbox.entries().getFirst().message().body());
		assertThat(m.find()).isTrue();
		ApiClient c = client();
		c.post("/api/v1/auth/password-reset/confirm", "{\"token\":\"" + m.group(1) + "\",\"newPassword\":\"Brand-New-Pass-9\"}")
			.andExpect(status().isNoContent());
		c.login(shop.code(), "owner", "Brand-New-Pass-9").andExpect(status().isOk());
		// The old session of the owner was ended by the reset.
		shop.owner().get("/api/v1/shop/context").andExpect(status().isUnauthorized());
	}

	@Test
	void passwordRulesAreEnforced() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		shop.owner().post("/api/v1/auth/change-password", "{\"currentPassword\":\"wrong\",\"newPassword\":\"Good-Pass-1234\"}")
			.andExpect(status().isBadRequest());
		var common = shop.owner().post("/api/v1/auth/change-password",
				"{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"password123\"}").andExpect(status().isBadRequest());
		assertThat((String) json(common, "$.errors[0].code")).isEqualTo("too_common");
		shop.owner().post("/api/v1/auth/change-password",
				"{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"owner-is-me-2026\"}").andExpect(status().isBadRequest());
	}

	@Test
	void authenticatorAppCanBeTurnedOnAndIsThenRequired() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		String secret = json(shop.owner().post("/api/v1/auth/totp/start").andExpect(status().isOk()), "$.secret");
		shop.owner().post("/api/v1/auth/totp/confirm", "{\"code\":\"000000\"}").andExpect(status().isBadRequest());
		shop.owner().post("/api/v1/auth/totp/confirm", "{\"code\":\"" + Totp.codeAt(secret, Instant.now()) + "\"}")
			.andExpect(status().isNoContent());
		var required = client().login(shop.code(), "owner", PASSWORD).andExpect(status().isUnauthorized());
		assertThat((String) json(required, "$.code")).isEqualTo("totp_required");
		ApiClient c = client();
		c.post("/api/v1/auth/login", """
				{"shopCode":"%s","username":"owner","password":"%s","totpCode":"%s"}"""
			.formatted(shop.code(), PASSWORD, Totp.codeAt(secret, Instant.now()))).andExpect(status().isOk());
		c.post("/api/v1/auth/totp/disable", "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isNoContent());
		client().login(shop.code(), "owner", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void usersRolesAndBranchLimits() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		List<Integer> branches = json(o.get("/api/v1/branches"), "$[*].id");
		var roles = o.get("/api/v1/roles").andExpect(status().isOk());
		List<String> keys = json(roles, "$[*].systemKey");
		assertThat(keys).first().isEqualTo("OWNER");
		Integer cashierRole = ((List<Integer>) json(roles, "$[?(@.systemKey=='CASHIER')].id")).getFirst();
		Integer ownerRole = ((List<Integer>) json(roles, "$[?(@.systemKey=='OWNER')].id")).getFirst();

		o.post("/api/v1/users", "{\"username\":\"x\",\"displayName\":\"X\",\"roleId\":" + cashierRole + "}")
			.andExpect(status().isBadRequest());
		o.post("/api/v1/users", "{\"username\":\"second.owner\",\"displayName\":\"X\",\"roleId\":" + ownerRole + "}")
			.andExpect(status().isBadRequest());
		var created = o.post("/api/v1/users", """
				{"username":"Bilal","displayName":"Bilal","roleId":%d,"allBranches":false,"branchIds":[%d]}"""
			.formatted(cashierRole, branches.get(1))).andExpect(status().isCreated());
		String temporary = json(created, "$.temporaryPassword");
		assertThat((String) json(created, "$.user.username")).isEqualTo("bilal");
		o.post("/api/v1/users", "{\"username\":\"bilal\",\"displayName\":\"B\",\"roleId\":" + cashierRole + "}")
			.andExpect(status().isConflict());

		ApiClient bilal = client();
		bilal.login(shop.code(), "bilal", temporary).andExpect(status().isOk());
		bilal.post("/api/v1/auth/change-password",
				"{\"currentPassword\":\"" + temporary + "\",\"newPassword\":\"Night-Shift-2026\"}").andExpect(status().isNoContent());
		var context = bilal.get("/api/v1/shop/context").andExpect(status().isOk());
		List<Integer> mine = json(context, "$.branches[*].id");
		assertThat(mine).containsExactly(branches.get(1));
		List<String> permissions = json(context, "$.permissions");
		assertThat(permissions).contains(Permissions.POS_SELL).doesNotContain(Permissions.COST_VIEW);

		Integer bilalId = json(created, "$.user.id");
		// Role change takes effect on the next request.
		Integer managerRole = ((List<Integer>) json(roles, "$[?(@.systemKey=='MANAGER')].id")).getFirst();
		o.patch("/api/v1/users/" + bilalId, "{\"roleId\":" + managerRole + "}").andExpect(status().isOk());
		List<String> now = json(bilal.get("/api/v1/shop/context"), "$.permissions");
		assertThat(now).contains(Permissions.COST_VIEW);
		// Deactivation signs the user out at once.
		o.post("/api/v1/users/" + bilalId + "/deactivate").andExpect(status().isOk());
		bilal.get("/api/v1/shop/context").andExpect(status().isUnauthorized());
		client().login(shop.code(), "bilal", "Night-Shift-2026").andExpect(status().isUnauthorized());
		// The owner cannot be deactivated, and cannot deactivate themselves.
		o.post("/api/v1/users/" + shop.ownerId() + "/deactivate").andExpect(status().isUnprocessableContent());

		List<String> actions = json(o.get("/api/v1/audit?size=100"), "$.items[*].action");
		assertThat(actions).contains("USER_CREATED", "USER_UPDATED", "USER_DEACTIVATED");
		assertThat((List<?>) json(o.get("/api/v1/permissions"), "$")).isNotEmpty();
	}

	@Test
	void setupLinkForNewUserByEmail() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		Long roleId = TestDatabase.superuser().queryForObject("select id from role where shop_id = ? and system_key = 'ACCOUNTANT'",
				Long.class, shop.id());
		outbox.clear();
		shop.owner().post("/api/v1/users", """
				{"username":"zara","displayName":"Zara","roleId":%d,"sendSetupLink":true}""".formatted(roleId))
			.andExpect(status().isBadRequest());
		var created = shop.owner().post("/api/v1/users", """
				{"username":"zara","displayName":"Zara","email":"zara@shop.test","roleId":%d,"sendSetupLink":true}"""
			.formatted(roleId)).andExpect(status().isCreated());
		assertThat((Boolean) json(created, "$.setupLinkSent")).isTrue();
		assertThat((Object) json(created, "$.temporaryPassword")).isNull();
		Matcher m = Pattern.compile("token=([^\\s]+)").matcher(outbox.entries().getFirst().message().body());
		assertThat(m.find()).isTrue();
		client().post("/api/v1/auth/setup", "{\"token\":\"" + m.group(1) + "\",\"newPassword\":\"Ledger-Books-31\"}")
			.andExpect(status().isNoContent());
		client().login(shop.code(), "zara", "Ledger-Books-31").andExpect(status().isOk());
	}

	@Test
	void pinApprovalChecksPinPermissionAndLocks() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient manager = userWithRole(shop, "MANAGER", "manager1");
		manager.post("/api/v1/auth/pin", "{\"password\":\"wrong\",\"pin\":\"4821\"}").andExpect(status().isBadRequest());
		manager.post("/api/v1/auth/pin", "{\"password\":\"" + PASSWORD + "\",\"pin\":\"1111\"}").andExpect(status().isBadRequest());
		manager.post("/api/v1/auth/pin", "{\"password\":\"" + PASSWORD + "\",\"pin\":\"4821\"}").andExpect(status().isNoContent());
		ApiClient cashier = userWithRole(shop, "CASHIER", "cashier1");
		cashier.post("/api/v1/auth/pin", "{\"password\":\"" + PASSWORD + "\",\"pin\":\"7391\"}").andExpect(status().isNoContent());

		TenantContext.runAsShop(shop.id(), () -> {
			var approval = approvals.approve("manager1", "4821", Permissions.POS_DISCOUNT, "Discount 15%");
			assertThat(approval.approverName()).isEqualTo("User manager1");
			assertThat(code(() -> approvals.approve("cashier1", "7391", Permissions.POS_DISCOUNT, "x")))
				.isEqualTo("approver_not_allowed");
			for (int i = 0; i < 5; i++) {
				assertThat(code(() -> approvals.approve("manager1", "0000", Permissions.POS_DISCOUNT, "x"))).isEqualTo("pin_wrong");
			}
			assertThat(code(() -> approvals.approve("manager1", "4821", Permissions.POS_DISCOUNT, "x"))).isEqualTo("pin_locked");
		});
	}

	@Test
	void sessionTimeoutComesFromShopSettings() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		shop.owner().patch("/api/v1/settings", "{\"sessionIdleMinutes\":25}").andExpect(status().isOk());
		var login = client().login(shop.code(), "owner", PASSWORD).andExpect(status().isOk());
		assertThat((Integer) json(login, "$.idleMinutes")).isEqualTo(25);
	}

	private static String code(Runnable action) {
		try {
			action.run();
			return null;
		}
		catch (ApiException e) {
			return e.code();
		}
	}

	@org.junit.jupiter.api.Test
	void lockScreenUnlocksWithOwnPinAndLocksAfterFiveWrong() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		var owner = shop.owner();
		owner.post("/api/v1/auth/unlock", "{\"pin\":\"1234\"}")
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableContent());
		owner.post("/api/v1/auth/pin", "{\"pin\":\"5829\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
		owner.post("/api/v1/auth/unlock", "{\"pin\":\"5829\"}")
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
		for (int i = 0; i < 5; i++) {
			owner.post("/api/v1/auth/unlock", "{\"pin\":\"0000\"}")
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
		}
		owner.post("/api/v1/auth/unlock", "{\"pin\":\"5829\"}")
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("pin_locked"));
	}
}
