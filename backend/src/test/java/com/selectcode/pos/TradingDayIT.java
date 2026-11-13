package com.selectcode.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.platform.app.DemoShops;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Each shop type's demo shop can be seeded (OPS-01) and runs a full day through the API (TC-DAY-01, automated form):
 * open the till, receive goods, sell, take a return, count stock, close the till and read the reports; stock and
 * balances stay exact.
 */
class TradingDayIT extends IntegrationTest {

	@Autowired
	DemoShops demoShops;

	@ParameterizedTest
	@ValueSource(strings = { "mobile", "super_store", "medical", "garments", "electronics", "hardware", "cosmetics",
			"books", "auto_parts", "general" })
	void demoShopRunsAFullDay(String type) throws Exception {
		Map<String, Object> demo = demoShops.reset(type, DemoShops.Size.SMALL);
		long shopId = ((Number) demo.get("shopId")).longValue();
		String code = (String) demo.get("code");
		JdbcTemplate db = TestDatabase.superuser();
		assertThat((Integer) demo.get("products")).as(type + " products").isGreaterThan(3);
		assertThat((Integer) demo.get("sales")).as(type + " history").isGreaterThan(0);
		assertThat(db.queryForObject("select count(*) from supplier where shop_id = ?", Integer.class, shopId)).isEqualTo(5);
		assertThat(db.queryForObject("select count(*) from purchase_order where shop_id = ? and status = 'ORDERED'",
				Integer.class, shopId)).isGreaterThan(0);
		assertThat(db.queryForObject("select count(distinct role_id) from app_user where shop_id = ?", Integer.class,
				shopId)).isGreaterThanOrEqualTo(7);

		db.update("update app_user set password_hash = ?, must_change_password = false where shop_id = ? and is_owner",
				new BCryptPasswordEncoder(4).encode(PASSWORD), shopId);
		ApiClient owner = client();
		owner.login(code, "owner", PASSWORD).andExpect(status().isOk());
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();

		// Morning: open the till, receive goods from a supplier.
		long session = t.openTill(branch, "10000");
		Long supplier = db.queryForObject("select min(id) from supplier where shop_id = ?", Long.class, shopId);
		assertThat(db.queryForObject("""
				select count(*) from product p where p.shop_id = ? and p.kind in ('STANDARD', 'BATCH')
				and (select coalesce(sum(qty), 0) from product_stock s where s.product_id = p.id and s.branch_id = ?) > 5""",
				Integer.class, shopId, branch)).as(type + " stocked items: " + db.queryForList(
						"select p.name, p.kind, (select sum(qty) from product_stock s where s.product_id = p.id) as q from product p where p.shop_id = ?",
						shopId)).isGreaterThan(0);
		Map<String, Object> item = db.queryForMap("""
				select p.id, p.kind, p.price from product p where p.shop_id = ? and p.active and p.kind in ('STANDARD', 'BATCH')
				and (select coalesce(sum(qty), 0) from product_stock s where s.product_id = p.id and s.branch_id = ?) > 5
				order by p.id limit 1""", shopId, branch);
		long productId = ((Number) item.get("id")).longValue();
		boolean batch = "BATCH".equals(item.get("kind"));
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"supplierBillNo":"DAY-1","lines":[{"productId":%d,"qty":"5","unitCost":"10"%s}]}"""
			.formatted(supplier, branch, productId, batch ? ",\"batchNo\":\"DAY\",\"expiry\":\"2031-01-01\"" : ""),
				Trading.key()).andExpect(status().isCreated());

		// Selling: two of the item, cash.
		BigDecimal price = (BigDecimal) item.get("price");
		// Medicines marked RX need a prescription on the bill (MED-08, on by default).
		String prescription = "medical".equals(type)
				? ",\"prescription\":{\"patientName\":\"Walk-in patient\",\"doctorName\":\"Dr. Day\"}" : "";
		Integer sale = Trading.read(owner.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"%s"}]%s}"""
			.formatted(session, productId, price.multiply(BigDecimal.TWO).toPlainString(), prescription), Trading.key())
			.andExpect(status().isCreated()), "$.id");
		if ("mobile".equals(type) || "electronics".equals(type)) {
			Map<String, Object> model = db.queryForMap(
					"select id, price from product where shop_id = ? and kind = 'SERIAL' order by id limit 1", shopId);
			String imei = "35" + String.format("%013d", System.nanoTime() % 10_000_000_000_000L);
			owner.postWithKey("/api/v1/grns", """
					{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitCost":"30000","serials":[{"serial":"%s"}]}]}"""
				.formatted(supplier, branch, model.get("id"), imei), Trading.key()).andExpect(status().isCreated());
			owner.postWithKey("/api/v1/sales", """
					{"tillSessionId":%d,"lines":[{"productId":%d,"serials":["%s"]}],"payments":[{"method":"CARD","amount":"%s"}]}"""
				.formatted(session, model.get("id"), imei, model.get("price")), Trading.key())
				.andExpect(status().isCreated());
		}
		// A return of one item, cash back.
		Integer line = Trading.read(owner.get("/api/v1/sales/" + sale), "$.lines[0].id");
		owner.postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Changed mind","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(session, line), Trading.key()).andExpect(status().isCreated());

		// Stock count of the item.
		Integer count = Trading.read(owner.post("/api/v1/stock-counts", "{\"branchId\":" + branch + "}")
			.andExpect(status().isCreated()), "$.id");
		String onHand = t.onHand(productId, branch);
		if (!batch) {
			owner.put("/api/v1/stock-counts/" + count + "/lines",
					"{\"entries\":[{\"productId\":" + productId + ",\"countedQty\":\"" + onHand + "\"}]}")
				.andExpect(status().isOk());
			owner.post("/api/v1/stock-counts/" + count + "/post").andExpect(jsonPath("$.status").value("POSTED"));
		}
		else {
			owner.post("/api/v1/stock-counts/" + count + "/cancel").andExpect(jsonPath("$.status").value("CANCELLED"));
		}

		// Evening: close the till with the exact cash, read the reports.
		String expected = Trading.read(owner.get("/api/v1/till-sessions/" + session + "/z-report"), "$.expectedCash");
		owner.post("/api/v1/till-sessions/" + session + "/close", "{\"countedCash\":\"" + expected + "\"}")
			.andExpect(jsonPath("$.difference").value("0.00"));
		owner.get("/api/v1/dashboard").andExpect(status().isOk()).andExpect(jsonPath("$.invoicesToday").exists());
		owner.get("/api/v1/cash/daily").andExpect(status().isOk());
		owner.get("/api/v1/cash/profit-loss").andExpect(status().isOk());
		owner.get("/api/v1/reports/sales-daily").andExpect(status().isOk());
		owner.get("/api/v1/onboarding").andExpect(jsonPath("$.steps[?(@.key=='sale')].done").value(true));
		List<?> differences = Trading.read(owner.get("/api/v1/stock/audit"), "$");
		assertThat(differences).as(type + " stock vs history").isEmpty();
		assertThat(db.queryForObject("""
				select count(*) from customer c where c.shop_id = ? and c.balance <>
				  (select coalesce(sum(debit), 0) - coalesce(sum(credit), 0) from customer_ledger l where l.customer_id = c.id)""",
				Integer.class, shopId)).as(type + " customer balances").isZero();
	}
}
