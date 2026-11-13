package com.selectcode.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast food / restaurant shop type end to end (Stage 4c): the shop is created with its modules, menu categories and
 * kitchen and rider roles; menu items are made from recipes and take their ingredients out of stock; deals break
 * down into their menu items' ingredients; orders get a token, an order type and a kitchen ticket; the kitchen moves
 * tickets to served; returns and cancels; kitchen staff never see money.
 */
class FastFoodIT extends IntegrationTest {

	record Setup(TestShop shop, ApiClient o, Trading t, long branch, long session, long kg, long piece, long bun,
			long chicken, long cheese, long cola, long zinger, long fries, long deal) {
	}

	Setup setup() throws Exception {
		return setup("starter");
	}

	Setup setup(String plan) throws Exception {
		TestShop shop = newShop("fast_food", plan);
		Trading t = new Trading(shop.owner());
		ApiClient o = shop.owner();
		long branch = t.defaultBranch();
		long session = t.openTill(branch, "5000");
		long kg = t.unitId("kg");
		long piece = t.unitId("piece");
		long supplier = t.supplier("Food supplier");
		long bun = ingredient(o, "Bun", piece, "25");
		long chicken = ingredient(o, "Chicken fillet", kg, "900");
		long cheese = ingredient(o, "Cheese slice", piece, "15");
		long potato = ingredient(o, "Potatoes", kg, "120");
		long cola = ingredient(o, "Cola can", piece, "80");
		o.patch("/api/v1/products/" + cola, "{\"price\":\"120\"}").andExpect(status().isOk());
		o.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"100","unitCost":"25"},
				 {"productId":%d,"qty":"10","unitCost":"900"},{"productId":%d,"qty":"100","unitCost":"15"},
				 {"productId":%d,"qty":"20","unitCost":"120"},{"productId":%d,"qty":"48","unitCost":"80"}]}"""
			.formatted(supplier, branch, bun, chicken, cheese, potato, cola), Trading.key()).andExpect(status().isCreated());
		long zinger = menuItem(o, "Zinger burger", "550", piece);
		o.put("/api/v1/products/" + zinger + "/bundle", """
				{"items":[{"productId":%d,"qty":"1"},{"productId":%d,"qty":"0.15"},{"productId":%d,"qty":"1"}]}"""
			.formatted(bun, chicken, cheese)).andExpect(status().isOk());
		long fries = menuItem(o, "Regular fries", "250", piece);
		o.put("/api/v1/products/" + fries + "/bundle", "{\"items\":[{\"productId\":%d,\"qty\":\"0.2\"}]}".formatted(potato))
			.andExpect(status().isOk());
		long deal = ((Integer) Trading.read(o.post("/api/v1/products", """
				{"name":"Zinger deal","price":"850","unitId":%d,"kind":"BUNDLE"}""".formatted(piece))
			.andExpect(status().isCreated()), "$.id")).longValue();
		o.put("/api/v1/products/" + deal + "/bundle", """
				{"items":[{"productId":%d,"qty":"1"},{"productId":%d,"qty":"1"},{"productId":%d,"qty":"1"}]}"""
			.formatted(zinger, fries, cola)).andExpect(status().isOk());
		return new Setup(shop, o, t, branch, session, kg, piece, bun, chicken, cheese, cola, zinger, fries, deal);
	}

	static long ingredient(ApiClient o, String name, long unit, String cost) throws Exception {
		return ((Integer) Trading.read(o.post("/api/v1/products", """
				{"name":"%s","price":"%s","costPrice":"%s","unitId":%d}""".formatted(name, cost, cost, unit))
			.andExpect(status().isCreated()), "$.id")).longValue();
	}

	static long menuItem(ApiClient o, String name, String price, long unit) throws Exception {
		return ((Integer) Trading.read(o.post("/api/v1/products", """
				{"name":"%s","price":"%s","unitId":%d,"kind":"RECIPE"}""".formatted(name, price, unit))
			.andExpect(status().isCreated()), "$.id")).longValue();
	}

	@Test
	void shopTypeComesWithModulesCategoriesAndRoles() throws Exception {
		TestShop shop = newShop("fast_food", "starter");
		var context = shop.owner().get("/api/v1/shop/context").andExpect(status().isOk());
		List<String> on = Trading.read(context, "$.modules[*].key");
		// Starter: the first-release modules; delivery and offers come with the Business plan.
		assertThat(on).contains("recipes", "kitchen", "bundles", "multi_till").doesNotContain("delivery", "promotions");
		TestShop business = newShop("fast_food", "business");
		List<String> onBusiness = Trading.read(business.owner().get("/api/v1/shop/context"), "$.modules[*].key");
		assertThat(onBusiness).contains("recipes", "kitchen", "bundles", "multi_till", "delivery", "promotions");
		assertThat(TestDatabase.superuser().queryForList("select system_key from role where shop_id = ?", String.class,
				business.id())).contains("KITCHEN", "RIDER");
		List<String> categories = Trading.read(shop.owner().get("/api/v1/categories"), "$[*].name");
		assertThat(categories).contains("Burgers", "Pizza", "Deals", "Drinks", "Ingredients");
		List<String> roles = TestDatabase.superuser().queryForList(
				"select system_key from role where shop_id = ? order by system_key", String.class, shop.id());
		assertThat(roles).contains("KITCHEN", "CASHIER", "MANAGER");
		assertThat(TestDatabase.superuser().queryForList(
				"select unnest(permissions) from role where shop_id = ? and system_key = 'KITCHEN'", String.class, shop.id()))
			.containsExactly("kitchen.view");
	}

	@Test
	void menuItemsTakeTheirIngredientsAndDealsBreakDown() throws Exception {
		Setup s = setup();
		// Menu items are never stocked themselves and only take ordinary stock items as ingredients.
		s.o().put("/api/v1/products/" + s.zinger() + "/bundle", "{\"items\":[{\"productId\":%d,\"qty\":\"1\"}]}"
			.formatted(s.fries())).andExpect(status().isUnprocessableContent());
		s.o().get("/api/v1/pos/catalog").andExpect(jsonPath("$.items[?(@.id==" + s.zinger() + ")].stock").value(
				org.hamcrest.Matchers.contains((Object) null)));
		// 2 Zinger burgers + 1 deal, dine-in at table 5, with a note.
		Integer sale = Trading.read(s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"orderType":"DINE_IN","tableNo":"5",
				 "lines":[{"productId":%d,"qty":"2","note":"No cheese on one"},{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"1950"}]}"""
			.formatted(s.session(), s.zinger(), s.deal()), Trading.key()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("1950.00"))
			.andExpect(jsonPath("$.orderType").value("DINE_IN"))
			.andExpect(jsonPath("$.tableNo").value("5"))
			.andExpect(jsonPath("$.tokenNo").value(1))
			.andExpect(jsonPath("$.lines[0].note").value("No cheese on one")), "$.id");
		// Ingredients out: 3 burgers (2 + the deal's) → 3 buns, 0.45 kg chicken, 3 cheese; the deal's fries and cola.
		assertThat(s.t().onHand(s.bun(), s.branch())).isEqualTo("97.000");
		assertThat(s.t().onHand(s.chicken(), s.branch())).isEqualTo("9.550");
		assertThat(s.t().onHand(s.cheese(), s.branch())).isEqualTo("97.000");
		assertThat(s.t().onHand(s.cola(), s.branch())).isEqualTo("47.000");
		// Cost is what the ingredients cost: burger 25 + 135 + 15 = 175; deal 175 + 24 fries + 80 cola = 279.
		s.o().get("/api/v1/sales/" + sale).andExpect(jsonPath("$.costTotal").value("629.00"));
		// The next order gets token 2; dine-in needs a table.
		s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"orderType":"DINE_IN","lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"550"}]}""".formatted(s.session(), s.zinger()), Trading.key())
			.andExpect(status().isBadRequest());
		s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"orderType":"TAKEAWAY","lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"550"}]}""".formatted(s.session(), s.zinger()), Trading.key())
			.andExpect(jsonPath("$.tokenNo").value(2));
	}

	@Test
	void kitchenSeesOrdersAndMovesThemToServedButNeverSeesMoney() throws Exception {
		Setup s = setup();
		Integer sale = Trading.read(s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"orderType":"TAKEAWAY","lines":[{"productId":%d,"qty":"1","note":"Extra spicy"},
				 {"productId":%d,"qty":"1"},{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"1640"}]}"""
			.formatted(s.session(), s.deal(), s.zinger(), s.cola()), Trading.key()).andExpect(status().isCreated()), "$.id");
		// A drinks-only order goes to no kitchen.
		s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"120"}]}"""
			.formatted(s.session(), s.cola()), Trading.key()).andExpect(status().isCreated());
		ApiClient chef = userWithRole(s.shop(), "KITCHEN", unique("chef"));
		var open = chef.get("/api/v1/kitchen/tickets").andExpect(status().isOk());
		List<Object> tickets = Trading.read(open, "$");
		assertThat(tickets).hasSize(1);
		chef.get("/api/v1/kitchen/tickets").andExpect(jsonPath("$[0].saleId").value(sale))
			.andExpect(jsonPath("$[0].status").value("NEW"))
			.andExpect(jsonPath("$[0].items.length()").value(2))
			.andExpect(jsonPath("$[0].items[0].name").value("Zinger deal"))
			.andExpect(jsonPath("$[0].items[0].note").value("Extra spicy"))
			.andExpect(jsonPath("$[0].items[0].contains.length()").value(3));
		String body = chef.get("/api/v1/kitchen/tickets").andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("total").doesNotContain("price").doesNotContain("1640");
		// Kitchen staff cannot see sales, products' cost, the till or reports.
		chef.get("/api/v1/sales").andExpect(status().isForbidden());
		chef.get("/api/v1/sales/" + sale).andExpect(status().isForbidden());
		chef.get("/api/v1/reports").andExpect(status().isForbidden());
		chef.get("/api/v1/till-sessions/current").andExpect(status().isForbidden());
		Integer ticket = Trading.read(chef.get("/api/v1/kitchen/tickets"), "$[0].id");
		chef.post("/api/v1/kitchen/tickets/" + ticket + "/status", "{\"status\":\"PREPARING\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PREPARING"));
		chef.post("/api/v1/kitchen/tickets/" + ticket + "/status", "{\"status\":\"READY\"}")
			.andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.readyAt").exists());
		// Never backwards.
		chef.post("/api/v1/kitchen/tickets/" + ticket + "/status", "{\"status\":\"PREPARING\"}")
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ticket_backwards"));
		// The cashier hands it over.
		ApiClient cashier = userWithRole(s.shop(), "CASHIER", unique("cash"));
		cashier.post("/api/v1/kitchen/tickets/" + ticket + "/status", "{\"status\":\"SERVED\"}")
			.andExpect(jsonPath("$.status").value("SERVED"));
		chef.get("/api/v1/kitchen/tickets").andExpect(jsonPath("$.length()").value(0));
		chef.get("/api/v1/kitchen/tickets?status=SERVED").andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void returnedFoodIsALossAndACancelledOrderGivesTheIngredientsBack() throws Exception {
		Setup s = setup();
		Integer sale = Trading.read(s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"1100"}]}"""
			.formatted(s.session(), s.zinger()), Trading.key()).andExpect(status().isCreated()), "$.id");
		Integer line = Trading.read(s.o().get("/api/v1/sales/" + sale), "$.lines[0].id");
		s.o().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Cold","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.costDamaged").value("175.00"));
		assertThat(s.t().onHand(s.bun(), s.branch())).isEqualTo("98.000");
		// Cancel: the order was not made, so the ingredients come back and the ticket is cancelled.
		Integer second = Trading.read(s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"850"}]}"""
			.formatted(s.session(), s.deal()), Trading.key()).andExpect(status().isCreated()), "$.id");
		assertThat(s.t().onHand(s.bun(), s.branch())).isEqualTo("97.000");
		s.o().post("/api/v1/sales/" + second + "/cancel", "{\"reason\":\"Customer left\"}").andExpect(status().isOk());
		assertThat(s.t().onHand(s.bun(), s.branch())).isEqualTo("98.000");
		assertThat(s.t().onHand(s.cola(), s.branch())).isEqualTo("48.000");
		s.o().get("/api/v1/kitchen/tickets?status=CANCELLED").andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void deliveryOrderGoesToTheRider() throws Exception {
		Setup s = setup("business");
		long customer = s.t().customer("Home customer", "0300-4445556", "0");
		Integer sale = Trading.read(s.o().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"customerId":%d,"orderType":"DELIVERY","lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"850"}]}""".formatted(s.session(), customer, s.deal()), Trading.key())
			.andExpect(status().isCreated()).andExpect(jsonPath("$.orderType").value("DELIVERY")), "$.id");
		Integer delivery = Trading.read(s.o().post("/api/v1/deliveries",
				"{\"saleId\":" + sale + ",\"address\":\"House 3, Street 9\"}").andExpect(status().isCreated()), "$.id");
		ApiClient rider = userWithRole(s.shop(), "RIDER", unique("rider"));
		rider.get("/api/v1/deliveries").andExpect(jsonPath("$.total").value(1));
		rider.patch("/api/v1/deliveries/" + delivery, "{\"status\":\"ON_THE_WAY\"}").andExpect(jsonPath("$.status").value("ON_THE_WAY"));
		rider.patch("/api/v1/deliveries/" + delivery, "{\"status\":\"DELIVERED\",\"receivedBy\":\"Customer\"}")
			.andExpect(jsonPath("$.status").value("DELIVERED"));
		// Riders do not sell or see sales.
		rider.get("/api/v1/sales").andExpect(status().isForbidden());
		rider.get("/api/v1/kitchen/tickets").andExpect(status().isForbidden());
	}

	@Test
	void otherShopTypesHaveNoKitchen() throws Exception {
		TestShop shop = newShop("general", "starter");
		shop.owner().get("/api/v1/kitchen/tickets").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("module_disabled"));
		Trading t = new Trading(shop.owner());
		long session = t.openTill(t.defaultBranch(), "1000");
		long item = t.product("Tea", "100", "0", "SERVICE", null);
		shop.owner().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"orderType":"DINE_IN","tableNo":"1","lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"100"}]}""".formatted(session, item), Trading.key())
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("module_disabled"));
		// A menu item needs the recipes module.
		shop.owner().post("/api/v1/products", "{\"name\":\"Burger\",\"price\":\"500\",\"unitId\":%d,\"kind\":\"RECIPE\"}"
			.formatted(t.firstUnit())).andExpect(status().isForbidden());
	}
}
