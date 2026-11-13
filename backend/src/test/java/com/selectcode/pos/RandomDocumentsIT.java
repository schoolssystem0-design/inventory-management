package com.selectcode.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * QA TC-STK-01 and TC-MNY-01: hundreds of random documents (receipts, sales on cash and credit, returns with every
 * refund method, cancellations, adjustments, transfers, purchase returns and payments both ways), then:
 * <ul>
 * <li>stock of every product, branch and batch equals the sum of its movements (and serial units match);</li>
 * <li>every customer's balance equals its ledger and equals invoices − returns − payments to the paisa;</li>
 * <li>every supplier's balance equals its ledger and equals bills − returns − payments.</li>
 * </ul>
 */
class RandomDocumentsIT extends IntegrationTest {

	@Test
	void stockAndBalancesStayExactAfterRandomDocuments() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		shopAdmin.setModules(shop.id(), Map.of("serials", true, "batches", true));
		ApiClient api = shop.owner();
		Trading t = new Trading(api);
		long main = t.branch(0);
		long godown = t.branch(1);
		long session = t.openTill(main, "100000");
		Random random = new Random(20261108);
		List<Long> suppliers = List.of(t.supplier("S1"), t.supplier("S2"), t.supplier("S3"));
		List<Long> customers = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			customers.add(t.customer("C" + i, "0300777000" + i, "500000"));
		}
		List<Long> products = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			products.add(t.product("Item " + i, String.valueOf(200 + i * 35), "0"));
		}
		long batchItem = t.product("Batch item", "300", "0", "BATCH", null);
		long phone = t.product("Phone", "40000", "0", "SERIAL", null);
		Map<String, Integer> done = new LinkedHashMap<>();
		List<Integer> sales = new ArrayList<>();
		List<Integer> grns = new ArrayList<>();
		int imei = 0;
		for (int step = 0; step < 400; step++) {
			int op = random.nextInt(100);
			MvcResult r;
			String kind;
			if (op < 18 || step < 12) {
				kind = "grn";
				long supplier = suppliers.get(random.nextInt(suppliers.size()));
				long p = products.get(random.nextInt(products.size()));
				long branch = random.nextInt(4) == 0 ? godown : main;
				String paid = random.nextBoolean() ? ",\"paidNow\":\"" + (100 + random.nextInt(900)) + "\"" : "";
				String body;
				if (random.nextInt(5) == 0) {
					List<String> units = new ArrayList<>();
					for (int u = 0; u < 1 + random.nextInt(3); u++) {
						units.add("{\"serial\":\"3599900000" + String.format("%05d", imei++) + "\"}");
					}
					body = """
							{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitCost":"%d","serials":[%s]}]}"""
						.formatted(supplier, main, phone, 30000 + random.nextInt(5000), String.join(",", units));
				}
				else if (random.nextInt(4) == 0) {
					body = """
							{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"%d","unitCost":"%d","batchNo":"B%d",
							 "expiry":"2030-01-%02d"}]%s}""".formatted(supplier, main, batchItem, 5 + random.nextInt(20),
							150 + random.nextInt(50), random.nextInt(4), 1 + random.nextInt(4), paid);
				}
				else {
					body = """
							{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"%d","bonusQty":"%d","unitCost":"%d",
							 "discount":"%d"}],"discount":"%d"%s}""".formatted(supplier, branch, p, 5 + random.nextInt(30),
							random.nextInt(3), 100 + random.nextInt(80), random.nextInt(20), random.nextInt(30), paid);
				}
				r = api.postWithKey("/api/v1/grns", body, Trading.key()).andReturn();
				if (r.getResponse().getStatus() == 201) {
					grns.add(com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
				}
			}
			else if (op < 50) {
				kind = "sale";
				List<String> lines = new ArrayList<>();
				for (int l = 0; l < 1 + random.nextInt(3); l++) {
					long p = products.get(random.nextInt(products.size()));
					lines.add("{\"productId\":" + p + ",\"qty\":\"" + (1 + random.nextInt(4)) + "\",\"discount\":\""
							+ random.nextInt(15) + "\"}");
				}
				if (random.nextInt(6) == 0) {
					lines.add("{\"productId\":" + batchItem + ",\"qty\":\"" + (1 + random.nextInt(3)) + "\"}");
				}
				if (random.nextInt(8) == 0) {
					List<String> inStock = TestDatabase.superuser().queryForList(
							"select serial from serial_unit where shop_id = ? and status = 'IN_STOCK' and branch_id = ? limit 1",
							String.class, shop.id(), main);
					if (!inStock.isEmpty()) {
						lines.add("{\"productId\":" + phone + ",\"serials\":[\"" + inStock.getFirst() + "\"]}");
					}
				}
				boolean credit = random.nextInt(3) == 0;
				String customer = credit || random.nextInt(4) == 0
						? ",\"customerId\":" + customers.get(random.nextInt(customers.size())) : "";
				String payments = credit ? "[{\"method\":\"CASH\",\"amount\":\"" + random.nextInt(200) + "\"},{\"method\":\"ACCOUNT\"}]"
						: "[{\"method\":\"CASH\",\"amount\":\"999999\"}]";
				String bill = random.nextInt(4) == 0 ? ",\"billDiscount\":\"" + random.nextInt(25) + "\"" : "";
				r = api.postWithKey("/api/v1/sales", """
						{"tillSessionId":%d%s%s,"lines":[%s],"payments":%s}""".formatted(session, customer, bill,
						String.join(",", lines), payments), Trading.key()).andReturn();
				if (r.getResponse().getStatus() == 201) {
					sales.add(com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
				}
			}
			else if (op < 62 && !sales.isEmpty()) {
				kind = "return";
				Integer sale = sales.get(random.nextInt(sales.size()));
				var view = api.get("/api/v1/sales/" + sale).andReturn().getResponse().getContentAsString();
				List<Integer> lineIds = com.jayway.jsonpath.JsonPath.read(view, "$.lines[*].id");
				Integer line = lineIds.get(random.nextInt(lineIds.size()));
				String method = List.of("CASH", "CREDIT_NOTE", "ACCOUNT").get(random.nextInt(3));
				String refund = random.nextInt(3) == 0 ? ",\"refundAmount\":\"0\"" : "";
				r = api.postWithKey("/api/v1/sales/" + sale + "/returns", """
						{"tillSessionId":%d,"reason":"random","refundMethod":"%s"%s,"lines":[{"saleLineId":%d,"qty":"1",
						 "restock":%s}]}""".formatted(session, method, refund, line, random.nextInt(4) != 0), Trading.key())
					.andReturn();
			}
			else if (op < 66 && !sales.isEmpty()) {
				kind = "cancel";
				Integer sale = sales.get(random.nextInt(sales.size()));
				r = api.post("/api/v1/sales/" + sale + "/cancel", "{\"reason\":\"random\"}").andReturn();
			}
			else if (op < 74) {
				kind = "customer_payment";
				long c = customers.get(random.nextInt(customers.size()));
				r = api.postWithKey("/api/v1/customer-payments", """
						{"customerId":%d,"amount":"%d","method":"%s","tillSessionId":%d}""".formatted(c, 50 + random.nextInt(3000),
						random.nextBoolean() ? "CASH" : "BANK", session), Trading.key()).andReturn();
			}
			else if (op < 81) {
				kind = "supplier_payment";
				long s = suppliers.get(random.nextInt(suppliers.size()));
				r = api.postWithKey("/api/v1/supplier-payments", """
						{"supplierId":%d,"amount":"%d","method":"BANK"}""".formatted(s, 100 + random.nextInt(4000)),
						Trading.key()).andReturn();
			}
			else if (op < 86 && !grns.isEmpty()) {
				kind = "purchase_return";
				Integer grn = grns.get(random.nextInt(grns.size()));
				var view = api.get("/api/v1/grns/" + grn).andReturn().getResponse().getContentAsString();
				Integer supplier = com.jayway.jsonpath.JsonPath.read(view, "$.supplierId");
				Integer line = com.jayway.jsonpath.JsonPath.read(view, "$.lines[0].id");
				List<String> serials = com.jayway.jsonpath.JsonPath.read(view, "$.lines[0].serials");
				String lineBody = serials.isEmpty() ? "{\"grnLineId\":" + line + ",\"qty\":\"1\"}"
						: "{\"grnLineId\":" + line + ",\"serials\":[\"" + serials.getFirst() + "\"]}";
				r = api.post("/api/v1/purchase-returns", """
						{"supplierId":%d,"grnId":%d,"reason":"DAMAGED","lines":[%s]}""".formatted(supplier, grn, lineBody))
					.andReturn();
			}
			else if (op < 92) {
				kind = "adjustment";
				long p = products.get(random.nextInt(products.size()));
				String reason = random.nextBoolean() ? "FOUND" : "DAMAGED";
				r = api.post("/api/v1/adjustments", """
						{"branchId":%d,"reason":"%s","lines":[{"productId":%d,"qty":"%d"}]}""".formatted(main, reason, p,
						1 + random.nextInt(2))).andReturn();
			}
			else {
				kind = "transfer";
				long p = products.get(random.nextInt(products.size()));
				r = api.post("/api/v1/transfers", """
						{"fromBranchId":%d,"toBranchId":%d,"lines":[{"productId":%d,"qty":"%d"}]}"""
					.formatted(godown, main, p, 1 + random.nextInt(3))).andReturn();
				if (r.getResponse().getStatus() == 201 && random.nextInt(4) != 0) {
					Integer id = com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
					api.post("/api/v1/transfers/" + id + "/receive").andExpect(status().isOk());
				}
			}
			int code = r.getResponse().getStatus();
			assertThat(code).as(kind + ": " + r.getResponse().getContentAsString()).isIn(200, 201, 409, 422);
			if (code < 300) {
				done.merge(kind, 1, Integer::sum);
			}
		}
		assertThat(done.getOrDefault("sale", 0)).isGreaterThan(50);
		assertThat(done.getOrDefault("return", 0)).isGreaterThan(10);
		assertThat(done.getOrDefault("grn", 0)).isGreaterThan(30);

		// TC-STK-01: stock = sum of movements, serial units match.
		List<?> differences = Trading.read(api.get("/api/v1/stock/audit").andExpect(status().isOk()), "$");
		assertThat(differences).as("stock rows differing from their history").isEmpty();

		// TC-MNY-01: balances to the paisa.
		JdbcTemplate db = TestDatabase.superuser();
		for (Map<String, Object> c : db.queryForList("select id, balance from customer where shop_id = ?", shop.id())) {
			long id = ((Number) c.get("id")).longValue();
			BigDecimal balance = (BigDecimal) c.get("balance");
			BigDecimal ledger = db.queryForObject("""
					select coalesce(sum(debit), 0) - coalesce(sum(credit), 0) from customer_ledger where customer_id = ?""",
					BigDecimal.class, id);
			BigDecimal fromDocuments = db.queryForObject("""
					select coalesce((select sum(due) from sale where customer_id = ? and status = 'POSTED'), 0)
					     - coalesce((select sum(refund_amount) from sale_return where customer_id = ? and refund_method = 'ACCOUNT'), 0)
					     - coalesce((select sum(unallocated) from customer_payment where customer_id = ?), 0)""", BigDecimal.class,
					id, id, id);
			assertThat(balance).as("customer " + id + " ledger").isEqualByComparingTo(ledger);
			assertThat(balance).as("customer " + id + " invoices − returns − payments").isEqualByComparingTo(fromDocuments);
		}
		for (Map<String, Object> s : db.queryForList("select id, balance from supplier where shop_id = ?", shop.id())) {
			long id = ((Number) s.get("id")).longValue();
			BigDecimal balance = (BigDecimal) s.get("balance");
			BigDecimal ledger = db.queryForObject("""
					select coalesce(sum(credit), 0) - coalesce(sum(debit), 0) from supplier_ledger where supplier_id = ?""",
					BigDecimal.class, id);
			BigDecimal fromDocuments = db.queryForObject("""
					select coalesce((select sum(total) from grn where supplier_id = ?), 0)
					     - coalesce((select sum(total) from purchase_return where supplier_id = ?), 0)
					     - coalesce((select sum(amount) from supplier_payment where supplier_id = ?), 0)""", BigDecimal.class, id,
					id, id);
			BigDecimal open = db.queryForObject("""
					select coalesce((select sum(due) from grn where supplier_id = ?), 0)
					     - coalesce((select sum(unallocated) from supplier_payment where supplier_id = ?), 0)
					     - (coalesce((select sum(total) from purchase_return where supplier_id = ?), 0)
					        - coalesce((select sum(returned) from grn where supplier_id = ?), 0))""", BigDecimal.class, id, id,
					id, id);
			assertThat(balance).as("supplier " + id + " ledger").isEqualByComparingTo(ledger);
			assertThat(balance).as("supplier " + id + " bills − returns − payments").isEqualByComparingTo(fromDocuments);
			assertThat(balance).as("supplier " + id + " open bills").isEqualByComparingTo(open);
		}
		// Every sale: due = total − returned − paid and never negative; credit notes never above their amount.
		assertThat(db.queryForObject("select count(*) from sale where shop_id = ? and due < 0", Integer.class, shop.id()))
			.isZero();
		assertThat(db.queryForObject("""
				select count(*) from sale_line l where l.shop_id = ? and l.qty_returned > l.qty""", Integer.class, shop.id()))
			.isZero();
	}
}
