package com.selectcode.pos.shared.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.Trading;
import org.junit.jupiter.api.Test;

/**
 * What a dishonest or careless user might try against the Stage 4b modules (QA pass 2026-11-13): prices changed
 * through side doors, points pushed below zero, documents used twice, odd input. Every case must be refused with a
 * clear 4xx answer, never a 500 and never a silent success.
 */
class AbuseIT extends IntegrationTest {

	@Test
	void technicianCannotPriceRepairPartsBelowTheListPrice() throws Exception {
		TestShop shop = newShop("mobile", "enterprise");
		Trading t = new Trading(shop.owner());
		long part = t.product("Battery", "3000", "1500");
		t.receive(t.supplier("S"), t.defaultBranch(), part, "5", "1500");
		Integer job = Trading.read(shop.owner().post("/api/v1/repairs", """
				{"customerName":"Ali","customerPhone":"03001112223","device":"Phone","problem":"Battery"}""")
			.andExpect(status().isCreated()), "$.id");
		ApiClient tech = userWithRole(shop, "TECHNICIAN", unique("tech"));
		tech.post("/api/v1/repairs/" + job + "/parts", "{\"productId\":" + part + ",\"qty\":\"1\",\"price\":\"1\"}")
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("price_change_not_allowed"));
		// At the list price it is fine.
		tech.post("/api/v1/repairs/" + job + "/parts", "{\"productId\":" + part + ",\"qty\":\"1\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.partsTotal").value("3000.00"));
	}

	@Test
	void challanPricesNeedThePriceChangePermission() throws Exception {
		TestShop shop = newShop("hardware", "enterprise");
		Trading t = new Trading(shop.owner());
		long cement = t.product("Cement", "1450", "1300");
		t.receive(t.supplier("S"), t.defaultBranch(), cement, "50", "1300");
		long userId = createUser(shop, "STOCK_KEEPER", unique("sk"));
		com.selectcode.pos.support.TestDatabase.superuser().update("""
				update role set permissions = permissions || array['delivery.manage']
				where id = (select role_id from app_user where id = ?)""", userId);
		String username = com.selectcode.pos.support.TestDatabase.superuser()
			.queryForObject("select username from app_user where id = ?", String.class, userId);
		ApiClient keeper = client();
		keeper.login(shop.code(), username, PASSWORD).andExpect(status().isOk());
		keeper.post("/api/v1/challans", """
				{"customerName":"Friend","lines":[{"productId":%d,"qty":"10","price":"100"}]}""".formatted(cement))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("price_change_not_allowed"));
		keeper.post("/api/v1/challans", """
				{"customerName":"Friend","lines":[{"productId":%d,"qty":"10"}]}""".formatted(cement))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.total").value("14500.00"));
	}

	@Test
	void pointsCannotGoBelowZeroOrBeUsedInFractions() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		Trading t = new Trading(shop.owner());
		long session = t.openTill(t.defaultBranch(), "5000");
		long item = t.product("Rice", "1000", "0", "SERVICE", null);
		long customer = t.customer("Sana", "0300-1110001", "0");
		shop.owner().post("/api/v1/customers/" + customer + "/loyalty/adjust", "{\"points\":150,\"note\":\"start\"}")
			.andExpect(status().isOk());
		shop.owner().post("/api/v1/customers/" + customer + "/loyalty/adjust", "{\"points\":-500,\"note\":\"too many\"}")
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("points_short"));
		shop.owner().get("/api/v1/customers/" + customer + "/loyalty").andExpect(jsonPath("$.points").value(150));
		// Settings: Rs 2 per point → Rs 201 is not a whole number of points.
		shop.owner().patch("/api/v1/settings", "{\"loyaltyPointValue\":\"2\",\"loyaltyMinRedeem\":10}")
			.andExpect(status().isOk());
		shop.owner().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"POINTS","amount":"201"},{"method":"CASH","amount":"799"}]}"""
			.formatted(session, customer, item), Trading.key()).andExpect(status().isBadRequest());
		// Points without a customer.
		shop.owner().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"POINTS","amount":"200"},{"method":"CASH","amount":"800"}]}"""
			.formatted(session, item), Trading.key()).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("points_no_customer"));
		// Another customer's points cannot be used by putting their name on the bill: points come from the bill's
		// customer only, so a walk-in bill cannot spend anything.
	}

	@Test
	void documentsCannotBeUsedTwice() throws Exception {
		TestShop shop = newShop("hardware", "enterprise");
		Trading t = new Trading(shop.owner());
		long session = t.openTill(t.defaultBranch(), "5000");
		long pipe = t.product("Pipe", "500", "300");
		t.receive(t.supplier("S"), t.defaultBranch(), pipe, "20", "300");
		Integer challan = Trading.read(shop.owner().post("/api/v1/challans", """
				{"customerName":"Site","lines":[{"productId":%d,"qty":"2"}]}""".formatted(pipe)).andExpect(status().isCreated()),
				"$.id");
		String body = "{\"tillSessionId\":%d,\"payments\":[{\"method\":\"CASH\",\"amount\":\"1000\"}]}".formatted(session);
		shop.owner().postWithKey("/api/v1/challans/" + challan + "/convert", body, Trading.key()).andExpect(status().isOk());
		// A second bill with a new key is refused; the same key returns the first answer.
		shop.owner().postWithKey("/api/v1/challans/" + challan + "/convert", body, Trading.key())
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("challan_closed"));
		shop.owner().post("/api/v1/challans/" + challan + "/cancel", "{\"reason\":\"x\"}")
			.andExpect(status().isUnprocessableContent());
		// Stock: 20 − 2 sold, the challan's own movement reversed.
		org.assertj.core.api.Assertions.assertThat(t.onHand(pipe, t.defaultBranch())).isEqualTo("18.000");
	}

	@Test
	void oddInputIsRefusedCleanly() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		shopAdmin.setModules(shop.id(), QaSecurityIT.ALL_MODULES);
		ApiClient o = shop.owner();
		// Offers: nonsense values.
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"PERCENT\",\"value\":\"150\"}").andExpect(status().isBadRequest());
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"BUY_GET\",\"buyQty\":\"1.5\",\"getQty\":\"1\"}")
			.andExpect(status().isBadRequest());
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"PERCENT\",\"value\":\"5\",\"days\":[9]}")
			.andExpect(status().isBadRequest());
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"PERCENT\",\"value\":\"5\",\"fromTime\":\"25:99\",\"toTime\":\"10:00\"}")
			.andExpect(status().isBadRequest());
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"PERCENT\",\"value\":\"5\",\"couponCode\":\"<script>\"}")
			.andExpect(status().isBadRequest());
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"DROP_TABLE\",\"value\":\"5\"}").andExpect(status().isBadRequest());
		o.post("/api/v1/promotions", "{\"name\":\"x\",\"kind\":\"PERCENT\",\"value\":\"5\",\"scope\":\"PRODUCTS\",\"productIds\":[999999]}")
			.andExpect(status().isBadRequest());
		// Scale code that is not digits.
		o.post("/api/v1/products", "{\"name\":\"Beef\",\"price\":\"1200\",\"unitId\":%d,\"scaleCode\":\"12a\"}"
			.formatted(new Trading(o).firstUnit())).andExpect(status().isBadRequest());
		// Coupon lookup with odd characters never reaches SQL as text.
		o.get("/api/v1/pos/coupons/' or 1=1 --").andExpect(status().isUnprocessableContent());
		// Searches with SQL text are only search words.
		o.get("/api/v1/repairs?q=' or '1'='1").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
		o.get("/api/v1/challans?q=%27%3B%20drop%20table%20sale%3B--").andExpect(status().isOk());
		// Installment plan on a sale that does not exist / a huge number of months.
		o.post("/api/v1/installment-plans", "{\"saleId\":99999999,\"months\":2}").andExpect(status().isNotFound());
	}
}
