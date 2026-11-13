package com.selectcode.pos.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.platform.app.DemoShops;
import com.selectcode.pos.shared.files.FileStorage;
import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * QA and security pass (2026-11-13): every role against every endpoint, branch limits on the Stage 4b modules, a full
 * purge of a shop's rows and files, security headers, report limits, and the QA-plan cases that had no test yet
 * (TC-ACC-01, TC-ACC-02, TC-GAR-01, TC-MED-04, TC-MED-05, TC-SUP-03, TC-CUS-03). Runs only against the embedded test
 * database: shops, users and data made here are thrown away.
 */
class QaSecurityIT extends IntegrationTest {

	static final Map<String, Boolean> ALL_MODULES = Map.ofEntries(Map.entry("serials", true),
			Map.entry("used_buyback", true), Map.entry("warranty", true), Map.entry("variants", true),
			Map.entry("batches", true), Map.entry("pack_units", true), Map.entry("formula", true),
			Map.entry("weighed", true), Map.entry("multi_till", true), Map.entry("quotations", true),
			Map.entry("bundles", true), Map.entry("part_numbers", true), Map.entry("sms", true),
			Map.entry("repairs", true), Map.entry("installments", true), Map.entry("prescriptions", true),
			Map.entry("scale_barcodes", true), Map.entry("promotions", true), Map.entry("loyalty", true),
			Map.entry("delivery", true));

	static final List<String> ROLES = List.of("PARTNER", "MANAGER", "CASHIER", "SALESMAN", "STOCK_KEEPER", "PURCHASER",
			"ACCOUNTANT", "PHARMACIST", "TECHNICIAN");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Autowired
	DemoShops demoShops;

	@Autowired
	FileStorage storage;

	/** An endpoint and what @PreAuthorize asks for: any one of {@code anyOf}, or all of {@code allOf}. */
	record Rule(RequestMethod method, String path, Set<String> anyOf, Set<String> allOf) {
	}

