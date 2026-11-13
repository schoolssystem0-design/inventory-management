package com.selectcode.pos.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.catalog.domain.Product;
import com.selectcode.pos.catalog.infra.ProductRepository;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Shop data separation (NFR-01, QA TC-SEC-01). Each isolation layer is tested on its own, then every shop endpoint is
 * called as Shop A with Shop B's ids and must answer 404 without changing anything of Shop B. New endpoints are picked
 * up automatically; a new path variable fails the test until it is mapped to its table below.
 */
class TenantIsolationIT extends IntegrationTest {

	/** Path segment before an {id} → table holding that id. */
	private static final Map<String, String> ID_TABLES = Map.ofEntries(Map.entry("products", "product"),
			Map.entry("categories", "category"), Map.entry("brands", "brand"), Map.entry("units", "unit"),
			Map.entry("branches", "branch"), Map.entry("tills", "till"), Map.entry("users", "app_user"),
			Map.entry("numbering", "numbering"), Map.entry("payment-methods", "payment_method"),
			Map.entry("files", "stored_file"), Map.entry("adjustments", "adjustment"),
			Map.entry("stock-counts", "stock_count"), Map.entry("transfers", "transfer"),
			Map.entry("alerts", "stock_alert"), Map.entry("customers", "customer"), Map.entry("suppliers", "supplier"),
			Map.entry("purchase-orders", "purchase_order"), Map.entry("grns", "grn"),
			Map.entry("purchase-returns", "purchase_return"), Map.entry("supplier-payments", "supplier_payment"),
			Map.entry("till-sessions", "till_session"), Map.entry("sales", "sale"), Map.entry("sale-returns", "sale_return"),
			Map.entry("held-sales", "held_sale"), Map.entry("quotations", "quotation"),
			Map.entry("customer-payments", "customer_payment"), Map.entry("buybacks", "buyback"),
			Map.entry("warranty-claims", "warranty_claim"), Map.entry("expenses", "expense"),
			Map.entry("expense-categories", "expense_category"), Map.entry("size-sets", "size_set"),
			Map.entry("exports", "export_job"), Map.entry("notifications", "notification"),
			Map.entry("promotions", "promotion"), Map.entry("repairs", "repair_job"), Map.entry("parts", "repair_part"),
			Map.entry("installment-plans", "installment_plan"), Map.entry("prescriptions", "prescription"),
			Map.entry("deliveries", "delivery"), Map.entry("challans", "challan"));

	/** Path variables that are not record ids (they act on the caller's own shop only). */
	private static final List<String> NOT_IDS = List.of("/api/v1/modules/{key}", "/api/v1/public/shops/{code}",
			"/api/v1/serials/{serial}", "/api/v1/reports/{key}", "/api/v1/reports/{key}/download",
			"/api/v1/reports/{key}/export", "/api/v1/pos/coupons/{code}");

	/** Switchable modules on for both shops, so every endpoint answers (403 module_disabled is tested elsewhere). */
	static final Map<String, Boolean> ALL_MODULES = Map.ofEntries(Map.entry("serials", true),
			Map.entry("used_buyback", true), Map.entry("warranty", true), Map.entry("variants", true),
			Map.entry("batches", true), Map.entry("pack_units", true), Map.entry("formula", true),
			Map.entry("weighed", true), Map.entry("multi_till", true), Map.entry("quotations", true),
			Map.entry("bundles", true), Map.entry("part_numbers", true), Map.entry("sms", true),
			Map.entry("repairs", true), Map.entry("installments", true), Map.entry("prescriptions", true),
			Map.entry("scale_barcodes", true), Map.entry("promotions", true), Map.entry("loyalty", true),
			Map.entry("delivery", true));

	@Autowired
	ProductRepository products;

	@Autowired
	DataSource dataSource;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	JdbcTemplate app;

	TransactionTemplate tx;

	TestShop shopA;

	TestShop shopB;

	String marker;

