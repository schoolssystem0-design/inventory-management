package com.selectcode.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POS, sales, returns and customer accounts through the API (QA §6 POS, SAL, CUS and shop-type cases).
 */
class SalesIT extends IntegrationTest {

	record Setup(TestShop shop, ApiClient owner, Trading t, long branch, long session, long supplier) {
	}

	Setup setup(String type) throws Exception {
		TestShop shop = newShop(type, "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long session = t.openTill(branch, "5000");
		long supplier = t.supplier("Main supplier");
		return new Setup(shop, shop.owner(), t, branch, session, supplier);
	}

	ResultActions sell(ApiClient api, String body) throws Exception {
		return api.postWithKey("/api/v1/sales", body, Trading.key());
	}

	@Test
	void splitPaymentGivesChangeAndStoresEachMethod() throws Exception {
		Setup s = setup("general");
		long kettle = s.t().product("Kettle", "4950", "0");
		s.t().receive(s.supplier(), s.branch(), kettle, "5", "3500");
		// Rs 4,950: Rs 3,000 card + Rs 2,000 cash handed over → change Rs 50 (TC-POS-02).
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CARD","amount":"3000"},{"method":"CASH","amount":"2000"}]}"""
			.formatted(s.session(), kettle)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("4950.00"))
			.andExpect(jsonPath("$.change").value("50.00"))
			.andExpect(jsonPath("$.due").value("0.00"))
			.andExpect(jsonPath("$.payments[?(@.method=='CASH')].amount").value("1950.00"))
			.andExpect(jsonPath("$.payments[?(@.method=='CASH')].tendered").value("2000.00"))
			.andExpect(jsonPath("$.payments[?(@.method=='CARD')].amount").value("3000.00"))
			.andExpect(jsonPath("$.costTotal").value("3500.00"))
			.andExpect(jsonPath("$.profit").value("1450.00"));
		assertThat(s.t().onHand(kettle, s.branch())).isEqualTo("4.000");
		// Card overpayment cannot be change.
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CARD","amount":"5000"}]}"""
			.formatted(s.session(), kettle)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("overpaid"));
	}

	@Test
	void billDiscountIsSpreadWithRemainderOnLastLine() throws Exception {
		Setup s = setup("general");
		long a = s.t().product("A", "100", "0", "SERVICE", null);
		long b = s.t().product("B", "200", "0", "SERVICE", null);
		long c = s.t().product("C", "300", "0", "SERVICE", null);
		// TC-POS-04 with the shared vector: 100 / 200 / 300 and Rs 100 off → 16.67 / 33.33 / 50.00.
		sell(s.owner(), """
				{"tillSessionId":%d,"billDiscount":"100","lines":[{"productId":%d,"qty":"1"},{"productId":%d,"qty":"1"},
				 {"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"500"}]}"""
			.formatted(s.session(), a, b, c)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.lines[0].billShare").value("16.67"))
			.andExpect(jsonPath("$.lines[1].billShare").value("33.33"))
			.andExpect(jsonPath("$.lines[2].billShare").value("50.00"))
			.andExpect(jsonPath("$.total").value("500.00"));
	}

	@Test
	void discountAboveLimitNeedsManagerPinAndIsRecorded() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Shirt", "2000", "0", "SERVICE", null);
		ApiClient cashier = userWithRole(s.shop(), "CASHIER", unique("cash"));
		long cashierTill = TestDatabase.superuser().queryForObject("""
				insert into till (shop_id, branch_id, name) values (?, ?, 'Till 2') returning id""", Long.class,
				s.shop().id(), s.branch());
		Integer session = Trading.read(cashier.post("/api/v1/till-sessions", "{\"tillId\":" + cashierTill + "}")
			.andExpect(status().isCreated()), "$.id");
		String body = """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1","discount":"100"}],
				 "payments":[{"method":"CASH","amount":"1900"}]}""".formatted(session, item);
		// A cashier has no discount permission at all (TC-ACC-03).
		cashier.postWithKey("/api/v1/sales", body, Trading.key()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("approval_required"));
		s.owner().post("/api/v1/auth/pin", "{\"pin\":\"7391\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().is2xxSuccessful());
		cashier.postWithHeaders("/api/v1/sales", body, "Idempotency-Key", Trading.key(), "X-Approval-User", "owner",
				"X-Approval-Pin", "1111").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("pin_wrong"));
		cashier.postWithHeaders("/api/v1/sales", body, "Idempotency-Key", Trading.key(), "X-Approval-User", "owner",
				"X-Approval-Pin", "7391").andExpect(status().isCreated())
			.andExpect(jsonPath("$.approvalNote").value("discount"))
			.andExpect(jsonPath("$.costTotal").doesNotExist())
			.andExpect(jsonPath("$.profit").doesNotExist());
	}

	@Test
	void onAccountSaleRaisesTheBalanceAndCreditLimitNeedsApproval() throws Exception {
		Setup s = setup("hardware");
		long cement = s.t().product("Cement", "1450", "0");
		s.t().receive(s.supplier(), s.branch(), cement, "200", "1300");
		long customer = s.t().customer("Contractor Asif", "0300-1112223", "100000");
		// Rs 72,500 for 50 bags, Rs 20,000 paid now (TC-POS-03).
		Integer saleId = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"50"}],
				 "payments":[{"method":"CASH","amount":"20000"},{"method":"ACCOUNT"}]}"""
			.formatted(s.session(), customer, cement)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.due").value("52500.00")), "$.id");
		s.owner().get("/api/v1/customers/" + customer).andExpect(jsonPath("$.balance").value("52500.00"));
		// Walk-in without customer cannot leave money owing.
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[]}"""
			.formatted(s.session(), cement)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("payment_short"));
		// Another 50 bags on credit takes the balance to 125,000 > 100,000: a cashier needs a manager (TC-CUS-02).
		ApiClient cashier = userWithRole(s.shop(), "CASHIER", unique("c"));
		cashier.post("/api/v1/till-sessions/" + s.session() + "/close", "{\"countedCash\":\"0\"}")
			.andExpect(status().isForbidden());
		long till2 = TestDatabase.superuser().queryForObject(
				"insert into till (shop_id, branch_id, name) values (?, ?, 'T2') returning id", Long.class, s.shop().id(),
				s.branch());
		Integer cs = Trading.read(cashier.post("/api/v1/till-sessions", "{\"tillId\":" + till2 + "}"), "$.id");
		cashier.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"50"}],"payments":[]}"""
			.formatted(cs, customer, cement), Trading.key()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.limit").value(org.hamcrest.Matchers.containsString("limit")));
		// Customer pays Rs 60,000: clears the invoice (52,500) and leaves 7,500 advance (TC-CUS-01 style).
		s.owner().postWithKey("/api/v1/customer-payments", """
				{"customerId":%d,"amount":"60000","method":"CASH","tillSessionId":%d}""".formatted(customer, s.session()),
				Trading.key()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.allocations[0].saleId").value(saleId))
			.andExpect(jsonPath("$.allocations[0].amount").value("52500.00"))
			.andExpect(jsonPath("$.unallocated").value("7500.00"))
			.andExpect(jsonPath("$.balanceAfter").value("-7500.00"));
	}

	@Test
	void paymentsClearOldestInvoicesFirst() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Service", "1000", "0", "SERVICE", null);
		long customer = s.t().customer("Clinic", "0321-5556667", "100000");
		List<Integer> ids = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ids.add(Trading.read(sell(s.owner(), """
					{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[]}"""
				.formatted(s.session(), customer, item)).andExpect(status().isCreated()), "$.id"));
		}
		s.owner().postWithKey("/api/v1/customer-payments", """
				{"customerId":%d,"amount":"1500","method":"BANK","reference":"TRX-1"}""".formatted(customer), Trading.key())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.allocations.length()").value(2))
			.andExpect(jsonPath("$.allocations[0].saleId").value(ids.get(0)))
			.andExpect(jsonPath("$.allocations[0].amount").value("1000.00"))
			.andExpect(jsonPath("$.allocations[1].saleId").value(ids.get(1)))
			.andExpect(jsonPath("$.allocations[1].amount").value("500.00"));
		s.owner().get("/api/v1/sales/" + ids.get(2)).andExpect(jsonPath("$.due").value("1000.00"));
		s.owner().get("/api/v1/customers/" + customer + "/statement")
			.andExpect(jsonPath("$.closing").value("1500.00"))
			.andExpect(jsonPath("$.totalDebit").value("3000.00"))
			.andExpect(jsonPath("$.totalCredit").value("1500.00"));
		s.owner().get("/api/v1/customers/" + customer + "/statement.pdf").andExpect(status().isOk());
	}

	@Test
	void sameIdempotencyKeyMakesOneSale() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Bulb", "300", "0");
		s.t().receive(s.supplier(), s.branch(), item, "10", "200");
		String body = """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"300"}]}"""
			.formatted(s.session(), item);
		String key = Trading.key();
		Integer first = Trading.read(s.owner().postWithKey("/api/v1/sales", body, key), "$.id");
		Integer again = Trading.read(s.owner().postWithKey("/api/v1/sales", body, key), "$.id");
		assertThat(again).isEqualTo(first);
		assertThat(s.t().onHand(item, s.branch())).isEqualTo("9.000");
		s.owner().postWithKey("/api/v1/sales", body, null).andExpect(status().isBadRequest());
	}

	@Test
	void sellingMoreThanStockIsRefusedWithAvailableQty() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Fan", "6000", "0");
		s.t().receive(s.supplier(), s.branch(), item, "2", "4500");
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"3"}],"payments":[{"method":"CASH","amount":"18000"}]}"""
			.formatted(s.session(), item)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("insufficient_stock"))
			.andExpect(jsonPath("$.available").value("2.000"));
		assertThat(s.t().onHand(item, s.branch())).isEqualTo("2.000");
	}

	@Test
	void tillCloseRecordsShortAndZReport() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Torch", "1000", "0", "SERVICE", null);
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"3"}],"payments":[{"method":"CASH","amount":"3000"}]}"""
			.formatted(s.session(), item)).andExpect(status().isCreated());
		s.owner().post("/api/v1/till-sessions/" + s.session() + "/cash-movements",
				"{\"kind\":\"OUT\",\"amount\":\"500\",\"reason\":\"Tea\"}").andExpect(status().isCreated());
		// Expected 5,000 + 3,000 − 500 = 7,500; counted 7,300 → short 200 (TC-POS-08).
		s.owner().post("/api/v1/till-sessions/" + s.session() + "/close", "{\"countedCash\":\"7300\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expectedCash").value("7500.00"))
			.andExpect(jsonPath("$.difference").value("-200.00"))
			.andExpect(jsonPath("$.cashOut").value("500.00"))
			.andExpect(jsonPath("$.sales").value(1));
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"1000"}]}"""
			.formatted(s.session(), item)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("till_closed"));
	}

	@Test
	void holdAndResumeABill() throws Exception {
		Setup s = setup("general");
		Integer held = Trading.read(s.owner().post("/api/v1/held-sales", """
				{"branchId":%d,"label":"Customer in red","cart":{"lines":[{"productId":1,"qty":"2"}]}}"""
			.formatted(s.branch())).andExpect(status().isCreated()), "$.id");
		s.owner().get("/api/v1/held-sales").andExpect(jsonPath("$[0].cart.lines[0].qty").value("2"));
		s.owner().delete("/api/v1/held-sales/" + held).andExpect(status().isNoContent());
		s.owner().get("/api/v1/held-sales").andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void returnClearsWhatIsOwedFirst() throws Exception {
		Setup s = setup("garments");
		long suit = s.t().product("Suit", "2150", "0", "SERVICE", null);
		long customer = s.t().customer("Rabia", "0333-4445556", "10000");
		// Sale 2,150 with 250 paid → due 1,900; returning it all allows at most 250 back (TC-SAL-02).
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"250"}]}""".formatted(s.session(), customer, suit))
			.andExpect(jsonPath("$.due").value("1900.00")), "$.id");
		Integer line = Trading.read(s.owner().get("/api/v1/sales/" + sale), "$.lines[0].id");
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Did not fit","refundMethod":"CASH","refundAmount":"300",
				 "lines":[{"saleLineId":%d,"qty":"1"}]}""".formatted(s.session(), line), Trading.key())
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("refund_too_large"));
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Did not fit","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.value").value("2150.00"))
			.andExpect(jsonPath("$.appliedToDue").value("1900.00"))
			.andExpect(jsonPath("$.refundAmount").value("250.00"));
		s.owner().get("/api/v1/sales/" + sale).andExpect(jsonPath("$.due").value("0.00"))
			.andExpect(jsonPath("$.returned").value("2150.00")).andExpect(jsonPath("$.paid").value("0.00"));
		s.owner().get("/api/v1/customers/" + customer).andExpect(jsonPath("$.balance").value("0.00"));
		// Returning more than sold (TC-SAL-04).
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"reason":"again","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1"}]}""".formatted(line),
				Trading.key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_returned"));
	}

	@Test
	void partialReturnOfDiscountedLineAndDamagedItems() throws Exception {
		Setup s = setup("general");
		long cup = s.t().product("Cup", "300", "0");
		s.t().receive(s.supplier(), s.branch(), cup, "10", "150");
		// 4 cups at 300 with Rs 120 line discount = 1,080 (270 each): return 1 restocked, 1 damaged (TC-SAL-01/03).
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"4","discount":"120"}],
				 "payments":[{"method":"CASH","amount":"1080"}]}""".formatted(s.session(), cup)), "$.id");
		Integer line = Trading.read(s.owner().get("/api/v1/sales/" + sale), "$.lines[0].id");
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Chipped","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1","restock":true}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(jsonPath("$.value").value("270.00"))
			.andExpect(jsonPath("$.refundAmount").value("270.00"));
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Broken","refundMethod":"CREDIT_NOTE","lines":[{"saleLineId":%d,"qty":"1","restock":false}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(jsonPath("$.creditNoteNumber").exists())
			.andExpect(jsonPath("$.costDamaged").value("150.00"));
		assertThat(s.t().onHand(cup, s.branch())).isEqualTo("7.000");
		List<String> notes = Trading.read(s.owner().get("/api/v1/credit-notes"), "$[*].balance");
		assertThat(notes).containsExactly("270.00");
	}

	@Test
	void exchangeOneStepCustomerPaysDifference() throws Exception {
		Setup s = setup("garments");
		long m = s.t().product("Kurta M", "2500", "0");
		long l = s.t().product("Kurta L", "2800", "0");
		s.t().receive(s.supplier(), s.branch(), m, "3", "1500");
		s.t().receive(s.supplier(), s.branch(), l, "3", "1600");
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"2500"}]}"""
			.formatted(s.session(), m)), "$.id");
		Integer line = Trading.read(s.owner().get("/api/v1/sales/" + sale), "$.lines[0].id");
		// M back, L out, customer pays 300 (TC-SAL-05).
		s.owner().postWithKey("/api/v1/exchanges", """
				{"saleId":%d,"reason":"Size","returnLines":[{"saleLineId":%d,"qty":"1"}],
				 "newSale":{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"300"}]}}"""
			.formatted(sale, line, s.session(), l), Trading.key()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.returned.refundMethod").value("EXCHANGE"))
			.andExpect(jsonPath("$.sale.total").value("2800.00"))
			.andExpect(jsonPath("$.sale.due").value("0.00"))
			.andExpect(jsonPath("$.sale.payments[?(@.method=='EXCHANGE')].amount").value("2500.00"));
		assertThat(s.t().onHand(m, s.branch())).isEqualTo("3.000");
		assertThat(s.t().onHand(l, s.branch())).isEqualTo("2.000");
	}

	@Test
	void sameDayCancelReversesStockAndMoneyButNotOnAnotherDay() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Iron", "3200", "0");
		s.t().receive(s.supplier(), s.branch(), item, "4", "2500");
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"6400"}]}"""
			.formatted(s.session(), item)), "$.id");
		s.owner().post("/api/v1/sales/" + sale + "/cancel", "{\"reason\":\"Wrong customer\"}")
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.due").value("0.00"));
		assertThat(s.t().onHand(item, s.branch())).isEqualTo("4.000");
		s.owner().get("/api/v1/till-sessions/" + s.session() + "/z-report").andExpect(jsonPath("$.expectedCash").value("5000.00"));
		Integer old = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"3200"}]}"""
			.formatted(s.session(), item)), "$.id");
		TestDatabase.superuser().execute("alter table sale disable trigger sale_posted");
		TestDatabase.superuser().update("update sale set business_date = business_date - 1 where id = ?", old);
		TestDatabase.superuser().execute("alter table sale enable trigger sale_posted");
		s.owner().post("/api/v1/sales/" + old + "/cancel", "{\"reason\":\"Late\"}")
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("not_same_day"));
	}

	@Test
	void postedSaleAmountsCannotBeChanged() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Mug", "200", "0", "SERVICE", null);
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"200"}]}"""
			.formatted(s.session(), item)), "$.id");
		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> TestDatabase.superuser().update("update sale set total = 1 where id = ?", sale))
			.hasMessageContaining("cannot change");
	}

	@Test
	void medicalStorePicksEarliestExpiryAndRefusesExpired() throws Exception {
		Setup s = setup("medical");
		long strip = s.t().unitId("strip");
		long box = s.t().unitId("box");
		long panadol = ((Integer) Trading.read(s.owner().post("/api/v1/products", """
				{"name":"Panadol 500","price":"30","costPrice":"0","unitId":%d,"kind":"BATCH",
				 "packUnits":[{"unitId":%d,"factor":"10","price":"280"}],"pharma":{"formula":"Paracetamol"}}"""
			.formatted(strip, box)).andExpect(status().isCreated()), "$.id")).longValue();
		LocalDate soon = LocalDate.now().plusDays(45);
		LocalDate later = LocalDate.now().plusYears(1);
		LocalDate past = LocalDate.now().minusDays(3);
		for (String[] b : List.of(new String[] { "LATE", later.toString() }, new String[] { "SOON", soon.toString() },
				new String[] { "OLD", past.toString() })) {
			s.owner().postWithKey("/api/v1/grns", """
					{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitId":%d,"qty":"1","unitCost":"250",
					 "batchNo":"%s","expiry":"%s"}]}""".formatted(s.supplier(), s.branch(), panadol, box, b[0], b[1]),
					Trading.key()).andExpect(status().isCreated());
		}
		assertThat(s.t().onHand(panadol, s.branch())).isEqualTo("30.000");
		// 1 strip: from the batch expiring first among valid ones (SOON), never OLD (TC-MED-01, TC-MED-03).
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"30"}]}"""
			.formatted(s.session(), panadol)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.lines[0].batchNo").value("SOON"))
			.andExpect(jsonPath("$.lines[0].price").value("30.00"));
		Integer oldBatch = TestDatabase.superuser().queryForObject(
				"select id from batch where shop_id = ? and batch_no = 'OLD'", Integer.class, s.shop().id());
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1","batchId":%d}],"payments":[{"method":"CASH","amount":"30"}]}"""
			.formatted(s.session(), panadol, oldBatch)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("batch_expired"));
		// A box of 10 strips at the box price; 19 sellable strips left (SOON 9 + LATE 10) → 1 box ok.
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"unitId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"280"}]}"""
			.formatted(s.session(), panadol, box)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("280.00"));
		// 9 valid strips left (OLD's 10 are expired and not sellable): 9 sell, the 10th does not.
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"9"}],"payments":[{"method":"CASH","amount":"270"}]}"""
			.formatted(s.session(), panadol)).andExpect(status().isCreated());
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"30"}]}"""
			.formatted(s.session(), panadol)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("insufficient_stock"));
	}

	@Test
	void weighedItemsSellByDecimalQuantity() throws Exception {
		Setup s = setup("super_store");
		long kg = s.t().unitId("kg");
		long sugar = ((Integer) Trading.read(s.owner().post("/api/v1/products", """
				{"name":"Sugar loose","price":"180","costPrice":"0","unitId":%d,"weighed":true}""".formatted(kg))
			.andExpect(status().isCreated()), "$.id")).longValue();
		s.t().receive(s.supplier(), s.branch(), sugar, "50", "150");
		// 1.25 kg at 180 = 225.00; stock −1.250 (TC-SUP-01).
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1.25"}],"payments":[{"method":"CASH","amount":"225"}]}"""
			.formatted(s.session(), sugar)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("225.00"));
		assertThat(s.t().onHand(sugar, s.branch())).isEqualTo("48.750");
		long pen = s.t().product("Pen", "20", "0");
		s.t().receive(s.supplier(), s.branch(), pen, "10", "10");
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1.5"}],"payments":[{"method":"CASH","amount":"30"}]}"""
			.formatted(s.session(), pen)).andExpect(status().isBadRequest());
	}

	@Test
	void usedPhoneBuybackNeedsCnicThenSellsAndReturnsByImei() throws Exception {
		Setup s = setup("mobile");
		long phone = s.t().product("iPhone 13 used", "110000", "0", "SERIAL", "\"warrantyDays\":7");
		byte[] png = java.util.Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
		Integer cnicFile = Trading.read(s.owner().upload("/api/v1/files", "CNIC_PHOTO", "cnic.png", png), "$.id");
		Integer phoneFile = Trading.read(s.owner().upload("/api/v1/files", "DEVICE_PHOTO", "phone.png", png), "$.id");
		String withoutCnic = """
				{"tillSessionId":%d,"productId":%d,"unit":{"serial":"353000111222333","storage":"128GB"},
				 "sellerName":"Kamran","sellerCnic":"","cnicFileId":%d,"itemFileId":%d,"price":"95000"}"""
			.formatted(s.session(), phone, cnicFile, phoneFile);
		s.owner().post("/api/v1/buybacks", withoutCnic).andExpect(status().isBadRequest());
		// The till has 5,000: not enough cash to pay 95,000.
		s.owner().post("/api/v1/buybacks", withoutCnic.replace("\"sellerCnic\":\"\"", "\"sellerCnic\":\"35202-1234567-1\""))
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("not_enough_cash"));
		s.owner().post("/api/v1/till-sessions/" + s.session() + "/cash-movements",
				"{\"kind\":\"IN\",\"amount\":\"100000\",\"reason\":\"From safe\"}").andExpect(status().isCreated());
		s.owner().post("/api/v1/buybacks", withoutCnic.replace("\"sellerCnic\":\"\"", "\"sellerCnic\":\"35202-1234567-1\""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.condition").value("USED"))
			.andExpect(jsonPath("$.sellerCnic").value("35202-1234567-1"));
		assertThat(s.t().averageCost(phone)).isEqualTo("95000.00");
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"serials":["353000111222333"]}],
				 "payments":[{"method":"CASH","amount":"110000"}]}""".formatted(s.session(), phone))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.lines[0].serial").value("353000111222333"))
			.andExpect(jsonPath("$.lines[0].warrantyUntil").value(LocalDate.now().plusDays(7).toString())), "$.id");
		// Second till tries the same IMEI: refused (TC-STK-03 single-threaded form).
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"serials":["353000111222333"]}],
				 "payments":[{"method":"CASH","amount":"110000"}]}""".formatted(s.session(), phone))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("serial_unavailable"));
		// IMEI search finds a sold phone; the plain list shows only phones in stock.
		s.owner().get("/api/v1/serials?q=353000111222")
			.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].status").value("SOLD"));
		s.owner().get("/api/v1/serials").andExpect(jsonPath("$.items[?(@.serial == '353000111222333')]").isEmpty());
		s.owner().get("/api/v1/serials?status=ALL").andExpect(jsonPath("$.items[?(@.serial == '353000111222333')]").isNotEmpty());
		Integer line = Trading.read(s.owner().get("/api/v1/sales/" + sale), "$.lines[0].id");
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Changed mind","refundMethod":"CASH","lines":[{"saleLineId":%d,"condition":"USED"}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(status().isCreated());
		s.owner().get("/api/v1/serials/353000111222333")
			.andExpect(jsonPath("$.status").value("IN_STOCK"))
			.andExpect(jsonPath("$.history.length()").value(3));
		s.owner().post("/api/v1/warranty-claims", "{\"serial\":\"353000111222333\",\"issue\":\"Screen flicker\"}")
			.andExpect(status().isCreated()).andExpect(jsonPath("$.inWarranty").value(false));
	}

	@Test
	void bookSetTakesStockFromEachBook() throws Exception {
		Setup s = setup("books");
		long english = s.t().product("English 5", "500", "0");
		long maths = s.t().product("Maths 5", "600", "0");
		s.t().receive(s.supplier(), s.branch(), english, "10", "350");
		s.t().receive(s.supplier(), s.branch(), maths, "10", "420");
		long set = s.t().product("Class 5 set", "1050", "0", "BUNDLE", null);
		TestDatabase.superuser().update("""
				insert into bundle_item (shop_id, bundle_product_id, item_product_id, qty) values (?, ?, ?, 1), (?, ?, ?, 1)""",
				s.shop().id(), set, english, s.shop().id(), set, maths);
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"2100"}]}"""
			.formatted(s.session(), set)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.costTotal").value("1540.00"));
		assertThat(s.t().onHand(english, s.branch())).isEqualTo("8.000");
		assertThat(s.t().onHand(maths, s.branch())).isEqualTo("8.000");
	}

	@Test
	void quotationConvertsKeepingPricesAndCheckingStock() throws Exception {
		Setup s = setup("hardware");
		long pipe = s.t().product("PVC pipe", "900", "0");
		s.t().receive(s.supplier(), s.branch(), pipe, "5", "700");
		Integer quote = Trading.read(s.owner().post("/api/v1/quotations", """
				{"branchId":%d,"customerName":"House 12","lines":[{"productId":%d,"qty":"4","price":"850"}]}"""
			.formatted(s.branch(), pipe)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("3400.00")), "$.id");
		s.owner().patch("/api/v1/products/" + pipe, "{\"price\":\"950\",\"priceChangeReason\":\"New rate\"}")
			.andExpect(status().isOk());
		s.owner().postWithKey("/api/v1/quotations/" + quote + "/convert", """
				{"tillSessionId":%d,"payments":[{"method":"CASH","amount":"3400"}]}""".formatted(s.session()),
				Trading.key()).andExpect(status().isCreated()).andExpect(jsonPath("$.total").value("3400.00"));
		s.owner().get("/api/v1/quotations/" + quote).andExpect(jsonPath("$.status").value("CONVERTED"));
	}

	@Test
	void profitOfEarlierSaleUnchangedAfterCostMoves() throws Exception {
		Setup s = setup("general");
		long item = s.t().product("Router", "2500", "0");
		s.t().receive(s.supplier(), s.branch(), item, "10", "1850");
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"2500"}]}"""
			.formatted(s.session(), item)), "$.id");
		s.t().receive(s.supplier(), s.branch(), item, "5", "1910");
		s.owner().get("/api/v1/sales/" + sale).andExpect(jsonPath("$.costTotal").value("1850.00"))
			.andExpect(jsonPath("$.profit").value("650.00"));
	}

	@Test
	void posCatalogUsesEtag() throws Exception {
		Setup s = setup("super_store");
		s.t().product("Soap", "120", "0");
		var first = s.owner().get("/api/v1/pos/catalog").andExpect(status().isOk());
		String etag = first.andReturn().getResponse().getHeader("ETag");
		assertThat(etag).isNotBlank();
		var again = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/pos/catalog")
			.cookie(s.owner().sessionCookie())
			.header("If-None-Match", etag));
		again.andExpect(status().isNotModified());
	}
}
