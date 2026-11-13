package com.selectcode.pos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.platform.app.BillingService;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Subscriptions (BIL-05..09, TC-BIL-01..05): invoices before the due date, reminders once each, Grace then Read-only
 * on schedule, 402 on every write while read-only, payment back to Active, upgrades now and downgrades next cycle,
 * module switches enforced by the API (TC-SEC-02), onboarding checklist and terms.
 */
class BillingIT extends IntegrationTest {

	@Autowired
	BillingService billing;

	static LocalDate today() {
		return LocalDate.now(ZoneId.of("Asia/Karachi"));
	}

	@Test
	void overdueShopGoesGraceThenReadOnlyAndPaymentBringsItBack() throws Exception {
		TestShop shop = newShop("general", "business", 14);
		ApiClient platform = platformUser("SUPER_ADMIN");
		// The trial ended 3 days ago and nothing was paid.
		TestDatabase.superuser().update("update subscription set next_due_on = ? where shop_id = ?",
				java.sql.Date.valueOf(today().minusDays(3)), shop.id());
		TestDatabase.superuser().update("update shop set trial_ends_on = ? where id = ?",
				java.sql.Date.valueOf(today().minusDays(3)), shop.id());
		var first = billing.runDaily();
		assertThat(first.invoicesMade()).isGreaterThanOrEqualTo(1);
		assertThat(shopStatus(shop)).isEqualTo("GRACE");
		Integer invoiceId = Trading.read(platform.get("/api/v1/platform/subscription-invoices?shopId=" + shop.id()),
				"$[0].id");
		platform.get("/api/v1/platform/subscription-invoices/" + invoiceId)
			.andExpect(jsonPath("$.total").value("5500.00"))
			.andExpect(jsonPath("$.status").value("OPEN"));
		// Overdue reminder sent once, however often the job runs (TC-BIL-05).
		billing.runDaily();
		assertThat(TestDatabase.superuser().queryForObject(
				"select count(*) from subscription_reminder where shop_id = ? and kind = 'OVERDUE'", Integer.class, shop.id()))
			.isEqualTo(1);
		// Owner still works in Grace.
		shop.owner().post("/api/v1/brands", "{\"name\":\"Grace brand\"}").andExpect(status().isCreated());

		// 8 days overdue: past the 7 grace days → Read-only (TC-BIL-01).
		TestDatabase.superuser().update("update subscription_invoice set due_on = ? where id = ?",
				java.sql.Date.valueOf(today().minusDays(8)), invoiceId);
		billing.runDaily();
		assertThat(shopStatus(shop)).isEqualTo("READ_ONLY");
		// Every write is refused with 402; reads still work.
		shop.owner().get("/api/v1/products").andExpect(status().isOk());
		for (String[] call : new String[][] { { "POST", "/api/v1/brands" }, { "POST", "/api/v1/sales" },
				{ "POST", "/api/v1/grns" }, { "POST", "/api/v1/adjustments" }, { "PATCH", "/api/v1/settings" },
				{ "POST", "/api/v1/customers" }, { "POST", "/api/v1/expenses" }, { "POST", "/api/v1/till-sessions" } }) {
			var result = "PATCH".equals(call[0]) ? shop.owner().patch(call[1], "{}") : shop.owner().post(call[1], "{}");
			result.andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("subscription_read_only"));
		}
		// SelectCode records the payment: Active again, next due moves a month.
		platform.post("/api/v1/platform/subscription-invoices/" + invoiceId + "/payments",
				"{\"amount\":\"5500\",\"method\":\"BANK\",\"reference\":\"HBL-123\"}").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PAID"));
		assertThat(shopStatus(shop)).isEqualTo("ACTIVE");
		shop.owner().post("/api/v1/brands", "{\"name\":\"Back brand\"}").andExpect(status().isCreated());
		LocalDate nextDue = TestDatabase.superuser().queryForObject("select next_due_on from subscription where shop_id = ?",
				LocalDate.class, shop.id());
		assertThat(nextDue).isAfter(today());
		assertThat(TestDatabase.superuser().queryForObject("""
				select count(*) from notification where shop_id = ? and topic in ('subscription_due', 'subscription_receipt')""",
				Integer.class, shop.id())).isGreaterThan(0);
	}

	@Test
	void remindersSevenThreeOneDaysBefore() throws Exception {
		TestShop shop = newShop("general", "starter", 0);
		TestDatabase.superuser().update("update subscription set next_due_on = ? where shop_id = ?",
				java.sql.Date.valueOf(today().plusDays(7)), shop.id());
		billing.runDaily();
		assertThat(reminders(shop)).containsExactly("D7");
		Long invoice = TestDatabase.superuser().queryForObject(
				"select id from subscription_invoice where shop_id = ? and kind = 'RENEWAL'", Long.class, shop.id());
		TestDatabase.superuser().update("update subscription_invoice set due_on = ? where id = ?",
				java.sql.Date.valueOf(today().plusDays(3)), invoice);
		billing.runDaily();
		billing.runDaily();
		TestDatabase.superuser().update("update subscription_invoice set due_on = ? where id = ?",
				java.sql.Date.valueOf(today().plusDays(1)), invoice);
		billing.runDaily();
		assertThat(reminders(shop)).containsExactlyInAnyOrder("D7", "D3", "D1");
		assertThat(shopStatus(shop)).isEqualTo("ACTIVE");
	}

	@Test
	void upgradeAppliesNowWithProRataDowngradeWaits() throws Exception {
		TestShop shop = newShop("general", "starter", 0);
		ApiClient platform = platformUser("SUPER_ADMIN");
		TestDatabase.superuser().update("update subscription set next_due_on = ? where shop_id = ?",
				java.sql.Date.valueOf(today().plusDays(15)), shop.id());
		platform.post("/api/v1/platform/shops/" + shop.id() + "/plan-change", "{\"planKey\":\"business\"}")
			.andExpect(jsonPath("$.effect").value("UPGRADED"))
			.andExpect(jsonPath("$.invoice.kind").value("UPGRADE"));
		assertThat(plan(shop)).isEqualTo("business");
		// The 3rd user is now allowed (Business allows 8).
		createUser(shop, "CASHIER", unique("u"));
		shop.owner().post("/api/v1/users", "{\"username\":\"" + unique("x") + "\",\"displayName\":\"X\",\"roleId\":"
				+ TestDatabase.superuser().queryForObject("select id from role where shop_id = ? and system_key = 'CASHIER'",
						Long.class, shop.id())
				+ ",\"password\":\"Long-Enough-Pass-9\"}").andExpect(status().isCreated());
		// Downgrade is booked for the next cycle; nothing changes now and nothing is deleted.
		platform.post("/api/v1/platform/shops/" + shop.id() + "/plan-change", "{\"planKey\":\"starter\"}")
			.andExpect(jsonPath("$.effect").value("DOWNGRADE_BOOKED"));
		assertThat(plan(shop)).isEqualTo("business");
		TestDatabase.superuser().update("update subscription set next_due_on = ? where shop_id = ?",
				java.sql.Date.valueOf(today()), shop.id());
		billing.runDaily();
		assertThat(plan(shop)).isEqualTo("starter");
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from app_user where shop_id = ?",
				Integer.class, shop.id())).isGreaterThan(2);
		// Over the Starter limit of 2 users: no new users (TC-BIL-03).
		shop.owner().post("/api/v1/users", "{\"username\":\"" + unique("y") + "\",\"displayName\":\"Y\",\"roleId\":"
				+ TestDatabase.superuser().queryForObject("select id from role where shop_id = ? and system_key = 'CASHIER'",
						Long.class, shop.id())
				+ ",\"password\":\"Long-Enough-Pass-9\"}").andExpect(status().isPaymentRequired())
			.andExpect(jsonPath("$.code").value("plan_limit"));
	}

	@Test
	void switchedOffModuleEndpointsAnswerModuleDisabled() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient owner = shop.owner();
		// General store: serials, buyback, warranty, quotations and variants are off by default (TC-SEC-02).
		for (String url : new String[] { "/api/v1/serials", "/api/v1/serials/123", "/api/v1/buybacks",
				"/api/v1/warranty-claims", "/api/v1/quotations", "/api/v1/size-sets", "/api/v1/products/1/variants" }) {
			owner.get(url).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("module_disabled"));
		}
		owner.post("/api/v1/buybacks", "{}").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("module_disabled"));
		// Switched on, the same endpoints answer.
		shopAdmin.setModules(shop.id(), java.util.Map.of("quotations", true, "serials", true));
		owner.get("/api/v1/quotations").andExpect(status().isOk());
		owner.get("/api/v1/serials").andExpect(status().isOk());
	}

	@Test
	void onboardingChecklistAndTerms() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient owner = shop.owner();
		owner.get("/api/v1/onboarding").andExpect(jsonPath("$.complete").value(false))
			.andExpect(jsonPath("$.steps[?(@.key=='sale')].done").value(false));
		owner.post("/api/v1/onboarding/printer-tested").andExpect(jsonPath("$.steps[?(@.key=='printer')].done").value(true));
		ApiClient platform = platformUser("SUPER_ADMIN");
		platform.get("/api/v1/platform/shops/" + shop.id() + "/onboarding").andExpect(status().isOk())
			.andExpect(jsonPath("$.steps[?(@.key=='printer')].done").value(true));
		String version = Trading.read(owner.get("/api/v1/terms"), "$.currentVersion");
		owner.get("/api/v1/terms").andExpect(jsonPath("$.required").value(true));
		owner.post("/api/v1/terms/accept", "{\"version\":\"0.0\"}").andExpect(status().isUnprocessableContent());
		owner.post("/api/v1/terms/accept", "{\"version\":\"" + version + "\"}").andExpect(jsonPath("$.required").value(false));
		owner.get("/api/v1/subscription/invoices").andExpect(status().isOk());
	}

	@Test
	void closedShopIsPurgedAfterItsDaysButBillingKept() throws Exception {
		TestShop shop = newShop("general", "starter", 0);
		ApiClient platform = platformUser("SUPER_ADMIN");
		billing.runDaily();
		platform.post("/api/v1/platform/shops/" + shop.id() + "/close",
				"{\"confirmCode\":\"" + shop.code() + "\",\"reason\":\"Client left\"}").andExpect(status().isOk());
		TestDatabase.superuser().update("update shop set delete_after = ? where id = ?",
				java.sql.Date.valueOf(today().minusDays(1)), shop.id());
		assertThat(billing.retention()).isGreaterThanOrEqualTo(1);
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from product where shop_id = ?", Integer.class,
				shop.id())).isZero();
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from app_user where shop_id = ?", Integer.class,
				shop.id())).isZero();
		assertThat(TestDatabase.superuser().queryForObject("select purged_at is not null from shop where id = ?",
				Boolean.class, shop.id())).isTrue();
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from subscription_invoice where shop_id = ?",
				Integer.class, shop.id())).isGreaterThan(0);
	}

	private static String shopStatus(TestShop shop) {
		return TestDatabase.superuser().queryForObject("select status from shop where id = ?", String.class, shop.id());
	}

	private static String plan(TestShop shop) {
		return TestDatabase.superuser().queryForObject(
				"select p.plan_key from shop s join plan p on p.id = s.plan_id where s.id = ?", String.class, shop.id());
	}

	private static java.util.List<String> reminders(TestShop shop) {
		return TestDatabase.superuser().queryForList("select kind from subscription_reminder where shop_id = ?",
				String.class, shop.id());
	}
}
