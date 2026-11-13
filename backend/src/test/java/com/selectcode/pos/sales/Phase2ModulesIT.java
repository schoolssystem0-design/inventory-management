package com.selectcode.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase 2 shop-type modules through the API (Stage 4b): scale labels, offers and coupons, loyalty points, repair
 * jobs, installments, prescriptions and the controlled register, challans and deliveries.
 */
class Phase2ModulesIT extends IntegrationTest {

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

	static String expectedCash(Setup s) throws Exception {
		return Trading.read(s.owner().get("/api/v1/till-sessions/" + s.session() + "/z-report"), "$.expectedCash");
	}

	@Test
	void scaleLabelFindsTheProductByItsScaleCode() throws Exception {
		Setup s = setup("super_store");
		long mutton = s.t().product("Mutton", "1800", "0", "STANDARD", "\"scaleCode\":\"123\"");
		s.owner().get("/api/v1/products/" + mutton).andExpect(jsonPath("$.scaleCode").value("00123"));
		s.owner().get("/api/v1/products/lookup?code=2100123015009").andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(mutton));
		// Wrong check digit: not a scale label, not found.
		s.owner().get("/api/v1/products/lookup?code=2100123015008").andExpect(status().isNotFound());
		// Two products cannot share a scale code.
		s.owner().post("/api/v1/products", """
				{"name":"Beef","price":"1200","unitId":%d,"scaleCode":"00123"}""".formatted(s.t().firstUnit()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
		s.owner().get("/api/v1/pos/catalog").andExpect(jsonPath("$.settings.scaleValueKind").value("WEIGHT"))
			.andExpect(jsonPath("$.items[?(@.id==" + mutton + ")].scaleCode").value("00123"));
	}

	@Test
	void offersApplyOnTheServerAndCouponsNeedTheirCode() throws Exception {
		Setup s = setup("super_store");
		long soap = s.t().product("Soap", "50", "20");
		long rice = s.t().product("Rice", "200", "120");
		s.t().receive(s.supplier(), s.branch(), soap, "100", "20");
		s.t().receive(s.supplier(), s.branch(), rice, "100", "120");
		s.owner().post("/api/v1/promotions", """
				{"name":"Soap 2+1","kind":"BUY_GET","buyQty":"2","getQty":"1","scope":"PRODUCTS","productIds":[%d]}"""
			.formatted(soap)).andExpect(status().isCreated()).andExpect(jsonPath("$.summary").value("Buy 2 get 1 free"))
			.andExpect(jsonPath("$.runningNow").value(true));
		Integer coupon = Trading.read(s.owner().post("/api/v1/promotions", """
				{"name":"Ten off","kind":"PERCENT","value":"10","couponCode":"save10","maxUses":1}""")
			.andExpect(status().isCreated()).andExpect(jsonPath("$.couponCode").value("SAVE10")), "$.id");
		// 3 soap = 150 with one free → 100, no approval needed even though it is 33 % off.
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"3"}],"payments":[{"method":"CASH","amount":"100"}]}"""
			.formatted(s.session(), soap)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("100.00"))
			.andExpect(jsonPath("$.promoDiscount").value("50.00"))
			.andExpect(jsonPath("$.lines[0].offerName").value("Soap 2+1"))
			.andExpect(jsonPath("$.approvalNote").doesNotExist());
		// A typed line discount wins over the offer.
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"3","discount":"5"}],"payments":[{"method":"CASH","amount":"145"}]}"""
			.formatted(s.session(), soap)).andExpect(status().isCreated()).andExpect(jsonPath("$.total").value("145.00"))
			.andExpect(jsonPath("$.promoDiscount").value("0.00"));
		// Coupon: unknown code refused; right code takes 10 % off rice.
		sell(s.owner(), """
				{"tillSessionId":%d,"couponCode":"NOPE","lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"200"}]}"""
			.formatted(s.session(), rice)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("coupon_invalid"));
		s.owner().get("/api/v1/pos/coupons/save10").andExpect(status().isOk()).andExpect(jsonPath("$.offerId").value(coupon));
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"couponCode":"save10","lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"180"}]}"""
			.formatted(s.session(), rice)).andExpect(status().isCreated()).andExpect(jsonPath("$.total").value("180.00"))
			.andExpect(jsonPath("$.couponCode").value("SAVE10")), "$.id");
		// Used once (its limit): no longer running.
		s.owner().get("/api/v1/pos/coupons/SAVE10").andExpect(status().isUnprocessableContent());
		// Cancelling the sale gives the use back.
		s.owner().post("/api/v1/sales/" + sale + "/cancel", "{\"reason\":\"Test\"}").andExpect(status().isOk());
		s.owner().get("/api/v1/pos/coupons/SAVE10").andExpect(status().isOk());
		// Offers are sent to the till (coupon codes are not).
		s.owner().get("/api/v1/pos/catalog").andExpect(jsonPath("$.offers.length()").value(2))
			.andExpect(jsonPath("$.offers[?(@.coupon==true)].name").value("Ten off"));
	}

	@Test
	void loyaltyPointsAreEarnedUsedTakenBackAndGivenBack() throws Exception {
		Setup s = setup("super_store");
		long tea = s.t().product("Tea pack", "500", "300");
		s.t().receive(s.supplier(), s.branch(), tea, "50", "300");
		long customer = s.t().customer("Sana", "0300-5556667", "0");
		s.owner().post("/api/v1/customers/" + customer + "/loyalty/adjust", "{\"points\":200,\"note\":\"Opening points\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.points").value(200));
		// Rs 1,000: 150 points (Rs 150) + Rs 850 cash → earns 8 points (one per Rs 100).
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"2"}],
				 "payments":[{"method":"POINTS","amount":"150"},{"method":"CASH","amount":"850"}]}"""
			.formatted(s.session(), customer, tea)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.pointsUsed").value(150)).andExpect(jsonPath("$.pointsEarned").value(8))
			.andExpect(jsonPath("$.payments[?(@.method=='POINTS')].amount").value("150.00")), "$.id");
		s.owner().get("/api/v1/customers/" + customer + "/loyalty").andExpect(jsonPath("$.points").value(58));
		// Not enough points, and below the minimum.
		sell(s.owner(), """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"POINTS","amount":"100"},{"method":"CASH","amount":"400"}]}"""
			.formatted(s.session(), customer, tea)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("points_short"));
		// Cancel: 150 back, 8 taken back.
		s.owner().post("/api/v1/sales/" + sale + "/cancel", "{\"reason\":\"Test\"}").andExpect(status().isOk());
		s.owner().get("/api/v1/customers/" + customer + "/loyalty").andExpect(jsonPath("$.points").value(200));
		// Return half of a Rs 1,000 sale: 5 of its 10 points come back off.
		Integer second = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"1000"}]}"""
			.formatted(s.session(), customer, tea)).andExpect(status().isCreated()), "$.id");
		Integer line = Trading.read(s.owner().get("/api/v1/sales/" + second), "$.lines[0].id");
		s.owner().postWithKey("/api/v1/sales/" + second + "/returns", """
				{"tillSessionId":%d,"reason":"Extra","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(status().isCreated());
		s.owner().get("/api/v1/customers/" + customer + "/loyalty").andExpect(jsonPath("$.points").value(205));
		s.owner().get("/api/v1/customers/" + customer + "/loyalty/history")
			.andExpect(jsonPath("$.items[0].kind").value("TAKE_BACK")).andExpect(jsonPath("$.items[0].points").value(-5));
	}

	@Test
	void repairJobFromBookingToInvoice() throws Exception {
		Setup s = setup("mobile");
		long screen = s.t().product("Screen A10", "2500", "1500");
		s.t().receive(s.supplier(), s.branch(), screen, "5", "1500");
		String cashBefore = expectedCash(s);
		Integer job = Trading.read(s.owner().post("/api/v1/repairs", """
				{"customerName":"Bilal","customerPhone":"0301-2223334","device":"Samsung A10","serial":"356789012345678",
				 "problem":"Broken screen","accessories":"No charger","estimate":"3000","advance":"500","advanceMethod":"CASH",
				 "tillSessionId":%d}""".formatted(s.session())).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(jsonPath("$.number").exists()), "$.id");
		assertThat(new BigDecimal(expectedCash(s))).isEqualByComparingTo(new BigDecimal(cashBefore).add(new BigDecimal("500")));
		// Not ready yet: cannot be handed back.
		s.owner().postWithKey("/api/v1/repairs/" + job + "/deliver", "{\"tillSessionId\":" + s.session() + "}",
				Trading.key()).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("repair_not_ready"));
		s.owner().post("/api/v1/repairs/" + job + "/parts", "{\"productId\":%d,\"qty\":\"1\"}".formatted(screen))
			.andExpect(status().isOk()).andExpect(jsonPath("$.partsTotal").value("2500.00"));
		s.owner().patch("/api/v1/repairs/" + job, "{\"status\":\"READY\",\"labour\":\"300\",\"diagnosis\":\"Screen replaced\"}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.total").value("2800.00"))
			.andExpect(jsonPath("$.balance").value("2300.00"));
		Integer saleId = Trading.read(s.owner().postWithKey("/api/v1/repairs/" + job + "/deliver", """
				{"tillSessionId":%d,"payments":[{"method":"CASH","amount":"2300"}]}""".formatted(s.session()), Trading.key())
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED")), "$.saleId");
		s.owner().get("/api/v1/sales/" + saleId).andExpect(jsonPath("$.total").value("2800.00"))
			.andExpect(jsonPath("$.due").value("0.00"))
			.andExpect(jsonPath("$.payments[?(@.method=='ADVANCE')].amount").value("500.00"))
			.andExpect(jsonPath("$.lines[?(@.productName=='Repair labour')].total").value("300.00"));
		assertThat(s.t().onHand(screen, s.branch())).isEqualTo("4.000");
		s.owner().get("/api/v1/repairs?status=DELIVERED&q=356789012345678").andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void installmentPlanScheduleReceiptsAndLateFees() throws Exception {
		Setup s = setup("electronics");
		long tv = s.t().product("LED TV 43", "100000", "70000");
		s.t().receive(s.supplier(), s.branch(), tv, "3", "70000");
		long customer = s.t().customer("Imran", "0333-1234567", "200000");
		Integer sale = Trading.read(sell(s.owner(), """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],
				 "payments":[{"method":"CASH","amount":"20000"},{"method":"ACCOUNT"}]}"""
			.formatted(s.session(), customer, tv)).andExpect(status().isCreated()).andExpect(jsonPath("$.due").value("80000.00")),
				"$.id");
		// Guarantor is required.
		s.owner().post("/api/v1/installment-plans", "{\"saleId\":%d,\"months\":4}".formatted(sale))
			.andExpect(status().isBadRequest());
		Integer plan = Trading.read(s.owner().post("/api/v1/installment-plans", """
				{"saleId":%d,"months":4,"markupPercent":"10","graceDays":3,"lateFeePerDay":"100",
				 "guarantorName":"Asif","guarantorCnic":"35202-1234567-1","guarantorPhone":"0300-9998887"}""".formatted(sale))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.markup").value("8000.00"))
			.andExpect(jsonPath("$.total").value("88000.00")).andExpect(jsonPath("$.dues.length()").value(4))
			.andExpect(jsonPath("$.dues[0].amount").value("22000.00")), "$.id");
		s.owner().get("/api/v1/customers/" + customer).andExpect(jsonPath("$.balance").value("88000.00"));
		// The first installment falls due 10 days ago: 7 days after its 3 grace days → Rs 700 late fee.
		TestDatabase.superuser().update("update installment_due set due_on = ? where plan_id = ? and no = 1",
				java.sql.Date.valueOf(java.time.LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE).minusDays(10)), plan);
		s.owner().get("/api/v1/installments/due?overdue=true").andExpect(jsonPath("$[0].daysLate").value(7))
			.andExpect(jsonPath("$[0].lateFee").value("700.00"));
		s.owner().postWithKey("/api/v1/installment-plans/" + plan + "/receive", """
				{"amount":"22700","method":"CASH","tillSessionId":%d}""".formatted(s.session()), Trading.key())
			.andExpect(status().isOk()).andExpect(jsonPath("$.lateFees").value("700.00"))
			.andExpect(jsonPath("$.dues[0].state").value("PAID")).andExpect(jsonPath("$.paid").value("22700.00"))
			.andExpect(jsonPath("$.left").value("66000.00"));
		s.owner().get("/api/v1/sales/" + sale).andExpect(jsonPath("$.due").value("57300.00"));
		s.owner().get("/api/v1/customers/" + customer).andExpect(jsonPath("$.balance").value("66000.00"));
		// More than is left is refused; a plan with payments cannot be cancelled.
		s.owner().postWithKey("/api/v1/installment-plans/" + plan + "/receive",
				"{\"amount\":\"70000\",\"method\":\"CASH\",\"tillSessionId\":" + s.session() + "}", Trading.key())
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("more_than_left"));
		s.owner().post("/api/v1/installment-plans/" + plan + "/cancel", "{\"reason\":\"x\"}")
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("plan_has_payments"));
		// Markup and late fee are other income in profit and loss.
		s.owner().get("/api/v1/cash/profit-loss").andExpect(jsonPath("$.total.otherIncome").value("8700.00"));
	}

	@Test
	void prescriptionsAndTheControlledRegister() throws Exception {
		Setup s = setup("medical");
		long unit = s.t().firstUnit();
		long rx = s.t().product("Augmentin 625", "450", "300", "STANDARD",
				"\"pharma\":{\"formula\":\"Amoxicillin\",\"schedule\":\"RX\"}");
		long ctrl = s.t().product("Xanax 0.5", "200", "100", "STANDARD",
				"\"pharma\":{\"formula\":\"Alprazolam\",\"schedule\":\"CONTROLLED\"}");
		s.t().receive(s.supplier(), s.branch(), rx, "20", "300");
		s.t().receive(s.supplier(), s.branch(), ctrl, "20", "100");
		assertThat(unit).isPositive();
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"450"}]}"""
			.formatted(s.session(), rx)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("prescription_required"));
		sell(s.owner(), """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"450"}],
				 "prescription":{"patientName":"Ayesha","doctorName":"Dr. Khan"}}"""
			.formatted(s.session(), rx)).andExpect(status().isCreated()).andExpect(jsonPath("$.prescriptionId").exists());
		// Controlled: a cashier needs the pharmacist's PIN.
		ApiClient cashier = userWithRole(s.shop(), "CASHIER", unique("cash"));
		long till2 = TestDatabase.superuser().queryForObject("""
				insert into till (shop_id, branch_id, name) values (?, ?, 'Till 2') returning id""", Long.class,
				s.shop().id(), s.branch());
		Integer session2 = Trading.read(cashier.post("/api/v1/till-sessions", "{\"tillId\":" + till2 + "}")
			.andExpect(status().isCreated()), "$.id");
		String body = """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"2"}],"payments":[{"method":"CASH","amount":"400"}],
				 "prescription":{"patientName":"Hamid","doctorName":"Dr. Rana","doctorRegNo":"PMC-1234"}}"""
			.formatted(session2, ctrl);
		cashier.postWithKey("/api/v1/sales", body, Trading.key()).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("approval_required"));
		ApiClient pharmacist = userWithRole(s.shop(), "PHARMACIST", "pharma1");
		pharmacist.post("/api/v1/auth/pin", "{\"pin\":\"5050\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().is2xxSuccessful());
		Integer sale = Trading.read(cashier.postWithHeaders("/api/v1/sales", body, "Idempotency-Key", Trading.key(),
				"X-Approval-User", "pharma1", "X-Approval-Pin", "5050").andExpect(status().isCreated()), "$.id");
		List<Object> register = Trading.read(s.owner().get("/api/v1/controlled-register"), "$");
		assertThat(register).hasSize(1);
		s.owner().get("/api/v1/controlled-register").andExpect(jsonPath("$[0].qty").value("2"))
			.andExpect(jsonPath("$[0].patientName").value("Hamid"))
			.andExpect(jsonPath("$[0].pharmacistName").exists());
		Integer line = Trading.read(s.owner().get("/api/v1/sales/" + sale), "$.lines[0].id");
		s.owner().postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"Not needed","refundMethod":"CASH","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(s.session(), line), Trading.key()).andExpect(status().isCreated());
		s.owner().get("/api/v1/controlled-register").andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[1].qty").value("-1"));
	}

	@Test
	void challanSendsStockOutThenBillsItAndDeliveriesAreTracked() throws Exception {
		Setup s = setup("hardware");
		long cement = s.t().product("Cement bag", "1450", "1300");
		s.t().receive(s.supplier(), s.branch(), cement, "100", "1300");
		long customer = s.t().customer("Contractor Asif", "0300-1112223", "500000");
		Integer challan = Trading.read(s.owner().post("/api/v1/challans", """
				{"customerId":%d,"address":"Plot 12, DHA","lines":[{"productId":%d,"qty":"10","price":"1400"}]}"""
			.formatted(customer, cement)).andExpect(status().isCreated()).andExpect(jsonPath("$.total").value("14000.00")),
				"$.id");
		assertThat(s.t().onHand(cement, s.branch())).isEqualTo("90.000");
		Integer delivery = Trading.read(s.owner().post("/api/v1/deliveries", """
				{"challanId":%d,"scheduledOn":"%s","driver":"Rafiq","vehicle":"LES-1234"}"""
			.formatted(challan, java.time.LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE))).andExpect(status().isCreated())
			.andExpect(jsonPath("$.address").value("Plot 12, DHA")), "$.id");
		s.owner().patch("/api/v1/deliveries/" + delivery, "{\"status\":\"ON_THE_WAY\"}").andExpect(jsonPath("$.status").value("ON_THE_WAY"));
		s.owner().patch("/api/v1/deliveries/" + delivery, "{\"status\":\"DELIVERED\",\"receivedBy\":\"Site manager\"}")
			.andExpect(jsonPath("$.status").value("DELIVERED"));
		// Billed on account at the challan price; stock stays at 90 (back 10, sold 10).
		Integer sale = Trading.read(s.owner().postWithKey("/api/v1/challans/" + challan + "/convert",
				"{\"tillSessionId\":%d,\"payments\":[{\"method\":\"ACCOUNT\"}]}".formatted(s.session()), Trading.key())
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVOICED")), "$.saleId");
		s.owner().get("/api/v1/sales/" + sale).andExpect(jsonPath("$.total").value("14000.00"))
			.andExpect(jsonPath("$.due").value("14000.00"));
		assertThat(s.t().onHand(cement, s.branch())).isEqualTo("90.000");
		s.owner().get("/api/v1/deliveries/" + delivery).andExpect(jsonPath("$.saleId").value(sale));
		// A cancelled challan puts its stock back.
		Integer second = Trading.read(s.owner().post("/api/v1/challans", """
				{"customerName":"Walk-in","lines":[{"productId":%d,"qty":"5"}]}""".formatted(cement))
			.andExpect(status().isCreated()), "$.id");
		assertThat(s.t().onHand(cement, s.branch())).isEqualTo("85.000");
		s.owner().post("/api/v1/challans/" + second + "/cancel", "{\"reason\":\"Order cancelled\"}")
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(s.t().onHand(cement, s.branch())).isEqualTo("90.000");
	}

	@Test
	void modulesAreRefusedWhenSwitchedOff() throws Exception {
		Setup s = setup("general");
		s.owner().get("/api/v1/repairs").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("module_disabled"));
		s.owner().get("/api/v1/promotions").andExpect(status().isForbidden());
		s.owner().get("/api/v1/installment-plans").andExpect(status().isForbidden());
		s.owner().get("/api/v1/controlled-register").andExpect(status().isForbidden());
		s.owner().get("/api/v1/challans").andExpect(status().isForbidden());
	}
}
