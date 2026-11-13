package com.selectcode.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The application starts, a shop can be created and its owner can sign in and load the shop context. */
class SmokeIT extends IntegrationTest {

	@Test
	void ownerSignsInAndLoadsContext() throws Exception {
		TestShop shop = newShop("medical", "business");
		var context = shop.owner().get("/api/v1/shop/context").andExpect(status().isOk());
		assertThat((String) json(context, "$.shop.code")).isEqualTo(shop.code());
		assertThat((String) json(context, "$.shop.typeKey")).isEqualTo("medical");
		assertThat((Boolean) json(context, "$.user.owner")).isTrue();
		java.util.List<String> modules = json(context, "$.modules[*].key");
		assertThat(modules).contains("batches", "formula", "pack_units", "multi_till");
		java.util.List<String> branches = json(context, "$.branches[*].name");
		assertThat(branches).containsExactly("Main shop", "Godown");
	}
}
