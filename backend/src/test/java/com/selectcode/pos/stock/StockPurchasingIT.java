package com.selectcode.pos.stock;

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
 * Stock engine and purchasing through the API: average cost (TC-PUR-01), PO with bonus and short close (TC-PUR-02),
 * purchase returns (TC-PUR-03), reorder (TC-PUR-04), opening stock (TC-ONB-02), adjustments with approval
 * (TC-STK-04), counts during trading (TC-STK-05), transfers in transit (TC-STK-06), history (TC-STK-07), cartons and
 * coils (TC-SUP-02, TC-HRD-01), IMEI receipts (TC-MOB-01) and supplier payments oldest first (PUR-06).
 */
class StockPurchasingIT extends IntegrationTest {

	@Test
	void averageCostMovesOnEachReceiptAndHistoryShowsBalances() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long supplier = t.supplier("Hilal Foods");
		long product = t.product("Cooking oil 5L", "2100", "0");
		t.receive(supplier, branch, product, "10", "1850");
		assertThat(t.averageCost(product)).isEqualTo("1850.00");
		t.receive(supplier, branch, product, "5", "1910");
		assertThat(t.averageCost(product)).isEqualTo("1870.00");
		assertThat(t.onHand(product, branch)).isEqualTo("15.000");

		// Stock history: each movement with balance after, newest first (TC-STK-07).
		var history = shop.owner().get("/api/v1/stock/movements?productId=" + product).andExpect(status().isOk());
		List<String> balances = Trading.read(history, "$.items[*].balanceAfter");
		assertThat(balances).containsExactly("15.000", "10.000");
		List<String> types = Trading.read(history, "$.items[*].type");
		assertThat(types).containsOnly("PURCHASE");
		// Supplier owes: 18,500 + 9,550
		shop.owner().get("/api/v1/suppliers/" + supplier).andExpect(jsonPath("$.balance").value("28050.00"));
		assertStockMatchesHistory(shop);
	}

	@Test
	void purchaseOrderWithBonusReceivedShortThenClosed() throws Exception {
		TestShop shop = newShop("medical", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long supplier = t.supplier("Getz Pharma");
		long product = t.product("Calpol syrup", "120", "0");
		Integer po = Trading.read(owner.post("/api/v1/purchase-orders", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"50","unitCost":"80"}]}"""
			.formatted(supplier, branch, product)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ORDERED"))
			.andExpect(jsonPath("$.total").value("4000.00")), "$.id");
		Integer poLine = Trading.read(owner.get("/api/v1/purchase-orders/" + po), "$.lines[0].id");

		// 48 billed + 2 bonus arrive: stock +50, bill for 48, cost per unit 48×80/50 = 76.80.
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"poId":%d,"supplierBillNo":"GP-7781","lines":[{"productId":%d,"poLineId":%d,"qty":"48",
				 "bonusQty":"2","unitCost":"80"}]}""".formatted(supplier, po, product, poLine), Trading.key())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("3840.00"))
			.andExpect(jsonPath("$.lines[0].baseQty").value("50.000"))
			.andExpect(jsonPath("$.lines[0].baseUnitCost").value("76.80"));
		assertThat(t.onHand(product, branch)).isEqualTo("50.000");
		owner.get("/api/v1/purchase-orders/" + po)
			.andExpect(jsonPath("$.status").value("PARTIAL"))
			.andExpect(jsonPath("$.lines[0].qtyOpen").value("2.000"));
		owner.post("/api/v1/purchase-orders/" + po + "/close", "{\"reason\":\"2 short, not coming\"}")
			.andExpect(jsonPath("$.status").value("CLOSED"));
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"poId":%d,"lines":[{"productId":%d,"qty":"2","unitCost":"80"}]}"""
			.formatted(supplier, po, product), Trading.key()).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("po_closed"));
	}

	@Test
	void sameIdempotencyKeyReceivesOnce() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long supplier = t.supplier("Wholesaler");
		long product = t.product("Pen", "50", "0");
		String body = """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"10","unitCost":"30"}]}"""
			.formatted(supplier, branch, product);
		String key = Trading.key();
		Integer first = Trading.read(shop.owner().postWithKey("/api/v1/grns", body, key).andExpect(status().isCreated()),
				"$.id");
		Integer second = Trading.read(shop.owner().postWithKey("/api/v1/grns", body, key).andExpect(status().isCreated()),
				"$.id");
		assertThat(second).isEqualTo(first);
		assertThat(t.onHand(product, branch)).isEqualTo("10.000");
	}

	@Test
	void cartonsReceivedAndCoilsInMetres() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long supplier = t.supplier("Nestle distributor");
		long carton = t.unitId("carton");
		long piece = t.unitId("piece");
		long juice = ((Integer) Trading.read(owner.post("/api/v1/products", """
				{"name":"Juice 200ml","price":"60","costPrice":"0","unitId":%d,
				 "packUnits":[{"unitId":%d,"factor":"24","price":"1300"}]}""".formatted(piece, carton))
			.andExpect(status().isCreated()), "$.id")).longValue();
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitId":%d,"qty":"2","unitCost":"1200"}]}"""
			.formatted(supplier, branch, juice, carton), Trading.key())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.lines[0].baseQty").value("48.000"))
			.andExpect(jsonPath("$.lines[0].baseUnitCost").value("50.00"));
		assertThat(t.onHand(juice, branch)).isEqualTo("48.000");
		assertThat(t.averageCost(juice)).isEqualTo("50.00");

		// A coil of 90 m (TC-HRD-01, the selling half is in SalesIT): metres allow decimals.
		long metre = t.unitId("metre");
		long wire = ((Integer) Trading.read(owner.post("/api/v1/products", """
				{"name":"Wire 7/29","price":"95","costPrice":"0","unitId":%d}""".formatted(metre))
			.andExpect(status().isCreated()), "$.id")).longValue();
		t.receive(supplier, branch, wire, "90", "70");
		owner.post("/api/v1/adjustments", """
				{"branchId":%d,"reason":"OWN_USE","lines":[{"productId":%d,"qty":"12.5"}]}""".formatted(branch, wire))
			.andExpect(status().isCreated());
		assertThat(t.onHand(wire, branch)).isEqualTo("77.500");
	}

	@Test
	void openingStockIsNotAPurchaseAndSetsCost() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long product = t.product("Kettle", "3500", "0");
		shop.owner().post("/api/v1/opening-stock", """
				{"branchId":%d,"lines":[{"productId":%d,"qty":"6","unitCost":"2800"}]}""".formatted(branch, product))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reason").value("OPENING"))
			.andExpect(jsonPath("$.valueIn").value("16800.00"));
		assertThat(t.onHand(product, branch)).isEqualTo("6.000");
		assertThat(t.averageCost(product)).isEqualTo("2800.00");
		shop.owner().get("/api/v1/stock/movements?productId=" + product).andExpect(jsonPath("$.items[0].type").value("OPENING"));
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from grn where shop_id = ?", Integer.class,
				shop.id())).isZero();
	}

	@Test
	void writeOffAboveLimitNeedsManagerPin() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long supplier = t.supplier("S");
		long product = t.product("Glass bowl", "900", "0");
		t.receive(supplier, branch, product, "20", "600");
		// A stock keeper role with stock.adjust (custom), no approvals: 10 × 600 = 6,000 is above the 5,000 limit.
		long roleId = TestDatabase.superuser().queryForObject("""
				insert into role (shop_id, name, permissions) values (?, 'Store', '{stock.view,stock.adjust,catalog.view}')
				returning id""", Long.class, shop.id());
		String username = unique("st");
		long userId = createUser(shop, "CASHIER", username);
		TestDatabase.superuser().update("update app_user set role_id = ? where id = ?", roleId, userId);
		ApiClient keeper = client();
		keeper.login(shop.code(), username, PASSWORD).andExpect(status().isOk());
		String body = """
				{"branchId":%d,"reason":"DAMAGED","note":"Fell from shelf","lines":[{"productId":%d,"qty":"10"}]}"""
			.formatted(branch, product);
		keeper.post("/api/v1/adjustments", body)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("approval_required"));
		// Manager approves with PIN.
		shop.owner().post("/api/v1/auth/pin", "{\"pin\":\"4826\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().is2xxSuccessful());
		keeperWithApproval(keeper, body, "owner", "0000").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("pin_wrong"));
		keeperWithApproval(keeper, body, "owner", "4826").andExpect(status().isCreated())
			.andExpect(jsonPath("$.valueOut").doesNotExist());
		assertThat(t.onHand(product, branch)).isEqualTo("10.000");
		shop.owner().get("/api/v1/adjustments").andExpect(jsonPath("$.items[0].valueOut").value("6000.00"));
	}

	private org.springframework.test.web.servlet.ResultActions keeperWithApproval(ApiClient keeper, String body,
			String approver, String pin) throws Exception {
		return keeper.postWithHeaders("/api/v1/adjustments", body, "X-Approval-User", approver, "X-Approval-Pin", pin);
	}

	@Test
	void transferIsInTransitUntilReceived() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long main = t.branch(0);
		long godown = t.branch(1);
		long supplier = t.supplier("S");
		long product = t.product("Cement bag", "1450", "0");
		t.receive(supplier, godown, product, "120", "1300");
		Integer transfer = Trading.read(owner.post("/api/v1/transfers", """
				{"fromBranchId":%d,"toBranchId":%d,"lines":[{"productId":%d,"qty":"20"}]}"""
			.formatted(godown, main, product)).andExpect(status().isCreated()), "$.id");
		assertThat(t.onHand(product, godown)).isEqualTo("100.000");
		assertThat(t.onHand(product, main)).isEqualTo("0.000");
		owner.get("/api/v1/stock/products/" + product)
			.andExpect(jsonPath("$.branches[?(@.branchId==" + main + ")].inTransit").value("20.000"));
		Integer line = Trading.read(owner.get("/api/v1/transfers/" + transfer), "$.lines[0].id");
		owner.post("/api/v1/transfers/" + transfer + "/receive", "{\"lines\":[{\"lineId\":" + line + ",\"qtyReceived\":\"19\"}]}")
			.andExpect(jsonPath("$.status").value("RECEIVED"))
			.andExpect(jsonPath("$.shortQty").value("1.000"));
		assertThat(t.onHand(product, main)).isEqualTo("19.000");
		owner.post("/api/v1/transfers/" + transfer + "/receive").andExpect(status().isUnprocessableContent());
		assertStockMatchesHistory(shop);
	}

	@Test
	void countAccountsForMovementsDuringTheCount() throws Exception {
		TestShop shop = newShop("cosmetics", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long supplier = t.supplier("S");
		long lipstick = t.product("Lipstick red", "800", "0");
		long cream = t.product("Face cream", "650", "0");
		t.receive(supplier, branch, lipstick, "10", "400");
		t.receive(supplier, branch, cream, "5", "300");
		Integer count = Trading.read(owner.post("/api/v1/stock-counts", "{\"branchId\":" + branch + "}")
			.andExpect(status().isCreated()), "$.id");
		// While counting, 2 lipsticks leave the shelf (own use), before the lipstick shelf is counted.
		owner.post("/api/v1/adjustments", """
				{"branchId":%d,"reason":"OWN_USE","lines":[{"productId":%d,"qty":"2"}]}""".formatted(branch, lipstick))
			.andExpect(status().isCreated());
		// Shelf shows 7 lipsticks (one missing) and 5 creams.
		owner.put("/api/v1/stock-counts/" + count + "/lines", """
				{"entries":[{"productId":%d,"countedQty":"7"},{"productId":%d,"countedQty":"5"}]}"""
			.formatted(lipstick, cream))
			.andExpect(jsonPath("$.items[?(@.productId==" + lipstick + ")].expectedQty").value("8.000"))
			.andExpect(jsonPath("$.items[?(@.productId==" + lipstick + ")].difference").value("-1.000"))
			.andExpect(jsonPath("$.lossValue").value("400.00"));
		owner.post("/api/v1/stock-counts/" + count + "/post").andExpect(jsonPath("$.status").value("POSTED"));
		assertThat(t.onHand(lipstick, branch)).isEqualTo("7.000");
		assertThat(t.onHand(cream, branch)).isEqualTo("5.000");
		assertStockMatchesHistory(shop);
	}

	@Test
	void imeiPurchaseRefusesDuplicatesAndSearchShowsSupplier() throws Exception {
		TestShop shop = newShop("mobile", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long supplier = t.supplier("Samsung distributor");
		long phone = t.product("Samsung A15 128GB", "52000", "0", "SERIAL", null);
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitCost":"47000","serials":[
				 {"serial":"356789012345671","serial2":"356789012345689","colour":"Black","storage":"128GB","ptaStatus":"APPROVED"},
				 {"serial":"356789012345697","colour":"Blue","storage":"128GB","ptaStatus":"APPROVED"}]}]}"""
			.formatted(supplier, branch, phone), Trading.key()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.lines[0].baseQty").value("2.000"));
		assertThat(t.onHand(phone, branch)).isEqualTo("2.000");
		// Mistyped: the second IMEI of the first phone is already in the system.
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitCost":"47000","serials":[{"serial":"356789012345689"}]}]}"""
			.formatted(supplier, branch, phone), Trading.key()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("duplicate_serial"));
		owner.get("/api/v1/serials/356789012345689")
			.andExpect(jsonPath("$.serial").value("356789012345671"))
			.andExpect(jsonPath("$.supplierName").value("Samsung distributor"))
			.andExpect(jsonPath("$.status").value("IN_STOCK"));
		assertThat(t.onHand(phone, branch)).isEqualTo("2.000");
	}

	@Test
	void purchaseReturnOfExpiredBatchAndPaymentsOldestFirst() throws Exception {
		TestShop shop = newShop("medical", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long supplier = t.supplier("Distributor");
		long syrup = t.product("Cough syrup", "250", "0", "BATCH", null);
		var expired = java.time.LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE).minusDays(10);
		Integer grn1 = Trading.read(owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"billDate":"%s","lines":[{"productId":%d,"qty":"12","unitCost":"150",
				 "batchNo":"B-OLD","expiry":"%s"}]}""".formatted(supplier, branch, java.time.LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE).minusDays(60),
				syrup, expired), Trading.key()).andExpect(status().isCreated()), "$.id");
		owner.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"10","unitCost":"160",
				 "batchNo":"B-NEW","expiry":"%s"}]}""".formatted(supplier, branch, syrup,
				java.time.LocalDate.now(com.selectcode.pos.shared.time.ShopTime.ZONE).plusYears(1)), Trading.key()).andExpect(status().isCreated());
		Integer grnLine = Trading.read(owner.get("/api/v1/grns/" + grn1), "$.lines[0].id");
		// 12 expired syrups back to the distributor: stock −12, owed −1,800 (TC-PUR-03).
		owner.post("/api/v1/purchase-returns", """
				{"supplierId":%d,"grnId":%d,"reason":"EXPIRED","lines":[{"grnLineId":%d,"qty":"12"}]}"""
			.formatted(supplier, grn1, grnLine)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.total").value("1800.00"));
		assertThat(t.onHand(syrup, branch)).isEqualTo("10.000");
		owner.get("/api/v1/suppliers/" + supplier).andExpect(jsonPath("$.balance").value("1600.00"));
		owner.post("/api/v1/purchase-returns", """
				{"supplierId":%d,"grnId":%d,"reason":"EXPIRED","lines":[{"grnLineId":%d,"qty":"1"}]}"""
			.formatted(supplier, grn1, grnLine)).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("already_returned"));
		// Payment of 1,000 goes to the oldest open bill.
		owner.postWithKey("/api/v1/supplier-payments", """
				{"supplierId":%d,"amount":"1000","method":"CASH"}""".formatted(supplier), Trading.key())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.allocations.length()").value(1))
			.andExpect(jsonPath("$.balanceAfter").value("600.00"));
		owner.postWithKey("/api/v1/supplier-payments", "{\"supplierId\":" + supplier + ",\"amount\":\"5\"}", null)
			.andExpect(status().isBadRequest());
	}

	@Test
	void reorderSuggestsAndMakesOnePoPerSupplier() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		ApiClient owner = shop.owner();
		Trading t = new Trading(owner);
		long branch = t.defaultBranch();
		long s1 = t.supplier("Supplier One");
		long s2 = t.supplier("Supplier Two");
		long rice = t.product("Rice 5kg", "1500", "0", "STANDARD", "\"reorderLevel\":\"10\"");
		long sugar = t.product("Sugar 1kg", "160", "0", "STANDARD", "\"reorderLevel\":\"5\"");
		t.receive(s1, branch, rice, "3", "1200");
		t.receive(s2, branch, sugar, "2", "140");
		List<Integer> ids = Trading.read(owner.get("/api/v1/reorder"), "$[*].productId");
		assertThat(ids).contains((int) rice, (int) sugar);
		owner.get("/api/v1/reorder").andExpect(jsonPath("$[?(@.productId==" + rice + ")].suggestedQty").value("17.000"));
		owner.post("/api/v1/reorder/purchase-orders", "{\"branchId\":" + branch + "}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.length()").value(2));
		// On order now counts: nothing more is suggested for rice.
		owner.get("/api/v1/reorder").andExpect(jsonPath("$[?(@.productId==" + rice + ")].suggestedQty").value("0.000"));
	}

	static void assertStockMatchesHistory(TestShop shop) throws Exception {
		shop.owner().get("/api/v1/stock/audit").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}
}