	List<Rule> rules() {
		List<Rule> list = new ArrayList<>();
		Pattern quoted = Pattern.compile("'([a-z_.]+)'");
		for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
			PreAuthorize pre = e.getValue().getMethodAnnotation(PreAuthorize.class);
			if (pre == null || !pre.value().contains("Authority")) {
				continue;
			}
			Set<String> perms = new TreeSet<>();
			Matcher m = quoted.matcher(pre.value());
			while (m.find()) {
				perms.add(m.group(1));
			}
			boolean all = pre.value().contains(" and ");
			for (String path : e.getKey().getPatternValues()) {
				if (!path.startsWith("/api/v1/") || path.startsWith("/api/v1/platform/")) {
					continue;
				}
				for (RequestMethod method : e.getKey().getMethodsCondition().getMethods()) {
					list.add(new Rule(method, path, all ? Set.of() : perms, all ? perms : Set.of()));
				}
			}
		}
		list.sort(java.util.Comparator.comparing(Rule::path).thenComparing(Rule::method));
		return list;
	}

	static boolean allowed(Rule r, Set<String> held) {
		return r.allOf().isEmpty() ? r.anyOf().stream().anyMatch(held::contains) : held.containsAll(r.allOf());
	}

	static MvcResult call(ApiClient client, RequestMethod method, String url) throws Exception {
		return switch (method) {
			case GET -> client.get(url).andReturn();
			case POST -> url.endsWith("/import")
					? client.uploadWith(url, "x.csv", "name,price".getBytes(), "dryRun", "true").andReturn()
					: client.post(url, "{}").andReturn();
			case PATCH -> client.patch(url, "{}").andReturn();
			case PUT -> client.put(url, "{}").andReturn();
			case DELETE -> client.delete(url).andReturn();
			default -> throw new IllegalStateException(method.name());
		};
	}

	/**
	 * TC-ACC-01: each system role is refused (403 forbidden, before anything runs) on every endpoint whose permission
	 * it does not hold, and is let in on every list it does hold.
	 */
	@Test
	void everyRoleIsRefusedEverywhereItHasNoPermission() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		shopAdmin.setModules(shop.id(), ALL_MODULES);
		List<Rule> rules = rules();
		assertThat(rules).hasSizeGreaterThan(200);
		Map<String, List<String>> wrong = new LinkedHashMap<>();
		int refused = 0;
		int allowedLists = 0;
		for (String role : ROLES) {
			if (TestDatabase.superuser().queryForObject("select count(*) from role where shop_id = ? and system_key = ?",
					Integer.class, shop.id(), role) == 0) {
				// Roles that depend on a module are added when the module is switched on.
				shopAdmin.setModules(shop.id(), ALL_MODULES);
			}
			ApiClient user = userWithRole(shop, role, unique(role.toLowerCase().replace("_", "")));
			Set<String> held = new TreeSet<>(TestDatabase.superuser().queryForList(
					"select unnest(permissions) from role where shop_id = ? and system_key = ?", String.class, shop.id(),
					role));
			for (Rule r : rules) {
				String url = r.path().replaceAll("\\{[a-zA-Z]+}", "1");
				boolean ok = allowed(r, held);
				if (!ok) {
					MvcResult res = call(user, r.method(), url);
					refused++;
					if (res.getResponse().getStatus() != 403) {
						wrong.computeIfAbsent(role, k -> new ArrayList<>())
							.add(r.method() + " " + r.path() + " → " + res.getResponse().getStatus() + " (needs " + r.anyOf()
									+ r.allOf() + ")");
					}
				}
				else if (r.method() == RequestMethod.GET && !r.path().contains("{")) {
					MvcResult res = call(user, r.method(), url);
					allowedLists++;
					String body = res.getResponse().getContentAsString();
					if (res.getResponse().getStatus() == 403 && body.contains("\"forbidden\"")) {
						wrong.computeIfAbsent(role, k -> new ArrayList<>())
							.add("GET " + r.path() + " refused although " + role + " holds " + r.anyOf() + r.allOf());
					}
				}
			}
		}
		assertThat(wrong).as("role / endpoint mismatches").isEmpty();
		assertThat(refused).isGreaterThan(500);
		assertThat(allowedLists).isGreaterThan(50);
	}

	/** TC-ACC-02 for the Stage 4b modules: a user limited to one branch never sees another branch's documents. */
	@Test
	void branchLimitedUserSeesOnlyTheirBranchInTheNewModules() throws Exception {
		TestShop shop = newShop("electronics", "enterprise");
		shopAdmin.setModules(shop.id(), ALL_MODULES);
		Trading t = new Trading(shop.owner());
		long main = t.branch(0);
		long godown = t.branch(1);
		long session = t.openTill(main, "5000");
		long supplier = t.supplier("S");
		long tv = t.product("TV", "1000", "0");
		t.receive(supplier, main, tv, "10", "600");
		long customer = t.customer("Branch One Customer", "0300-1231231", "100000");
		ApiClient o = shop.owner();
		Integer sale = Trading.read(o.postWithKey("/api/v1/sales", """
				{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"ACCOUNT"}]}"""
			.formatted(session, customer, tv), Trading.key()).andExpect(status().isCreated()), "$.id");
		Integer plan = Trading.read(o.post("/api/v1/installment-plans", """
				{"saleId":%d,"months":2,"guarantorName":"G","guarantorCnic":"3520212345671","guarantorPhone":"03001234567"}"""
			.formatted(sale)).andExpect(status().isCreated()), "$.id");
		Integer repair = Trading.read(o.post("/api/v1/repairs", """
				{"customerName":"X","customerPhone":"03001112223","device":"TV","problem":"No picture"}""")
			.andExpect(status().isCreated()), "$.id");
		Integer challan = Trading.read(o.post("/api/v1/challans", """
				{"branchId":%d,"customerName":"Y","lines":[{"productId":%d,"qty":"1"}]}""".formatted(main, tv))
			.andExpect(status().isCreated()), "$.id");
		Integer delivery = Trading.read(o.post("/api/v1/deliveries", "{\"saleId\":" + sale + ",\"address\":\"Somewhere\"}")
			.andExpect(status().isCreated()), "$.id");
		// A manager limited to the godown.
		String username = unique("godown");
		long userId = createUser(shop, "MANAGER", username);
		TestDatabase.superuser().update("update app_user set all_branches = false where id = ?", userId);
		TestDatabase.superuser().update("insert into user_branch (shop_id, user_id, branch_id) values (?, ?, ?)", shop.id(),
				userId, godown);
		ApiClient g = client();
		g.login(shop.code(), username, PASSWORD).andExpect(status().isOk());
		g.get("/api/v1/sales/" + sale).andExpect(status().isNotFound());
		g.get("/api/v1/installment-plans/" + plan).andExpect(status().isNotFound());
		g.get("/api/v1/installment-plans").andExpect(jsonPath("$.total").value(0));
		g.get("/api/v1/installments/due?to=2099-01-01").andExpect(jsonPath("$.length()").value(0));
		g.get("/api/v1/repairs/" + repair).andExpect(status().isNotFound());
		g.get("/api/v1/repairs?status=").andExpect(jsonPath("$.total").value(0));
		g.get("/api/v1/challans/" + challan).andExpect(status().isNotFound());
		g.get("/api/v1/challans").andExpect(jsonPath("$.total").value(0));
		g.get("/api/v1/deliveries/" + delivery).andExpect(status().isNotFound());
		g.get("/api/v1/deliveries").andExpect(jsonPath("$.total").value(0));
		// Nor can they act on them.
		g.postWithKey("/api/v1/installment-plans/" + plan + "/receive", "{\"amount\":\"10\",\"method\":\"BANK\"}",
				Trading.key()).andExpect(status().isNotFound());
		g.post("/api/v1/challans/" + challan + "/cancel", "{\"reason\":\"x\"}").andExpect(status().isNotFound());
		// The owner (all branches) still sees everything.
		o.get("/api/v1/installment-plans").andExpect(jsonPath("$.total").value(1));
	}

	/**
	 * Data removal in the sandbox: a demo shop full of data from every module is purged — every shop table is empty
	 * for it and its uploaded files (CNIC and phone photos) are gone from storage.
	 */
	@Test
	void purgingAShopRemovesEveryRowAndEveryFile() throws Exception {
		Map<String, Object> first = demoShops.reset("mobile", DemoShops.Size.SMALL);
		long oldId = ((Number) first.get("shopId")).longValue();
		var db = TestDatabase.superuser();
		assertThat(db.queryForObject("select count(*) from stored_file where shop_id = ?", Integer.class, oldId))
			.as("demo has photos").isPositive();
		assertThat(db.queryForObject("select count(*) from repair_job where shop_id = ?", Integer.class, oldId))
			.as("demo has repair jobs").isPositive();
		List<String> keys = db.queryForList("select storage_key from stored_file where shop_id = ?", String.class, oldId);
		assertThat(storage.get(keys.getFirst())).isNotEmpty();
		// Resetting purges the old shop.
		demoShops.reset("mobile", DemoShops.Size.SMALL);
		List<String> left = new ArrayList<>();
		for (String table : db.queryForList("""
				select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
				join pg_attribute a on a.attrelid = c.oid and a.attname = 'shop_id' and not a.attisdropped
				where c.relkind = 'r' and c.relname not in ('shop', 'subscription', 'subscription_invoice',
				      'subscription_payment', 'subscription_reminder', 'platform_audit_log')""", String.class)) {
			Integer n = db.queryForObject("select count(*) from " + table + " where shop_id = ?", Integer.class, oldId);
			if (n != null && n > 0) {
				left.add(table + "=" + n);
			}
		}
		assertThat(left).as("rows left after purge").isEmpty();
		for (String key : keys) {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.get(key)).as("file " + key).isNotNull();
		}
		assertThat(storage.deletePrefix(FileStorage.shopPrefix(oldId))).isZero();
	}

	/** Storage refuses to delete anything outside a shop's own folder. */
	@Test
	void storageDeletesOnlyInsideAShopFolder() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.deletePrefix("../"))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.deletePrefix(""))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.deletePrefix("shops/../../etc"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** SECURITY.md §2 "Transport and browser": API answers carry the security headers and no stack traces. */
	@Test
	void securityHeadersAndNoInternalsInErrors() throws Exception {
		TestShop shop = newShop("general", "starter");
		shop.owner().get("/api/v1/shop/context").andExpect(status().isOk())
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
			.andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
		// Bad input: a clear message, never SQL, class names or a stack trace.
		String body = shop.owner().get("/api/v1/sales?from=not-a-date").andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("SELECT").doesNotContain("org.");
		String body2 = shop.owner().post("/api/v1/products", "{\"name\":").andReturn().getResponse().getContentAsString();
		assertThat(body2).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("jackson");
	}

	/** A report cannot be asked for millions of rows. */
	@Test
	void reportLimitsAreCapped() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		var report = shop.owner().get("/api/v1/reports").andReturn().getResponse().getContentAsString();
		assertThat(report).contains("key");
		List<String> keys = Trading.read(shop.owner().get("/api/v1/reports"), "$[*].key");
		for (String key : keys) {
			shop.owner().get("/api/v1/reports/" + key + "?limit=999999999&days=99999999")
				.andExpect(status().is2xxSuccessful());
		}
	}

	/** TC-GAR-01: a design with 4 sizes × 3 colours makes 12 variants, each with its own barcode. */
	@Test
	void variantGridMakesTwelveVariantsWithTheirOwnBarcodes() throws Exception {
		TestShop shop = newShop("garments", "enterprise");
		Trading t = new Trading(shop.owner());
		long design = t.product("Kurta", "2500", "0");
		shop.owner().post("/api/v1/products/" + design + "/variants",
				"{\"sizes\":[\"S\",\"M\",\"L\",\"XL\"],\"colours\":[\"White\",\"Black\",\"Blue\"]}")
			.andExpect(status().is2xxSuccessful());
		List<Integer> ids = Trading.read(shop.owner().get("/api/v1/products/" + design + "/variants"), "$.variants[*].productId");
		assertThat(ids).hasSize(12);
		Integer barcodes = TestDatabase.superuser().queryForObject(
				"select count(distinct barcode) from product_barcode where product_id = any(?)", Integer.class,
				(Object) ids.stream().map(Integer::longValue).toArray(Long[]::new));
		assertThat(barcodes).isEqualTo(12);
	}

	/** TC-MED-04 and TC-MED-05: formula search finds alternatives; near-expiry lists the 45-day batch. */
	@Test
	void formulaSearchAndNearExpiry() throws Exception {
		TestShop shop = newShop("medical", "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long supplier = t.supplier("Pharma");
		long a = t.product("Panadol 500", "30", "0", "BATCH", "\"pharma\":{\"formula\":\"Paracetamol\"}");
		long b = t.product("Calpol 500", "28", "0", "BATCH", "\"pharma\":{\"formula\":\"Paracetamol\"}");
		t.product("Brufen 400", "40", "0", "BATCH", "\"pharma\":{\"formula\":\"Ibuprofen\"}");
		String soon = java.time.LocalDate.now().plusDays(45).toString();
		shop.owner().postWithKey("/api/v1/grns", """
				{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"qty":"10","unitCost":"20","batchNo":"S1","expiry":"%s"},
				 {"productId":%d,"qty":"10","unitCost":"20","batchNo":"L1","expiry":"2030-01-01"}]}"""
			.formatted(supplier, branch, a, soon, b), Trading.key()).andExpect(status().isCreated());
		List<Integer> found = Trading.read(shop.owner().get("/api/v1/products?q=paracetamol"), "$.items[*].id");
		assertThat(found).containsExactlyInAnyOrder((int) a, (int) b);
		shop.owner().post("/api/v1/alerts/refresh").andExpect(status().isOk());
		var alerts = shop.owner().get("/api/v1/alerts?kind=NEAR_EXPIRY").andReturn().getResponse().getContentAsString();
		assertThat(alerts).contains("Panadol 500").doesNotContain("Calpol 500");
	}

	/** TC-SUP-03: three tills each keep their own cash at shift close. */
	@Test
	void eachTillKeepsItsOwnCash() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long item = t.product("Milk", "200", "0", "SERVICE", null);
		List<Long> sessions = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long till = TestDatabase.superuser().queryForObject(
					"insert into till (shop_id, branch_id, name) values (?, ?, ?) returning id", Long.class, shop.id(), branch,
					"Extra " + i);
			ApiClient cashier = userWithRole(shop, "CASHIER", unique("c" + i));
			Integer s = Trading.read(cashier.post("/api/v1/till-sessions", "{\"tillId\":" + till + ",\"openingFloat\":\"1000\"}")
				.andExpect(status().isCreated()), "$.id");
			sessions.add(s.longValue());
			for (int k = 0; k <= i; k++) {
				cashier.postWithKey("/api/v1/sales", """
						{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"200"}]}"""
					.formatted(s, item), Trading.key()).andExpect(status().isCreated());
			}
		}
		for (int i = 0; i < 3; i++) {
			String expected = Trading.read(shop.owner().get("/api/v1/till-sessions/" + sessions.get(i) + "/z-report"),
					"$.expectedCash");
			assertThat(new java.math.BigDecimal(expected)).isEqualByComparingTo(new java.math.BigDecimal(1000 + 200 * (i + 1)));
		}
	}

	/** TC-CUS-03: the statement's opening, lines and closing match the ledger. */
	@Test
	void statementMatchesTheLedger() throws Exception {
		TestShop shop = newShop("hardware", "enterprise");
		Trading t = new Trading(shop.owner());
		long session = t.openTill(t.defaultBranch(), "5000");
		long item = t.product("Pipe", "500", "0", "SERVICE", null);
		long customer = t.customer("Contractor", "0300-7778889", "100000");
		for (int i = 0; i < 3; i++) {
			shop.owner().postWithKey("/api/v1/sales", """
					{"tillSessionId":%d,"customerId":%d,"lines":[{"productId":%d,"qty":"%d"}],"payments":[{"method":"ACCOUNT"}]}"""
				.formatted(session, customer, item, i + 1), Trading.key()).andExpect(status().isCreated());
		}
		shop.owner().postWithKey("/api/v1/customer-payments", """
				{"customerId":%d,"amount":"700","method":"CASH","tillSessionId":%d}""".formatted(customer, session),
				Trading.key()).andExpect(status().isCreated());
		String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Karachi")).toString();
		var st = shop.owner().get("/api/v1/customers/" + customer + "/statement?from=" + today + "&to=" + today)
			.andExpect(status().isOk());
		assertThat((String) Trading.read(st, "$.opening")).isEqualTo("0.00");
		assertThat((String) Trading.read(st, "$.totalDebit")).isEqualTo("3000.00");
		assertThat((String) Trading.read(st, "$.totalCredit")).isEqualTo("700.00");
		assertThat((String) Trading.read(st, "$.closing")).isEqualTo("2300.00");
		shop.owner().get("/api/v1/customers/" + customer).andExpect(jsonPath("$.balance").value("2300.00"));
		var pdf = shop.owner().get("/api/v1/customers/" + customer + "/statement.pdf?from=" + today + "&to=" + today)
			.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(pdf.getContentType()).contains("pdf");
	}
}
