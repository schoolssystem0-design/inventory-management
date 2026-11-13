package com.selectcode.pos.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.test.web.servlet.ResultActions;

/** Small helpers to set up trading data through the real API in integration tests. */
public final class Trading {

	private final ApiClient api;

	public Trading(ApiClient api) {
		this.api = api;
	}

	public static String key() {
		return UUID.randomUUID().toString();
	}

	public static <T> T read(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
	}

	public long unitId(String name) throws Exception {
		java.util.List<Integer> ids = read(api.get("/api/v1/units"), "$[?(@.name=='" + name + "')].id");
		if (!ids.isEmpty()) {
			return ids.getFirst();
		}
		return ((Integer) read(api.post("/api/v1/units", "{\"name\":\"" + name + "\",\"decimalsAllowed\":true}")
			.andExpect(status().isCreated()), "$.id")).longValue();
	}

	public long firstUnit() throws Exception {
		return ((Integer) read(api.get("/api/v1/units"), "$[0].id")).longValue();
	}

	/** A product with the given price and opening cost (no stock). */
	public long product(String name, String price, String cost) throws Exception {
		return product(name, price, cost, "STANDARD", null);
	}

	public long product(String name, String price, String cost, String kind, String extraJson) throws Exception {
		String body = "{\"name\":\"" + name + "\",\"price\":\"" + price + "\",\"costPrice\":\"" + cost
				+ "\",\"unitId\":" + firstUnit() + ",\"kind\":\"" + kind + "\"" + (extraJson == null ? "" : "," + extraJson)
				+ "}";
		return ((Integer) read(api.post("/api/v1/products", body).andExpect(status().isCreated()), "$.id")).longValue();
	}

	public long supplier(String name) throws Exception {
		return ((Integer) read(api.post("/api/v1/suppliers", "{\"name\":\"" + name + "\",\"termsDays\":30}")
			.andExpect(status().isCreated()), "$.id")).longValue();
	}

	public long customer(String name, String phone, String creditLimit) throws Exception {
		return ((Integer) read(api.post("/api/v1/customers", "{\"name\":\"" + name + "\",\"phone\":\"" + phone
				+ "\",\"creditLimit\":\"" + creditLimit + "\"}").andExpect(status().isCreated()), "$.id")).longValue();
	}

	/** The shop's default branch ("Main shop"). */
	public long defaultBranch() throws Exception {
		java.util.List<Integer> ids = read(api.get("/api/v1/branches"), "$[?(@.isDefault==true)].id");
		return ids.getFirst();
	}

	public long branch(int index) throws Exception {
		return ((Integer) read(api.get("/api/v1/branches"), "$[" + index + "].id")).longValue();
	}

	/** Receives {@code qty} units at {@code cost} each into a branch (simple GRN). */
	public long receive(long supplierId, long branchId, long productId, String qty, String cost) throws Exception {
		return ((Integer) read(api.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"%s","unitCost":"%s"}]}"""
			.formatted(supplierId, branchId, productId, qty, cost), key()).andExpect(status().isCreated()), "$.id"))
			.longValue();
	}

	public String onHand(long productId, long branchId) throws Exception {
		java.util.List<String> qty = read(api.get("/api/v1/stock/products/" + productId),
				"$.branches[?(@.branchId==" + branchId + ")].qty");
		return qty.getFirst();
	}

	public String averageCost(long productId) throws Exception {
		return read(api.get("/api/v1/products/" + productId), "$.costAvg");
	}

	/** Opens a till session on the branch's first till and returns its id. */
	public long openTill(long branchId, String openingFloat) throws Exception {
		java.util.List<Integer> tills = read(api.get("/api/v1/tills"), "$[?(@.branchId==" + branchId + ")].id");
		return ((Integer) read(api.post("/api/v1/till-sessions", "{\"tillId\":" + tills.getFirst()
				+ ",\"openingFloat\":\"" + openingFloat + "\"}").andExpect(status().isCreated()), "$.id")).longValue();
	}
}
