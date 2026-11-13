package com.selectcode.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.selectcode.pos.catalog.app.ScaleBarcode;
import com.selectcode.pos.sales.app.PromotionEngine;
import com.selectcode.pos.sales.app.PromotionEngine.LineIn;
import com.selectcode.pos.sales.app.PromotionEngine.Offer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offers, coupons and scale labels against the shared vectors in {@code docs/test-vectors/promotions.json} (the till
 * runs the same file), so the till's total and the server's always match.
 */
class PromotionEngineTest {

	static JsonNode vectors;

	@BeforeAll
	static void load() throws Exception {
		vectors = JsonMapper.builder().build()
			.readTree(Files.readString(Path.of("..", "docs", "test-vectors", "promotions.json")));
	}

	static BigDecimal d(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isMissingNode() || v.isNull() ? null : new BigDecimal(v.asString());
	}

	static Offer offer(JsonNode o) {
		Set<Long> categories = new LinkedHashSet<>();
		o.path("categoryIds").forEach(x -> categories.add(x.asLong()));
		Set<Long> products = new LinkedHashSet<>();
		o.path("productIds").forEach(x -> products.add(x.asLong()));
		Set<Integer> days = new LinkedHashSet<>();
		o.path("days").forEach(x -> days.add(x.asInt()));
		String from = o.path("fromTime").isMissingNode() ? null : o.path("fromTime").asString();
		String to = o.path("toTime").isMissingNode() ? null : o.path("toTime").asString();
		return new Offer(o.path("id").asLong(1), "Offer", o.path("kind").asString("PERCENT"),
				d(o, "value") == null ? BigDecimal.ZERO : d(o, "value"), d(o, "buyQty"), d(o, "getQty"),
				d(o, "minAmount") == null ? BigDecimal.ZERO : d(o, "minAmount"), o.path("scope").asString("ALL"), categories,
				products, o.path("startsOn").isMissingNode() ? null : LocalDate.parse(o.path("startsOn").asString()),
				o.path("endsOn").isMissingNode() ? null : LocalDate.parse(o.path("endsOn").asString()), days,
				from == null ? null : LocalTime.parse(from), to == null ? null : LocalTime.parse(to),
				o.path("couponCode").isMissingNode() ? null : o.path("couponCode").asString(),
				o.path("maxUses").isMissingNode() ? null : o.path("maxUses").asInt(), o.path("uses").asInt(0),
				o.path("active").asBoolean(true));
	}

	@Test
	void lineAmount() {
		for (JsonNode v : vectors.path("lineAmount")) {
			LineIn line = new LineIn(1, null, d(v, "qty"), d(v, "price"), true);
			assertThat(PromotionEngine.lineAmount(offer(v.path("offer")), line).toPlainString()).as(v.path("name").asString())
				.isEqualTo(v.path("expected").asString());
		}
	}

	@Test
	void billAmount() {
		for (JsonNode v : vectors.path("billAmount")) {
			assertThat(PromotionEngine.billAmount(offer(v.path("offer")), d(v, "bill")).toPlainString())
				.as(v.path("name").asString())
				.isEqualTo(v.path("expected").asString());
		}
	}

	@Test
	void running() {
		for (JsonNode v : vectors.path("running")) {
			assertThat(PromotionEngine.running(offer(v.path("offer")), LocalDate.parse(v.path("date").asString()),
					LocalTime.parse(v.path("time").asString())))
				.as(v.path("name").asString())
				.isEqualTo(v.path("expected").asBoolean());
		}
	}

	@Test
	void apply() {
		for (JsonNode v : vectors.path("apply")) {
			List<Offer> offers = new ArrayList<>();
			v.path("offers").forEach(o -> offers.add(offer(o)));
			List<LineIn> lines = new ArrayList<>();
			List<BigDecimal> typed = new ArrayList<>();
			for (JsonNode l : v.path("lines")) {
				lines.add(new LineIn(l.path("productId").asLong(),
						l.path("categoryId").isNull() ? null : l.path("categoryId").asLong(), d(l, "qty"), d(l, "price"),
						l.path("eligible").asBoolean()));
				typed.add(d(l, "typedDiscount"));
			}
			var result = PromotionEngine.apply(offers, lines, typed, v.path("billTyped").asBoolean(),
					v.path("coupon").isNull() ? null : v.path("coupon").asString());
			String name = v.path("name").asString();
			for (int i = 0; i < lines.size(); i++) {
				JsonNode e = v.path("expectedLines").get(i);
				assertThat(result.lines().get(i).offerId()).as(name + " line " + i)
					.isEqualTo(e.path("offerId").isNull() ? null : e.path("offerId").asLong());
				assertThat(result.lines().get(i).amount().toPlainString()).as(name + " line " + i)
					.isEqualTo(e.path("amount").asString());
			}
			JsonNode bill = v.path("expectedBill");
			assertThat(result.bill().offerId()).as(name + " bill")
				.isEqualTo(bill.path("offerId").isNull() ? null : bill.path("offerId").asLong());
			assertThat(result.bill().amount().toPlainString()).as(name + " bill").isEqualTo(bill.path("amount").asString());
			assertThat(result.couponUsed()).as(name + " coupon").isEqualTo(v.path("expectedCouponUsed").asBoolean());
		}
	}

	@Test
	void scaleBarcodes() {
		for (JsonNode v : vectors.path("scaleBarcode")) {
			var reading = ScaleBarcode.read(v.path("barcode").asString(), v.path("mode").asString());
			String name = v.path("name").asString();
			if (v.path("expectedCode").isNull()) {
				assertThat(reading).as(name).isEmpty();
			}
			else {
				assertThat(reading).as(name).isPresent();
				assertThat(reading.get().itemCode()).as(name).isEqualTo(v.path("expectedCode").asString());
				assertThat(reading.get().value().toPlainString()).as(name).isEqualTo(v.path("expectedValue").asString());
			}
		}
	}
}
