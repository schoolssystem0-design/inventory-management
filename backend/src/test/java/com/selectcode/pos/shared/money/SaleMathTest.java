package com.selectcode.pos.shared.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The money rules against the shared test vectors in {@code docs/test-vectors/money.json} (also used by the frontend
 * tests), so the till and the server always agree to the paisa.
 */
class SaleMathTest {

	static JsonNode vectors;

	@BeforeAll
	static void load() throws Exception {
		Path path = Path.of("..", "docs", "test-vectors", "money.json");
		vectors = JsonMapper.builder().build().readTree(Files.readString(path));
	}

	static BigDecimal d(JsonNode node, String field) {
		return new BigDecimal(node.path(field).asString());
	}

	@Test
	void averageCost() {
		for (JsonNode v : vectors.path("averageCost")) {
			assertThat(SaleMath.averageCost(d(v, "onHand"), d(v, "currentAvg"), d(v, "qtyIn"), d(v, "unitCost")))
				.as(v.path("name").asString())
				.isEqualByComparingTo(d(v, "expectedAvg"));
		}
	}

	@Test
	void billDiscountSpread() {
		for (JsonNode v : vectors.path("billDiscountSpread")) {
			List<BigDecimal> nets = new ArrayList<>();
			v.path("lines").forEach(l -> nets.add(d(l, "net")));
			List<BigDecimal> shares = SaleMath.spread(nets, d(v, "billDiscount"));
			List<String> expected = new ArrayList<>();
			v.path("expectedLineDiscounts").forEach(e -> expected.add(e.asString()));
			assertThat(shares.stream().map(BigDecimal::toPlainString).toList()).as(v.path("name").asString())
				.isEqualTo(expected);
			assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(d(v, "billDiscount"));
		}
	}

	@Test
	void returnRefund() {
		for (JsonNode v : vectors.path("returnRefund")) {
			BigDecimal total = d(v, "saleTotal");
			BigDecimal returned = d(v, "alreadyReturned");
			BigDecimal paid = d(v, "paid");
			assertThat(SaleMath.dueBefore(total, returned, paid)).as(v.path("name").asString())
				.isEqualByComparingTo(d(v, "expectedDueBefore"));
			assertThat(SaleMath.maxRefund(total, returned, paid, d(v, "returnValue"))).as(v.path("name").asString())
				.isEqualByComparingTo(d(v, "expectedMaxRefund"));
		}
	}

	@Test
	void reorderSuggestion() {
		for (JsonNode v : vectors.path("reorderSuggestion")) {
			assertThat(SaleMath.reorderSuggestion(d(v, "reorderLevel"), d(v, "sold30"), d(v, "onHand"), d(v, "onOrder")))
				.as(v.path("name").asString())
				.isEqualByComparingTo(d(v, "expectedSuggest"));
		}
	}

	@Test
	void weighedItem() {
		for (JsonNode v : vectors.path("weighedItem")) {
			assertThat(SaleMath.lineAmount(d(v, "qty"), d(v, "price"))).as(v.path("name").asString())
				.isEqualByComparingTo(d(v, "expectedLine"));
		}
	}

	@Test
	void taxInclusiveAndExclusive() {
		// Rs 1,170 including 17% → tax 170.00; Rs 1,000 plus 17% → tax 170.00, total 1,170.00.
		assertThat(SaleMath.tax("INCLUSIVE", new BigDecimal("1170.00"), new BigDecimal("17"))).isEqualByComparingTo("170.00");
		BigDecimal tax = SaleMath.tax("EXCLUSIVE", new BigDecimal("1000.00"), new BigDecimal("17"));
		assertThat(tax).isEqualByComparingTo("170.00");
		assertThat(SaleMath.lineTotal("EXCLUSIVE", new BigDecimal("1000.00"), tax)).isEqualByComparingTo("1170.00");
		assertThat(SaleMath.tax("NONE", new BigDecimal("1000.00"), new BigDecimal("17"))).isEqualByComparingTo("0.00");
	}

	@Test
	void returnValueOfLastUnitsTakesTheRemainder() {
		// A line of 3 for 100.00 returned one at a time gives 33.33 + 33.33 + 33.34 = 100.00.
		BigDecimal total = new BigDecimal("100.00");
		BigDecimal qty = new BigDecimal("3");
		BigDecimal a = SaleMath.returnValue(total, qty, BigDecimal.ZERO, BigDecimal.ONE);
		BigDecimal b = SaleMath.returnValue(total, qty, BigDecimal.ONE, BigDecimal.ONE);
		BigDecimal c = SaleMath.returnValue(total, qty, BigDecimal.TWO, BigDecimal.ONE);
		assertThat(a).isEqualByComparingTo("33.33");
		assertThat(b).isEqualByComparingTo("33.34");
		assertThat(a.add(b).add(c)).isEqualByComparingTo("100.00");
	}

	@Test
	void spreadSkipsZeroLinesForRemainder() {
		List<BigDecimal> shares = SaleMath.spread(List.of(new BigDecimal("50.00"), BigDecimal.ZERO.setScale(2)),
				new BigDecimal("10.00"));
		assertThat(shares).containsExactly(new BigDecimal("10.00"), new BigDecimal("0.00"));
	}
}
