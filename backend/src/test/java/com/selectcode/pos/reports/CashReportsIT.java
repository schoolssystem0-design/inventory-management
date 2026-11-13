package com.selectcode.pos.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.selectcode.pos.notifications.app.NotificationDispatcher;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cash and reports: daily cash summary (TC-CSH-01), profit and loss (CSH-04), every Phase 1 report runs and totals
 * match the database (TC-RPT-01), cost reports hidden without cost permission (RPT-04), downloads, background exports
 * and the owner's backup (OPS-13, NFR-13), dashboard by role, search, alerts and the notification outbox (TC-NTF-01).
 */
class CashReportsIT extends IntegrationTest {

	@Autowired
	NotificationDispatcher dispatcher;

	@Autowired
	PlatformTransactionManager transactions;

	record Day(TestShop shop, ApiClient owner, Trading t, long branch, long session, long product, long customer,
			long supplier) {
	}

	Day tradingDay() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long supplier = t.supplier("Wholesaler");
		long product = t.product("Fan", "3000", "0", "STANDARD", "\"reorderLevel\":\"10\"");
		t.receive(supplier, branch, product, "12", "2000");
		long customer = t.customer("Akram", "0300-1231231", "50000");
		long session = t.openTill(branch, "5000");
		// Cash sale 3,000 and a credit sale 3,000 (nothing paid).
		Integer cashSale = Trading.read(owner.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"3000"}]}"""
			.formatted(session, product), Trading.key()).andExpect(status().isCreated()), "$.id");
		owner.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"ACCOUNT"}]}"""
			.formatted(session, customer, product), Trading.key()).andExpect(status().isCreated());
		// Customer pays 1,000 cash at the till; 200 refunded in cash on a return of part value... (whole fan 3,000 → refund 3,000)
		owner.postWithKey("/api/v1/customer-payments", """
				{"customerId":%d,"amount":"1000","method":"CASH","tillSessionId":%d}""".formatted(customer, session),
				Trading.key()).andExpect(status().isCreated());
		Integer line = Trading.read(owner.get("/api/v1/sales/" + cashSale), "$.lines[0].id");
		owner.postWithKey("/api/v1/sales/" + cashSale + "/returns", """
				{"tillSessionId":%d,"reason":"Noisy","refundMethod":"CASH","refundAmount":"200","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(session, line), Trading.key()).andExpect(status().isCreated());
		owner.post("/api/v1/till-sessions/" + session + "/cash-movements",
				"{\"kind\":\"IN\",\"amount\":\"100\",\"reason\":\"Change\"}").andExpect(status().isCreated());
		owner.post("/api/v1/till-sessions/" + session + "/cash-movements",
				"{\"kind\":\"OUT\",\"amount\":\"50\",\"reason\":\"Tea\"}").andExpect(status().isCreated());
		Integer category = Trading.read(owner.get("/api/v1/expense-categories"), "$[0].id");
		owner.post("/api/v1/expenses", """
				{"categoryId":%d,"amount":"500","method":"CASH","tillSessionId":%d,"paidTo":"Electrician"}"""
			.formatted(category, session)).andExpect(status().isCreated());
		owner.postWithKey("/api/v1/supplier-payments", """
				{"supplierId":%d,"branchId":%d,"amount":"400","method":"CASH"}""".formatted(supplier, branch), Trading.key())
			.andExpect(status().isCreated());
		return new Day(shop, owner, t, branch, session, product, customer, supplier);
	}

	@Test
	void dailyCashAddsUpAndTillClosesExactly() throws Exception {
		Day d = tradingDay();
		// opening 5,000 + sales 3,000 + receipts 1,000 + in 100 − refunds 200 − supplier 400 − out 50 − expenses 500 = 7,950
		d.owner().get("/api/v1/cash/daily?branchId=" + d.branch())
			.andExpect(jsonPath("$.opening").value("5000.00"))
			.andExpect(jsonPath("$.cashSales").value("3000.00"))
			.andExpect(jsonPath("$.cashReceipts").value("1000.00"))
			.andExpect(jsonPath("$.cashRefunds").value("200.00"))
			.andExpect(jsonPath("$.supplierPayments").value("400.00"))
			.andExpect(jsonPath("$.expenses").value("500.00"))
			.andExpect(jsonPath("$.expectedClosing").value("7950.00"))
			.andExpect(jsonPath("$.openTills").value(1));
		// The till itself does not pay suppliers: 8,350 expected in the drawer.
		d.owner().post("/api/v1/till-sessions/" + d.session() + "/close", "{\"countedCash\":\"8350\"}")
			.andExpect(jsonPath("$.expectedCash").value("8350.00"))
			.andExpect(jsonPath("$.difference").value("0.00"));
		d.owner().get("/api/v1/cash/daily?branchId=" + d.branch()).andExpect(jsonPath("$.difference").value("0.00"))
			.andExpect(jsonPath("$.counted").value("8350.00"));
	}

	@Test
	void profitAndLossAddsUp() throws Exception {
		Day d = tradingDay();
		// Net sales: 3,000 + 3,000 − 3,000 returned = 3,000; cost 4,000 − 2,000 back = 2,000; gross profit 1,000;
		// return charge 2,800 (3,000 value − 200 refunded) is income; expenses 500 → net 3,300.
		d.owner().get("/api/v1/cash/profit-loss")
			.andExpect(jsonPath("$.total.grossSales").value("6000.00"))
			.andExpect(jsonPath("$.total.returns").value("3000.00"))
			.andExpect(jsonPath("$.total.netSales").value("3000.00"))
			.andExpect(jsonPath("$.total.costOfSales").value("2000.00"))
			.andExpect(jsonPath("$.total.grossProfit").value("1000.00"))
			.andExpect(jsonPath("$.total.returnCharges").value("2800.00"))
			.andExpect(jsonPath("$.total.expenses").value("500.00"))
			.andExpect(jsonPath("$.total.netProfit").value("3300.00"));
	}

	@Test
	void everyReportRunsAndTotalsMatchTheDatabase() throws Exception {
		Day d = tradingDay();
		shopAdmin.setModules(d.shop().id(), Map.of("serials", true, "used_buyback", true, "warranty", true, "batches",
				true, "formula", true, "variants", true, "weighed", true, "multi_till", true));
		List<String> keys = Trading.read(d.owner().get("/api/v1/reports").andExpect(status().isOk()), "$[*].key");
		assertThat(keys).hasSizeGreaterThan(50).contains("sales-daily", "profit-by-product", "receivables-ageing",
				"near-expiry", "imei-stock", "variant-stock-sales", "tax-collected");
		for (String key : keys) {
			var result = d.owner().get("/api/v1/reports/" + key).andReturn();
			assertThat(result.getResponse().getStatus()).as("report " + key).isEqualTo(200);
		}
		// TC-RPT-01: totals equal an independent query.
		String total = Trading.read(d.owner().get("/api/v1/reports/sales-daily"), "$.totals[5]");
		BigDecimal expected = TestDatabase.superuser().queryForObject(
				"select sum(total) from sale where shop_id = ? and status = 'POSTED'", BigDecimal.class, d.shop().id());
		assertThat(new BigDecimal(total)).isEqualByComparingTo(expected);
		String onHand = Trading.read(d.owner().get("/api/v1/reports/stock-on-hand"), "$.totals[4]");
		assertThat(onHand).isEqualTo(d.t().onHand(d.product(), d.branch()));
		d.owner().get("/api/v1/reports/receivables-ageing").andExpect(jsonPath("$.rows[0][1]").value("2000.00"));
		d.owner().get("/api/v1/reports/no-such-report").andExpect(status().isNotFound());
	}

	@Test
	void costReportsHiddenWithoutCostPermission() throws Exception {
		Day d = tradingDay();
		long role = TestDatabase.superuser().queryForObject("""
				insert into role (shop_id, name, permissions) values (?, 'Reporter', '{reports.view,sales.view,stock.view}')
				returning id""", Long.class, d.shop().id());
		String username = unique("rep");
		long user = createUser(d.shop(), "CASHIER", username);
		TestDatabase.superuser().update("update app_user set role_id = ? where id = ?", role, user);
		ApiClient reporter = client();
		reporter.login(d.shop().code(), username, PASSWORD).andExpect(status().isOk());
		List<String> keys = Trading.read(reporter.get("/api/v1/reports"), "$[*].key");
		assertThat(keys).contains("sales-daily", "stock-on-hand").doesNotContain("profit-by-product", "purchases-by-supplier");
		reporter.get("/api/v1/reports/profit-by-product").andExpect(status().isNotFound());
		List<String> columns = Trading.read(reporter.get("/api/v1/reports/stock-on-hand"), "$.columns[*].key");
		assertThat(columns).contains("retailValue").doesNotContain("costValue");
		List<String> salesColumns = Trading.read(reporter.get("/api/v1/reports/sales-daily"), "$.columns[*].key");
		assertThat(salesColumns).doesNotContain("cost", "profit");
		reporter.get("/api/v1/cash/profit-loss").andExpect(status().isForbidden());
		ApiClient cashier = userWithRole(d.shop(), "CASHIER", unique("c"));
		cashier.get("/api/v1/reports").andExpect(status().isForbidden());
		cashier.get("/api/v1/dashboard").andExpect(jsonPath("$.profitToday").doesNotExist())
			.andExpect(jsonPath("$.salesToday").exists());
	}

	@Test
	void downloadsExportsAndBackup() throws Exception {
		Day d = tradingDay();
		var xlsx = d.owner().get("/api/v1/reports/sales-daily/download?format=XLSX").andExpect(status().isOk()).andReturn();
		assertThat(xlsx.getResponse().getContentAsByteArray()).startsWith('P', 'K');
		var pdf = d.owner().get("/api/v1/reports/invoice-register/download?format=PDF").andExpect(status().isOk()).andReturn();
		assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
		Integer job = Trading.read(d.owner().post("/api/v1/reports/stock-on-hand/export", "{\"format\":\"PDF\"}")
			.andExpect(status().isAccepted()), "$.id");
		waitDone(d.owner(), job);
		var file = d.owner().get("/api/v1/exports/" + job + "/file").andExpect(status().isOk()).andReturn();
		assertThat(new String(file.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
		Integer backup = Trading.read(d.owner().post("/api/v1/backup").andExpect(status().isAccepted()), "$.id");
		waitDone(d.owner(), backup);
		d.owner().get("/api/v1/exports/" + backup).andExpect(jsonPath("$.rows").value(org.hamcrest.Matchers.greaterThan(3)));
		d.owner().get("/api/v1/exports/" + backup + "/file").andExpect(status().isOk());
		// The cashier cannot take the backup.
		ApiClient cashier = userWithRole(d.shop(), "CASHIER", unique("c"));
		cashier.post("/api/v1/backup").andExpect(status().isForbidden());
	}

	@Test
	void dashboardAndSearch() throws Exception {
		Day d = tradingDay();
		d.owner().get("/api/v1/dashboard")
			.andExpect(jsonPath("$.salesToday").value("6000.00"))
			.andExpect(jsonPath("$.invoicesToday").value(2))
			.andExpect(jsonPath("$.customerDues").value("2000.00"))
			.andExpect(jsonPath("$.openTills").value(1))
			.andExpect(jsonPath("$.last14Days.length()").value(14))
			.andExpect(jsonPath("$.topItems[0].name").value("Fan"));
		List<String> types = Trading.read(d.owner().get("/api/v1/search?q=fan"), "$[*].type");
		assertThat(types).contains("product");
		List<String> sales = Trading.read(d.owner().get("/api/v1/search?q=INV"), "$[?(@.type=='sale')].title");
		assertThat(sales).isNotEmpty();
	}

	@Test
	void alertsRingTheBellAndOutboxRetries() throws Exception {
		Day d = tradingDay();
		// 12 received − 2 sold + 1 restocked = 11 > 10... sell 2 more to go below the reorder level of 10.
		d.owner().postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"6000"}]}"""
			.formatted(d.session(), d.product()), Trading.key()).andExpect(status().isCreated());
		d.owner().post("/api/v1/alerts/refresh").andExpect(jsonPath("$.open.LOW").value(1));
		d.owner().get("/api/v1/alerts").andExpect(jsonPath("$.items[0].kind").value("LOW"));
		d.owner().get("/api/v1/notifications/summary").andExpect(jsonPath("$.unread").value(1));
		Integer note = Trading.read(d.owner().get("/api/v1/notifications"), "$.items[0].id");
		d.owner().post("/api/v1/notifications/" + note + "/read").andExpect(status().isNoContent());
		d.owner().get("/api/v1/notifications/summary").andExpect(jsonPath("$.unread").value(0));
		// Refreshing again the same day does not ring twice.
		d.owner().post("/api/v1/alerts/refresh");
		d.owner().get("/api/v1/notifications/summary").andExpect(jsonPath("$.unread").value(0));

		// Email receipt goes through the outbox (NTF-05).
		Integer sale = Trading.read(d.owner().get("/api/v1/sales?size=1"), "$.items[0].id");
		outbox.clear();
		d.owner().post("/api/v1/sales/" + sale + "/send", "{\"email\":\"akram@example.com\"}")
			.andExpect(jsonPath("$.email").value(true));
		dispatch(d.shop().id());
		assertThat(outbox.entries()).anySatisfy(e -> assertThat(e.message().recipient()).isEqualTo("akram@example.com"));
		// SMS: off until the module is on; with a dead gateway it is retried 3 times then marked failed (TC-NTF-01).
		d.owner().post("/api/v1/sales/" + sale + "/send", "{\"phone\":\"03001231231\"}")
			.andExpect(status().isUnprocessableContent());
		shopAdmin.setModules(d.shop().id(), Map.of("sms", true));
		TestDatabase.superuser().update(
				"update platform_setting set value = 'http://127.0.0.1:9/sms' where setting_key = 'sms_gateway_url'");
		try {
			d.owner().post("/api/v1/sales/" + sale + "/send", "{\"phone\":\"03001231231\"}").andExpect(status().isOk());
			for (int i = 0; i < 3; i++) {
				TestDatabase.superuser().update("update notification set next_attempt_at = now() where shop_id = ?",
						d.shop().id());
				dispatch(d.shop().id());
			}
			Map<String, Object> sms = TestDatabase.superuser().queryForMap(
					"select status, attempts from notification where shop_id = ? and channel = 'SMS'", d.shop().id());
			assertThat(sms).containsEntry("status", "FAILED").containsEntry("attempts", 3);
		}
		finally {
			TestDatabase.superuser().update("update platform_setting set value = '' where setting_key = 'sms_gateway_url'");
		}
	}

	private void dispatch(long shopId) {
		TenantContext.runAsShop(shopId, () -> new TransactionTemplate(transactions).executeWithoutResult(s -> dispatcher.dispatchShop()));
	}

	private static void waitDone(ApiClient api, Integer job) throws Exception {
		for (int i = 0; i < 100; i++) {
			String status = JsonPath.read(api.get("/api/v1/exports/" + job).andReturn().getResponse().getContentAsString(),
					"$.status");
			if ("DONE".equals(status)) {
				return;
			}
			assertThat(status).isNotEqualTo("FAILED");
			Thread.sleep(100);
		}
		throw new AssertionError("export " + job + " did not finish");
	}
}
