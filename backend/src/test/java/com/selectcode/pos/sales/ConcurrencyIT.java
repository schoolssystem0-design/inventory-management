package com.selectcode.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import com.selectcode.pos.support.Trading;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Two tills at the same moment (NFR-04): the last unit (TC-STK-02) and the same IMEI (TC-STK-03) are sold once; the
 * other till gets a clean error and stock stays exact.
 */
class ConcurrencyIT extends IntegrationTest {

	record Two(TestShop shop, ApiClient tillA, long sessionA, ApiClient tillB, long sessionB, long branch,
			long supplier, Trading t) {
	}

	Two twoTills(String type) throws Exception {
		TestShop shop = newShop(type, "enterprise");
		Trading t = new Trading(shop.owner());
		long branch = t.defaultBranch();
		long sessionA = t.openTill(branch, "0");
		long till2 = TestDatabase.superuser().queryForObject(
				"insert into till (shop_id, branch_id, name) values (?, ?, 'Till 2') returning id", Long.class, shop.id(),
				branch);
		ApiClient cashier = userWithRole(shop, "CASHIER", unique("c"));
		Integer sessionB = Trading.read(cashier.post("/api/v1/till-sessions", "{\"tillId\":" + till2 + "}")
			.andExpect(status().isCreated()), "$.id");
		return new Two(shop, shop.owner(), sessionA, cashier, sessionB, branch, t.supplier("S"), t);
	}

	/** Runs the two calls at the same moment and returns their responses. */
	static List<MvcResult> race(Callable<MvcResult> a, Callable<MvcResult> b) throws Exception {
		CountDownLatch go = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (Callable<MvcResult> call : List.of(a, b)) {
				futures.add(pool.submit(() -> {
					go.await();
					return call.call();
				}));
			}
			go.countDown();
			List<MvcResult> results = new ArrayList<>();
			for (Future<MvcResult> f : futures) {
				results.add(f.get());
			}
			return results;
		}
	}

	@Test
	void lastUnitIsSoldOnce() throws Exception {
		Two s = twoTills("general");
		for (int round = 0; round < 5; round++) {
			long product = s.t().product("Last one " + round, "500", "0");
			s.t().receive(s.supplier(), s.branch(), product, "1", "300");
			String bodyA = """
					{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"500"}]}"""
				.formatted(s.sessionA(), product);
			String bodyB = """
					{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"500"}]}"""
				.formatted(s.sessionB(), product);
			List<MvcResult> results = race(
					() -> s.tillA().postWithKey("/api/v1/sales", bodyA, Trading.key()).andReturn(),
					() -> s.tillB().postWithKey("/api/v1/sales", bodyB, Trading.key()).andReturn());
			List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
			assertThat(statuses).as("round " + round).containsExactly(201, 409);
			MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
			assertThat(loser.getResponse().getContentAsString()).contains("insufficient_stock");
			assertThat(s.t().onHand(product, s.branch())).isEqualTo("0.000");
		}
		s.shop().owner().get("/api/v1/stock/audit").andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(0));
	}

	@Test
	void sameImeiIsSoldOnce() throws Exception {
		Two s = twoTills("mobile");
		long phone = s.t().product("Phone X", "60000", "0", "SERIAL", null);
		for (int round = 0; round < 5; round++) {
			String imei = "35111122223333" + round;
			s.shop().owner().postWithKey("/api/v1/grns", """
					{"supplierId":%d,"branchId":%d,"lines":[{"productId":%d,"unitCost":"50000","serials":[{"serial":"%s"}]}]}"""
				.formatted(s.supplier(), s.branch(), phone, imei), Trading.key()).andExpect(status().isCreated());
			String bodyA = """
					{"tillSessionId":%d,"lines":[{"productId":%d,"serials":["%s"]}],"payments":[{"method":"CASH","amount":"60000"}]}"""
				.formatted(s.sessionA(), phone, imei);
			String bodyB = """
					{"tillSessionId":%d,"lines":[{"productId":%d,"serials":["%s"]}],"payments":[{"method":"CASH","amount":"60000"}]}"""
				.formatted(s.sessionB(), phone, imei);
			List<MvcResult> results = race(
					() -> s.tillA().postWithKey("/api/v1/sales", bodyA, Trading.key()).andReturn(),
					() -> s.tillB().postWithKey("/api/v1/sales", bodyB, Trading.key()).andReturn());
			List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).sorted().toList();
			assertThat(statuses).as("round " + round).containsExactly(201, 409);
			MvcResult loser = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
			assertThat(loser.getResponse().getContentAsString()).containsAnyOf("serial_unavailable", "insufficient_stock");
		}
		assertThat(TestDatabase.superuser().queryForObject(
				"select count(*) from serial_unit where shop_id = ? and status = 'SOLD'", Integer.class, s.shop().id()))
			.isEqualTo(5);
		assertThat(s.t().onHand(phone, s.branch())).isEqualTo("0.000");
	}

	@Test
	void sameIdempotencyKeyAtTheSameMomentMakesOneSale() throws Exception {
		Two s = twoTills("general");
		long product = s.t().product("Retry item", "100", "0");
		s.t().receive(s.supplier(), s.branch(), product, "10", "60");
		String body = """
				{"tillSessionId":%d,"lines":[{"productId":%d,"qty":"1"}],"payments":[{"method":"CASH","amount":"100"}]}"""
			.formatted(s.sessionA(), product);
		String key = Trading.key();
		ApiClient twin = client();
		twin.useSessionCookie(s.tillA().sessionCookie());
		List<MvcResult> results = race(() -> s.tillA().postWithKey("/api/v1/sales", body, key).andReturn(),
				() -> twin.postWithKey("/api/v1/sales", body, key).andReturn());
		assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isIn(201, 409));
		assertThat(s.t().onHand(product, s.branch())).isEqualTo("9.000");
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from sale where shop_id = ?", Integer.class,
				s.shop().id())).isEqualTo(1);
	}
}
