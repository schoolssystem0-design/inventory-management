package com.selectcode.pos.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * ACC-05, QA TC-SEC-04: a cashier never receives cost or profit from any endpoint. Every GET endpoint is called as a
 * cashier (with this shop's ids); every JSON field whose name mentions cost must be null. Also checks that roles
 * without a permission get 403 and that a cashier cannot set cost when creating a product.
 */
class CostHidingIT extends IntegrationTest {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Test
	void cashierNeverSeesCostInAnyResponse() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		Integer unitId = json(shop.owner().get("/api/v1/units"), "$[0].id");
		Integer productId = json(shop.owner().post("/api/v1/products", """
				{"name":"Cooking oil 5L","price":"2950","costPrice":"2600","unitId":%d,"barcodes":["8960001112223"]}"""
			.formatted(unitId)).andExpect(status().isCreated()), "$.id");
		// Owner sees cost.
		assertThat((String) json(shop.owner().get("/api/v1/products/" + productId), "$.costAvg")).isEqualTo("2600.00");

		ApiClient cashier = userWithRole(shop, "CASHIER", unique("cash"));
		List<String> checked = new ArrayList<>();
		for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
			if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
				continue;
			}
			for (String path : info.getPatternValues()) {
				if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/platform/") || path.startsWith("/api/v1/dev/")
						|| path.startsWith("/api/v1/public/")) {
					continue;
				}
				String url = path.replace("{id}", String.valueOf(productId)).replace("{key}", "serials");
				if (path.equals("/api/v1/products/lookup")) {
					url += "?code=8960001112223";
				}
				if (url.contains("{")) {
					continue;
				}
				MvcResult result = cashier.get(url).andReturn();
				String type = result.getResponse().getContentType();
				if (result.getResponse().getStatus() == 200 && type != null && type.contains("json")) {
					assertNoCost(url, JsonPath.read(result.getResponse().getContentAsString(), "$"));
					checked.add(url);
				}
			}
		}
		assertThat(checked).contains("/api/v1/products", "/api/v1/products/" + productId,
				"/api/v1/products/" + productId + "/history", "/api/v1/products/lookup?code=8960001112223");
	}

	@Test
	void cashierCannotSetCostOrChangePricesOrManageUsers() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient cashier = userWithRole(shop, "CASHIER", unique("cash"));
		cashier.post("/api/v1/products", "{\"name\":\"x\",\"price\":\"1\",\"unitId\":1}").andExpect(status().isForbidden());
		cashier.get("/api/v1/users").andExpect(status().isForbidden());
		cashier.get("/api/v1/settings").andExpect(status().isForbidden());
		cashier.get("/api/v1/audit").andExpect(status().isForbidden());
		cashier.post("/api/v1/products/bulk-price/preview", "{}").andExpect(status().isForbidden());
		cashier.get("/api/v1/products/export").andExpect(status().isForbidden());
		cashier.get("/api/v1/platform/overview").andExpect(status().isForbidden());

		// A stock keeper may manage nothing in the catalogue except labels.
		ApiClient keeper = userWithRole(shop, "STOCK_KEEPER", unique("keep"));
		keeper.post("/api/v1/labels", "{\"items\":[]}").andExpect(status().isUnprocessableContent());
		keeper.post("/api/v1/brands", "{\"name\":\"B\"}").andExpect(status().isForbidden());

		// A manager can manage products but cannot change shop settings.
		ApiClient manager = userWithRole(shop, "MANAGER", unique("mgr"));
		manager.post("/api/v1/brands", "{\"name\":\"Brand " + unique("") + "\"}").andExpect(status().isCreated());
		manager.patch("/api/v1/settings", "{\"taxRate\":\"5\"}").andExpect(status().isForbidden());
	}

	@Test
	void managerWithoutPricePermissionCannotChangePrice() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		Integer unitId = json(shop.owner().get("/api/v1/units"), "$[0].id");
		Integer id = json(shop.owner().post("/api/v1/products", "{\"name\":\"Pen\",\"price\":\"50\",\"unitId\":" + unitId + "}")
			.andExpect(status().isCreated()), "$.id");
		// A custom role with catalog.manage but not catalog.prices.
		Long roleId = TestDatabase.superuser().queryForObject("""
				insert into role (shop_id, name, permissions) values (?, 'Editor', '{catalog.view,catalog.manage}')
				returning id""", Long.class, shop.id());
		String username = unique("ed");
		TestDatabase.superuser().update("update role set permissions = permissions where id = ?", roleId);
		long userId = createUser(shop, "CASHIER", username);
		TestDatabase.superuser().update("update app_user set role_id = ? where id = ?", roleId, userId);
		ApiClient editor = client();
		editor.login(shop.code(), username, PASSWORD).andExpect(status().isOk());
		editor.patch("/api/v1/products/" + id, "{\"name\":\"Blue pen\"}").andExpect(status().isOk());
		editor.patch("/api/v1/products/" + id, "{\"price\":\"55\"}").andExpect(status().isForbidden());
		editor.patch("/api/v1/products/" + id, "{\"costPrice\":\"30\"}").andExpect(status().isForbidden());
	}

	@SuppressWarnings("unchecked")
	private static void assertNoCost(String url, Object node) {
		if (node instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				String key = String.valueOf(e.getKey()).toLowerCase();
				if (key.contains("cost") || key.contains("profit") || key.contains("margin")) {
					assertThat(e.getValue()).as(url + " field " + e.getKey()).isNull();
				}
				assertNoCost(url, e.getValue());
			}
		}
		else if (node instanceof List<?> list) {
			for (Object item : list) {
				assertNoCost(url, item);
			}
		}
	}
}
