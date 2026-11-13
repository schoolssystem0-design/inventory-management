package com.selectcode.pos.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Shop settings, branches, tills, numbering and payment methods (SET-01..08; QA TC-SET). */
@SuppressWarnings("unchecked")
class ShopSetupIT extends IntegrationTest {

	@Test
	void settingsChangesAreValidatedAndRecordedWithOldAndNewValues() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		var settings = o.get("/api/v1/settings").andExpect(status().isOk());
		assertThat((String) json(settings, "$.taxMode")).isEqualTo("NONE");
		assertThat((String) json(settings, "$.receiptSize")).isEqualTo("MM80");

		o.patch("/api/v1/settings", """
				{"taxMode":"exclusive","taxRate":"17","receiptSize":"MM58","receiptLanguage":"UR","closingTime":"23:30",
				 "returnDays":14,"discountLimitPct":"5","urduName":"علی موبائلز","allowNegativeStock":true}""")
			.andExpect(status().isOk());
		var after = o.get("/api/v1/settings");
		assertThat((String) json(after, "$.taxMode")).isEqualTo("EXCLUSIVE");
		assertThat((String) json(after, "$.taxRate")).isEqualTo("17");
		assertThat((String) json(after, "$.closingTime")).isEqualTo("23:30");
		assertThat((String) json(after, "$.urduName")).isEqualTo("علی موبائلز");

		o.patch("/api/v1/settings", "{\"taxRate\":\"18\"}").andExpect(status().isOk());
		var history = o.get("/api/v1/settings/history").andExpect(status().isOk());
		List<String> keys = json(history, "$.items[*].key");
		assertThat(keys).contains("taxMode", "taxRate", "receiptSize", "closingTime");
		assertThat((String) json(history, "$.items[0].oldValue")).isEqualTo("17");
		assertThat((String) json(history, "$.items[0].newValue")).isEqualTo("18");

		o.patch("/api/v1/settings", "{\"taxRate\":\"120\"}").andExpect(status().isBadRequest());
		o.patch("/api/v1/settings", "{\"taxMode\":\"SOMETIMES\"}").andExpect(status().isBadRequest());
		o.patch("/api/v1/settings", "{\"businessName\":\"  \"}").andExpect(status().isBadRequest());
		o.patch("/api/v1/settings", "{\"closingTime\":\"late\"}").andExpect(status().isBadRequest());
		o.patch("/api/v1/settings", "{\"email\":\"not-an-email\"}").andExpect(status().isBadRequest());
		o.patch("/api/v1/settings", "{\"sessionIdleMinutes\":1}").andExpect(status().isBadRequest());
		o.patch("/api/v1/settings", "{\"logoFileId\":999999}").andExpect(status().isBadRequest());
	}

	@Test
	void logoUploadIsCheckedByContent() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		byte[] png = java.util.Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
		Integer fileId = json(shop.owner().upload("/api/v1/files", "SHOP_LOGO", "logo.png", png).andExpect(status().isOk()), "$.id");
		shop.owner().patch("/api/v1/settings", "{\"logoFileId\":" + fileId + "}").andExpect(status().isOk());
		shop.owner().get("/api/v1/files/" + fileId).andExpect(status().isOk());
		// A script renamed to .png is refused.
		shop.owner().upload("/api/v1/files", "SHOP_LOGO", "logo.png", "<script>alert(1)</script>".getBytes())
			.andExpect(status().isUnprocessableContent());
		ApiClient cashier = userWithRole(shop, "CASHIER", unique("c"));
		cashier.upload("/api/v1/files", "SHOP_LOGO", "logo.png", png).andExpect(status().isForbidden());
		var pub = client().get("/api/v1/public/shops/" + shop.code()).andExpect(status().isOk());
		assertThat((Integer) json(pub, "$.logoFileId")).isEqualTo(fileId);
	}

	@Test
	void branchesAndTills() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		var created = o.post("/api/v1/branches", "{\"name\":\"Branch 2\",\"code\":\"b2x\",\"kind\":\"SHOP\"}")
			.andExpect(status().isCreated());
		Integer id = json(created, "$.id");
		assertThat((String) json(created, "$.code")).isEqualTo("B2X");
		o.post("/api/v1/branches", "{\"name\":\"branch 2\"}").andExpect(status().isConflict());
		o.post("/api/v1/branches", "{\"name\":\"Other\",\"code\":\"B2X\"}").andExpect(status().isConflict());
		o.post("/api/v1/branches", "{\"name\":\"\"}").andExpect(status().isBadRequest());
		List<String> tills = json(o.get("/api/v1/tills"), "$[?(@.branchId==" + id + ")].name");
		assertThat(tills).containsExactly("Till 1");

		Integer defaultId = json(o.get("/api/v1/branches"), "$[0].id");
		o.post("/api/v1/branches/" + defaultId + "/deactivate").andExpect(status().isUnprocessableContent());
		o.post("/api/v1/branches/" + id + "/make-default").andExpect(status().isOk());
		assertThat((Integer) json(o.get("/api/v1/branches"), "$[0].id")).isEqualTo(id);
		o.post("/api/v1/branches/" + defaultId + "/deactivate").andExpect(status().isOk());
		o.post("/api/v1/branches/" + defaultId + "/activate").andExpect(status().isOk());

		Integer till = json(o.post("/api/v1/tills", "{\"branchId\":" + id + ",\"name\":\"Till 2\"}")
			.andExpect(status().isCreated()), "$.id");
		o.post("/api/v1/tills", "{\"branchId\":" + id + ",\"name\":\"till 2\"}").andExpect(status().isConflict());
		o.patch("/api/v1/tills/" + till, "{\"active\":false}").andExpect(status().isOk());
		o.patch("/api/v1/branches/" + id, "{\"phone\":\"042-1234567\"}").andExpect(status().isOk());
	}

	@Test
	void numberingOnlyGoesUpAndPaymentMethodsKeepCash() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		var numbering = o.get("/api/v1/numbering").andExpect(status().isOk());
		List<String> types = json(numbering, "$[*].docType");
		assertThat(types).contains("SALE", "PURCHASE_ORDER", "GRN", "ADJUSTMENT", "TRANSFER");
		Integer sale = ((List<Integer>) json(numbering, "$[?(@.docType=='SALE')].id")).getFirst();
		var changed = o.patch("/api/v1/numbering/" + sale, "{\"prefix\":\"inv-a\",\"nextNo\":1000}").andExpect(status().isOk());
		assertThat((String) json(changed, "$.example")).isEqualTo("INV-A-001000");
		o.patch("/api/v1/numbering/" + sale, "{\"nextNo\":10}").andExpect(status().isBadRequest());
		o.patch("/api/v1/numbering/" + sale, "{\"prefix\":\"bad prefix\"}").andExpect(status().isBadRequest());

		var methods = o.get("/api/v1/payment-methods").andExpect(status().isOk());
		Integer cash = ((List<Integer>) json(methods, "$[?(@.key=='CASH')].id")).getFirst();
		Integer cheque = ((List<Integer>) json(methods, "$[?(@.key=='CHEQUE')].id")).getFirst();
		o.patch("/api/v1/payment-methods/" + cash, "{\"active\":false}").andExpect(status().isUnprocessableContent());
		var on = o.patch("/api/v1/payment-methods/" + cheque, "{\"active\":true,\"label\":\"Cheque (post-dated ok)\"}")
			.andExpect(status().isOk());
		assertThat((Boolean) json(on, "$.active")).isTrue();
	}
}