	@BeforeEach
	void twoShops() throws Exception {
		app = new JdbcTemplate(dataSource);
		tx = new TransactionTemplate(transactionManager);
		shopA = newShop("super_store", "enterprise");
		shopB = newShop("medical", "enterprise");
		marker = unique("ZZB");
		// Shop B gets one of everything, named with a marker that must never appear in Shop A's answers.
		ApiClient b = shopB.owner();
		b.post("/api/v1/categories", "{\"name\":\"" + marker + " cat\"}").andExpect(status().isCreated());
		b.post("/api/v1/brands", "{\"name\":\"" + marker + " brand\"}").andExpect(status().isCreated());
		b.post("/api/v1/units", "{\"name\":\"" + marker.substring(0, 10) + "\"}").andExpect(status().isCreated());
		Integer unitId = json(b.get("/api/v1/units"), "$[0].id");
		b.post("/api/v1/products", """
				{"name":"%s product","price":"100","costPrice":"60","unitId":%d,"barcodes":["%s"],
				 "pharma":{"formula":"%s formula"}}""".formatted(marker, unitId, marker, marker))
			.andExpect(status().isCreated());
		b.post("/api/v1/users", """
				{"username":"%s","displayName":"%s user","roleId":%d,"password":"Another-Pass-77"}"""
			.formatted(marker.toLowerCase(), marker, roleId(shopB, "CASHIER"))).andExpect(status().isCreated());
		b.post("/api/v1/branches", "{\"name\":\"" + marker + " branch\",\"code\":\"ZB\"}").andExpect(status().isCreated());
		b.upload("/api/v1/files", "PRODUCT_IMAGE", "x.png", png()).andExpect(status().isOk());
		shopAdmin.setModules(shopA.id(), ALL_MODULES);
		shopAdmin.setModules(shopB.id(), ALL_MODULES);
		tradingDocuments(b, unitId);
	}

	/** One of every trading document for Shop B, named with the marker where there is a name. */
	private void tradingDocuments(ApiClient b, Integer unitId) throws Exception {
		Trading t = new Trading(b);
		long main = t.branch(0);
		long godown = t.branch(1);
		long supplier = ((Integer) json(b.post("/api/v1/suppliers", "{\"name\":\"" + marker + " supplier\"}")
			.andExpect(status().isCreated()), "$.id")).longValue();
		long item = ((Integer) json(b.post("/api/v1/products", """
				{"name":"%s item","price":"100","costPrice":"0","unitId":%d,"reorderLevel":"50"}""".formatted(marker, unitId))
			.andExpect(status().isCreated()), "$.id")).longValue();
		long phone = ((Integer) json(b.post("/api/v1/products", """
				{"name":"%s phone","price":"1000","costPrice":"0","unitId":%d,"kind":"SERIAL"}""".formatted(marker, unitId))
			.andExpect(status().isCreated()), "$.id")).longValue();
		long set = ((Integer) json(b.post("/api/v1/products", """
				{"name":"%s set","price":"150","costPrice":"0","unitId":%d,"kind":"BUNDLE"}""".formatted(marker, unitId))
			.andExpect(status().isCreated()), "$.id")).longValue();
		b.put("/api/v1/products/" + set + "/bundle", "{\"items\":[{\"productId\":" + item + ",\"qty\":\"1\"}]}")
			.andExpect(status().isOk());
		b.post("/api/v1/size-sets", "{\"name\":\"" + marker + " sizes\",\"sizes\":[\"S\",\"M\"]}")
			.andExpect(status().isCreated());
		Integer grn = json(b.postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"40","unitCost":"60"},
				 {"productId":%d,"unitCost":"700","serials":[{"serial":"%s1"},{"serial":"%s2"}]}]}"""
			.formatted(supplier, main, item, phone, marker, marker), Trading.key()).andExpect(status().isCreated()), "$.id");
		Integer grnLine = json(b.get("/api/v1/grns/" + grn), "$.lines[0].id");
		b.post("/api/v1/purchase-returns", """
				{"supplierId":%d,"grnId":%d,"reason":"DAMAGED","lines":[{"grnLineId":%d,"qty":"1"}]}"""
			.formatted(supplier, grn, grnLine)).andExpect(status().isCreated());
		b.post("/api/v1/purchase-orders", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"5","unitCost":"60"}]}"""
			.formatted(supplier, main, item)).andExpect(status().isCreated());
		b.postWithKey("/api/v1/supplier-payments", "{\"supplierId\":" + supplier + ",\"amount\":\"100\"}",
				Trading.key()).andExpect(status().isCreated());
		b.post("/api/v1/adjustments", """
				{"branchId":%d,"reason":"DAMAGED","lines":[{"productId":%d,"qty":"1"}]}""".formatted(main, item))
			.andExpect(status().isCreated());
		b.post("/api/v1/transfers", """
				{"fromBranchId":%d,"toBranchId":%d,"lines":[{"productId":%d,"qty":"1"}]}""".formatted(main, godown, item))
			.andExpect(status().isCreated());
		b.post("/api/v1/stock-counts", "{\"branchId\":" + godown + "}").andExpect(status().isCreated());
		long customer = ((Integer) json(b.post("/api/v1/customers", """
				{"name":"%s customer","phone":"03009998887","creditLimit":"100000"}""".formatted(marker))
			.andExpect(status().isCreated()), "$.id")).longValue();
		long session = t.openTill(main, "1000");
		Integer sale = json(b.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"2"},{"productId":%d,"serials":["%s1"]}],
				 "payments":[{"method":"CASH","amount":"100"}]}""".formatted(session, customer, item, phone, marker),
				Trading.key()).andExpect(status().isCreated()), "$.id");
		Integer saleLine = json(b.get("/api/v1/sales/" + sale), "$.lines[0].id");
		b.postWithKey("/api/v1/sales/" + sale + "/returns", """
				{"tillSessionId":%d,"reason":"%s","refundMethod":"CREDIT_NOTE","lines":[{"saleLineId":%d,"qty":"1"}]}"""
			.formatted(session, marker, saleLine), Trading.key()).andExpect(status().isCreated());
		b.postWithKey("/api/v1/customer-payments",
				"{\"customerId\":" + customer + ",\"amount\":\"50\",\"method\":\"CASH\"}", Trading.key())
			.andExpect(status().isCreated());
		b.post("/api/v1/held-sales", "{\"branchId\":" + main + ",\"label\":\"" + marker + "\",\"cart\":{\"x\":1}}")
			.andExpect(status().isCreated());
		b.post("/api/v1/quotations", """
				{"branchId":%d,"customerName":"%s","lines":[{"productId":%d,"qty":"1"}]}""".formatted(main, marker, item))
			.andExpect(status().isCreated());
		Integer cnic = json(b.upload("/api/v1/files", "CNIC_PHOTO", "c.png", png()), "$.id");
		Integer photo = json(b.upload("/api/v1/files", "DEVICE_PHOTO", "p.png", png()), "$.id");
		b.post("/api/v1/till-sessions/" + session + "/cash-movements",
				"{\"kind\":\"IN\",\"amount\":\"5000\",\"reason\":\"float\"}").andExpect(status().isCreated());
		b.post("/api/v1/buybacks", """
				{"tillSessionId":%d,"productId":%d,"unit":{"serial":"%sB"},"sellerName":"%s","sellerCnic":"3520212345671",
				 "cnicFileId":%d,"itemFileId":%d,"price":"500"}""".formatted(session, phone, marker, marker, cnic, photo))
			.andExpect(status().isCreated());
		b.post("/api/v1/warranty-claims", "{\"serial\":\"" + marker + "1\",\"issue\":\"" + marker + "\"}")
			.andExpect(status().isCreated());
		Integer category = json(b.get("/api/v1/expense-categories"), "$[0].id");
		b.post("/api/v1/expenses", "{\"categoryId\":" + category + ",\"amount\":\"10\",\"note\":\"" + marker + "\"}")
			.andExpect(status().isCreated());
		b.post("/api/v1/alerts/refresh").andExpect(status().isOk());
		b.post("/api/v1/reports/sales-daily/export", "{}").andExpect(status().isAccepted());
		phase2Documents(b, session, main, customer, item, sale);
	}

	/** One of each Phase 2 record in Shop B (Stage 4b modules). */
	private void phase2Documents(ApiClient b, long session, long main, long customer, long item, int sale)
			throws Exception {
		b.post("/api/v1/promotions", "{\"name\":\"" + marker + " offer\",\"kind\":\"PERCENT\",\"value\":\"5\"}")
			.andExpect(status().isCreated());
		b.post("/api/v1/customers/" + customer + "/loyalty/adjust", "{\"points\":50,\"note\":\"" + marker + "\"}")
			.andExpect(status().isOk());
		Integer job = json(b.post("/api/v1/repairs", """
				{"customerName":"%s","customerPhone":"03001112223","device":"%s device","problem":"%s"}"""
			.formatted(marker, marker, marker)).andExpect(status().isCreated()), "$.id");
		b.post("/api/v1/repairs/" + job + "/parts", "{\"productId\":" + item + ",\"qty\":\"1\"}")
			.andExpect(status().isOk());
		b.post("/api/v1/installment-plans", """
				{"saleId":%d,"months":2,"guarantorName":"%s","guarantorCnic":"3520212345671","guarantorPhone":"03001234567"}"""
			.formatted(sale, marker)).andExpect(status().isCreated());
		b.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"100"}],
				 "prescription":{"patientName":"%s","doctorName":"%s doctor"}}""".formatted(session, item, marker, marker),
				Trading.key()).andExpect(status().isCreated());
		Integer challan = json(b.post("/api/v1/challans", """
				{"branchId":%d,"customerName":"%s","address":"%s","lines":[{"productId":%d,"qty":"1"}]}"""
			.formatted(main, marker, marker, item)).andExpect(status().isCreated()), "$.id");
		b.post("/api/v1/deliveries", "{\"challanId\":" + challan + ",\"note\":\"" + marker + "\"}")
			.andExpect(status().isCreated());
	}

	// ------------------------------------------------------------------ layers

	@Test
	void appConnectsAsRestrictedRoleThatCannotBypassRls() {
		var role = app.queryForMap("select rolsuper, rolbypassrls from pg_roles where rolname = current_user");
		assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
		assertThat(app.queryForObject("select pg_has_role(current_user, 'pos_app', 'member')", Boolean.class)).isTrue();
	}

	@Test
	void hibernateFilterReturnsOnlyCurrentShop() {
		List<Product> visible = TenantContext.callAsShop(shopA.id(), () -> tx.execute(s -> products.findAll()));
		assertThat(visible).allSatisfy(p -> assertThat(p.getShopId()).isEqualTo(shopA.id()));
		Long bProduct = TestDatabase.superuser()
			.queryForObject("select id from product where shop_id = ? limit 1", Long.class, shopB.id());
		java.util.Optional<Product> found = TenantContext.callAsShop(shopA.id(),
				() -> tx.execute(s -> products.findById(bProduct)));
		assertThat(found).isEmpty();
	}

	@Test
	void rowLevelSecurityFiltersRawSql() {
		List<Long> shopIds = TenantContext.callAsShop(shopA.id(),
				() -> app.queryForList("select distinct shop_id from app_user", Long.class));
		assertThat(shopIds).containsOnly(shopA.id());
	}

	@Test
	void noTenantMeansNoRowsAndNoWrites() {
		assertThat(app.queryForObject("select count(*) from product", Long.class)).isZero();
		assertThatThrownBy(() -> app.update("insert into brand (shop_id, name) values (?, 'x')", shopA.id()))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security");
	}

	@Test
	void cannotWriteRowsForAnotherShop() {
		assertThatThrownBy(() -> TenantContext.runAsShop(shopA.id(),
				() -> app.update("insert into brand (shop_id, name) values (?, 'intruder')", shopB.id())))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("row-level security");
		int updated = TenantContext.callAsShop(shopA.id(),
				() -> app.update("update product set name = 'hacked' where shop_id = ?", shopB.id()));
		assertThat(updated).isZero();
	}

	@Test
	void compositeForeignKeysBlockCrossShopReferences() {
		Long bUnit = TestDatabase.superuser().queryForObject("select min(id) from unit where shop_id = ?", Long.class,
				shopB.id());
		assertThatThrownBy(() -> TestDatabase.superuser()
			.update("insert into product (shop_id, sku, name, unit_id, price) values (?, 'X1', 'x', ?, 1)", shopA.id(),
					bUnit))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("product_unit_fk");
	}

	@Test
	void allShopsModeSeesEveryShopButOnlyInPlatform() {
		List<Long> ids = TenantContext.callAcrossAllShops(
				() -> app.queryForList("select distinct shop_id from app_user", Long.class));
		assertThat(ids).contains(shopA.id(), shopB.id());
	}

	@Test
	void contextDoesNotLeakBetweenTasksOnTheSameThread() throws Exception {
		try (var executor = Executors.newSingleThreadExecutor()) {
			executor.submit(() -> TenantContext.callAsShop(shopA.id(), () -> products.count())).get();
			Future<Long> next = executor.submit(() -> {
				assertThat(TenantContext.shopId()).isEmpty();
				return app.queryForObject("select count(*) from product", Long.class);
			});
			assertThat(next.get()).isZero();
		}
	}

	@Test
	void auditAndLoginHistoryAreInsertOnly() {
		TenantContext.runAsShop(shopA.id(), () -> {
			assertThatThrownBy(() -> app.update("update audit_log set action = 'CHANGED'")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from login_event")).rootCause()
				.hasMessageContaining("permission denied");
			assertThatThrownBy(() -> app.update("delete from price_change")).rootCause()
				.hasMessageContaining("permission denied");
		});
	}

	@Test
	void everyShopTableHasRowLevelSecurity() {
		List<String> missing = TestDatabase.superuser().queryForList("""
				select c.relname from pg_class c
				join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
				join pg_attribute a on a.attrelid = c.oid and a.attname = 'shop_id' and not a.attisdropped
				where c.relkind = 'r'
				  and c.relname not in ('shop_domain', 'shop_module', 'subscription', 'support_session', 'platform_audit_log',
				                        'subscription_invoice', 'subscription_payment', 'subscription_reminder')
				  and not (c.relrowsecurity and exists (select 1 from pg_policy p where p.polrelid = c.oid))
				""", String.class);
		assertThat(missing).as("shop tables without RLS policy").isEmpty();
	}

	// ------------------------------------------------------------------ every endpoint

	@Test
	void everyEndpointWithAnIdRefusesAnotherShopsRecords() throws Exception {
		String before = snapshot(shopB.id());
		List<String> checked = new ArrayList<>();
		for (Endpoint endpoint : shopEndpoints()) {
			if (!endpoint.path().contains("{") || NOT_IDS.contains(endpoint.path())) {
				continue;
			}
			Matcher m = Pattern.compile("/([a-z-]+)/\\{[a-zA-Z]+}").matcher(endpoint.path());
			assertThat(m.find()).as("path variable in " + endpoint.path()).isTrue();
			String table = ID_TABLES.get(m.group(1));
			assertThat(table).as("Map the path variable of " + endpoint.path() + " to its table in ID_TABLES").isNotNull();
			Long bId = TestDatabase.superuser()
				.queryForObject("select max(id) from " + table + " where shop_id = ?", Long.class, shopB.id());
			assertThat(bId).as("Shop B has a " + table).isNotNull();
			String url = endpoint.path().replaceAll("\\{[a-zA-Z]+}", String.valueOf(bId));
			MvcResult result = call(shopA.owner(), endpoint.method(), url).andReturn();
			assertThat(result.getResponse().getStatus())
				.as(endpoint.method() + " " + url + " as Shop A must not find Shop B's record")
				.isEqualTo(404);
			checked.add(endpoint.method() + " " + endpoint.path());
		}
		assertThat(checked).hasSizeGreaterThan(80);
		assertThat(snapshot(shopB.id())).as("Shop B's data changed").isEqualTo(before);
	}

	@Test
	void everyListEndpointShowsOnlyTheCallersShop() throws Exception {
		List<String> checked = new ArrayList<>();
		for (Endpoint endpoint : shopEndpoints()) {
			if (endpoint.method() != RequestMethod.GET || endpoint.path().contains("{")) {
				continue;
			}
			String url = endpoint.path().equals("/api/v1/products/lookup") ? url(endpoint.path()) + "?code=" + marker
					: url(endpoint.path());
			MvcResult result = shopA.owner().get(url).andReturn();
			int status = result.getResponse().getStatus();
			assertThat(status).as("GET " + url).isIn(200, 404);
			String type = result.getResponse().getContentType();
			if (type != null && type.contains("json")) {
				assertThat(result.getResponse().getContentAsString()).as("GET " + url + " leaks Shop B").doesNotContain(marker);
			}
			checked.add(url);
		}
		assertThat(checked).contains("/api/v1/products", "/api/v1/users", "/api/v1/audit", "/api/v1/categories");
	}

	@Test
	void searchAndBarcodeLookupDoNotFindAnotherShopsProducts() throws Exception {
		shopA.owner().get("/api/v1/products/lookup?code=" + marker).andExpect(status().isNotFound());
		assertThat((Integer) json(shopA.owner().get("/api/v1/products?q=" + marker), "$.total")).isZero();
		assertThat((Integer) json(shopA.owner().get("/api/v1/products?q=" + marker + "%20formula"), "$.total")).isZero();
	}

	// ------------------------------------------------------------------ helpers

	record Endpoint(RequestMethod method, String path) {
	}

	List<Endpoint> shopEndpoints() {
		List<Endpoint> list = new ArrayList<>();
		for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
			for (String path : info.getPatternValues()) {
				if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/platform/") || path.startsWith("/api/v1/auth/")
						|| path.startsWith("/api/v1/public/") || path.startsWith("/api/v1/dev/")) {
					continue;
				}
				for (RequestMethod method : info.getMethodsCondition().getMethods()) {
					list.add(new Endpoint(method, path));
				}
			}
		}
		list.sort(java.util.Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
		return list;
	}

	static org.springframework.test.web.servlet.ResultActions call(ApiClient client, RequestMethod method, String url)
			throws Exception {
		return switch (method) {
			case GET -> client.get(url);
			case POST -> url.endsWith("/import") ? client.uploadWith(url, "x.csv", "name\nx".getBytes(), "dryRun", "true")
					: client.post(url, "{}");
			case PATCH -> client.patch(url, "{}");
			case PUT -> client.put(url, "{}");
			case DELETE -> client.delete(url);
			default -> throw new IllegalStateException(method.name());
		};
	}

	private static String url(String path) {
		return path;
	}

	private long roleId(TestShop shop, String key) {
		return TestDatabase.superuser().queryForObject("select id from role where shop_id = ? and system_key = ?",
				Long.class, shop.id(), key);
	}

	/** A fingerprint of every row of one shop in every shop table. */
	static String snapshot(long shopId) {
		JdbcTemplate db = TestDatabase.superuser();
		Map<String, String> parts = new LinkedHashMap<>();
		for (String table : db.queryForList("""
				select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
				join pg_attribute a on a.attrelid = c.oid and a.attname = 'shop_id'
				where c.relkind = 'r' order by c.relname""", String.class)) {
			parts.put(table, db.queryForObject("select md5(coalesce(string_agg(t::text, '|' order by t::text), '')) from "
					+ table + " t where shop_id = ?", String.class, shopId));
		}
		return parts.toString();
	}

	static byte[] png() {
		return java.util.Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
	}
}
