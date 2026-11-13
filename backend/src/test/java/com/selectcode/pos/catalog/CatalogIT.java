package com.selectcode.pos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.ApiClient;
import com.selectcode.pos.support.IntegrationTest;
import com.selectcode.pos.support.TestDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Products, categories, brands, units, import/export, labels (CAT-01..09, INT-05, PLT-18; QA TC-CAT-*). */
@SuppressWarnings("unchecked")
class CatalogIT extends IntegrationTest {

	@Test
	void productsGetAutomaticSkusUniqueBarcodesAndAreFoundByAnyCode() throws Exception {
		TestShop shop = newShop("super_store", "enterprise");
		ApiClient o = shop.owner();
		int piece = unit(o, "piece");
		int carton = unit(o, "carton");
		var created = o.post("/api/v1/products", """
				{"name":"Juice 250ml","urduName":"جوس","price":"80","costPrice":"62.5","unitId":%d,
				 "barcodes":["8961000000017","8961000000024"],
				 "packUnits":[{"unitId":%d,"factor":"24","price":"1850","barcode":"18961000000014"}]}"""
			.formatted(piece, carton)).andExpect(status().isCreated());
		Integer id = json(created, "$.id");
		assertThat((String) json(created, "$.sku")).matches("P-\\d{5}");
		assertThat((String) json(created, "$.price")).isEqualTo("80.00");
		assertThat((String) json(created, "$.costAvg")).isEqualTo("62.50");
		assertThat((List<String>) json(created, "$.barcodes")).containsExactly("8961000000017", "8961000000024");
		assertThat((String) json(created, "$.packUnits[0].factor")).isEqualTo("24.000");

		for (String code : List.of("8961000000017", "8961000000024", "18961000000014", json(created, "$.sku"))) {
			assertThat((Integer) json(o.get("/api/v1/products/lookup?code=" + code).andExpect(status().isOk()), "$.id"))
				.isEqualTo(id);
		}
		o.get("/api/v1/products/lookup?code=000").andExpect(status().isNotFound());

		var dupBarcode = o.post("/api/v1/products", """
				{"name":"Other","price":"1","unitId":%d,"barcodes":["8961000000024"]}""".formatted(piece))
			.andExpect(status().isConflict());
		assertThat((String) json(dupBarcode, "$.code")).isEqualTo("duplicate_barcode");
		o.post("/api/v1/products", """
				{"name":"Other","price":"1","unitId":%d,"barcodes":["18961000000014"]}""".formatted(piece))
			.andExpect(status().isConflict());
		String sku = json(created, "$.sku");
		var dupSku = o.post("/api/v1/products", """
				{"name":"Other","sku":"%s","price":"1","unitId":%d}""".formatted(sku.toLowerCase(), piece))
			.andExpect(status().isConflict());
		assertThat((String) json(dupSku, "$.code")).isEqualTo("duplicate_sku");

		o.post("/api/v1/products", "{\"name\":\"\",\"price\":\"1\",\"unitId\":" + piece + "}").andExpect(status().isBadRequest());
		o.post("/api/v1/products", "{\"name\":\"X\",\"price\":\"1.555\",\"unitId\":" + piece + "}").andExpect(status().isBadRequest());
		o.post("/api/v1/products", "{\"name\":\"X\",\"price\":\"-5\",\"unitId\":" + piece + "}").andExpect(status().isBadRequest());
		o.post("/api/v1/products", "{\"name\":\"X\",\"price\":\"5\",\"minPrice\":\"6\",\"unitId\":" + piece + "}")
			.andExpect(status().isBadRequest());
		o.post("/api/v1/products", "{\"name\":\"X\",\"price\":\"5\"}").andExpect(status().isBadRequest());

		// Search by name, Urdu name and barcode.
		assertThat((Integer) json(o.get("/api/v1/products?q=juice"), "$.total")).isEqualTo(1);
		assertThat((Integer) json(o.get("/api/v1/products?q=جوس"), "$.total")).isEqualTo(1);
		assertThat((Integer) json(o.get("/api/v1/products?q=8961000000017"), "$.total")).isEqualTo(1);
	}

	@Test
	void priceChangesAreRecordedAndStaleEditsRefused() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		var created = o.post("/api/v1/products", "{\"name\":\"Pen\",\"price\":\"50\",\"costPrice\":\"30\",\"unitId\":"
				+ unit(o, "piece") + "}").andExpect(status().isCreated());
		Integer id = json(created, "$.id");
		Integer version = json(created, "$.version");
		o.patch("/api/v1/products/" + id, "{\"price\":\"55\",\"priceChangeReason\":\"New supplier rate\",\"version\":"
				+ version + "}").andExpect(status().isOk());
		var stale = o.patch("/api/v1/products/" + id, "{\"price\":\"60\",\"version\":" + version + "}")
			.andExpect(status().isConflict());
		assertThat((String) json(stale, "$.code")).isEqualTo("stale_version");
		var history = o.get("/api/v1/products/" + id + "/history").andExpect(status().isOk());
		List<String> types = json(history, "$[*].type");
		assertThat(types).contains("PRICE_CHANGE", "PRODUCT_CREATED", "PRODUCT_UPDATED");
		List<String> newPrices = json(history, "$[?(@.type=='PRICE_CHANGE')].newPrice");
		assertThat(newPrices).contains("55.00", "50.00");
	}

	@Test
	void deactivatedProductsLeaveListsButKeepHistory() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		Integer id = json(o.post("/api/v1/products", "{\"name\":\"Old biscuit\",\"price\":\"20\",\"unitId\":"
				+ unit(o, "piece") + "}"), "$.id");
		o.post("/api/v1/products/" + id + "/deactivate").andExpect(status().isOk());
		assertThat((Integer) json(o.get("/api/v1/products?q=biscuit"), "$.total")).isZero();
		assertThat((Integer) json(o.get("/api/v1/products?q=biscuit&active=false"), "$.total")).isEqualTo(1);
		o.get("/api/v1/products/" + id).andExpect(status().isOk());
		o.post("/api/v1/products/" + id + "/activate").andExpect(status().isOk());
	}

	@Test
	void moduleFieldsAreSavedOnlyWhenTheModuleIsOn() throws Exception {
		TestShop medical = newShop("medical", "enterprise");
		ApiClient m = medical.owner();
		var med = m.post("/api/v1/products", """
				{"name":"Panadol 500mg","price":"40","unitId":%d,"kind":"BATCH",
				 "pharma":{"formula":"Paracetamol","strength":"500 mg","dosageForm":"Tablet","schedule":"otc","rack":"B3"}}"""
			.formatted(unit(m, "strip"))).andExpect(status().isCreated());
		assertThat((String) json(med, "$.pharma.formula")).isEqualTo("Paracetamol");
		assertThat((String) json(med, "$.pharma.schedule")).isEqualTo("OTC");
		assertThat((String) json(med, "$.kind")).isEqualTo("BATCH");
		assertThat((Integer) json(m.get("/api/v1/products?q=paracetamol"), "$.total")).isEqualTo(1);
		m.post("/api/v1/products", """
				{"name":"Bad","price":"1","unitId":%d,"pharma":{"schedule":"MAYBE"}}""".formatted(unit(m, "strip")))
			.andExpect(status().isBadRequest());

		TestShop autoParts = newShop("auto_parts", "enterprise");
		ApiClient a = autoParts.owner();
		a.post("/api/v1/products", """
				{"name":"Brake pad","price":"1200","unitId":%d,"part":{"oemNumber":"04465-0K240","alternateNumbers":["D2210"]}}"""
			.formatted(unit(a, "piece"))).andExpect(status().isCreated());
		assertThat((Integer) json(a.get("/api/v1/products?q=D2210"), "$.total")).isEqualTo(1);
		var refused = a.post("/api/v1/products", """
				{"name":"Phone","price":"1","unitId":%d,"kind":"SERIAL"}""".formatted(unit(a, "piece")))
			.andExpect(status().isForbidden());
		assertThat((String) json(refused, "$.code")).isEqualTo("module_disabled");
		a.post("/api/v1/products", """
				{"name":"Loose oil","price":"1","unitId":%d,"weighed":true}""".formatted(unit(a, "litre")))
			.andExpect(status().isForbidden());
	}

	@Test
	void bulkPricePreviewThenApplyWithRounding() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		Integer brand = json(o.post("/api/v1/brands", "{\"name\":\"Nestle\"}"), "$.id");
		int piece = unit(o, "piece");
		Integer a = json(o.post("/api/v1/products", "{\"name\":\"Milk\",\"price\":\"210\",\"brandId\":" + brand
				+ ",\"unitId\":" + piece + "}"), "$.id");
		o.post("/api/v1/products", "{\"name\":\"Cereal\",\"price\":\"643\",\"brandId\":" + brand + ",\"unitId\":" + piece + "}");
		o.post("/api/v1/products", "{\"name\":\"Other brand\",\"price\":\"100\",\"unitId\":" + piece + "}");
		o.post("/api/v1/products/bulk-price/preview", "{\"mode\":\"PERCENT\",\"value\":\"5\"}").andExpect(status().isUnprocessableContent());
		String request = "{\"brandId\":" + brand + ",\"mode\":\"PERCENT\",\"value\":\"5\",\"rounding\":\"FIVE\"}";
		var preview = o.post("/api/v1/products/bulk-price/preview", request).andExpect(status().isOk());
		assertThat((Integer) json(preview, "$.count")).isEqualTo(2);
		assertThat((List<String>) json(preview, "$.lines[*].newPrice")).containsExactlyInAnyOrder("220.00", "675.00");
		assertThat((String) json(o.get("/api/v1/products/" + a), "$.price")).isEqualTo("210.00");
		o.post("/api/v1/products/bulk-price", request).andExpect(status().isOk());
		assertThat((String) json(o.get("/api/v1/products/" + a), "$.price")).isEqualTo("220.00");
		assertThat((List<String>) json(o.get("/api/v1/products/" + a + "/history"), "$[*].description"))
			.contains("Bulk price update");
	}

	@Test
	void importDryRunListsProblemsThenCommitSavesEverything() throws Exception {
		TestShop shop = newShop("medical", "enterprise");
		ApiClient o = shop.owner();
		byte[] template = o.get("/api/v1/products/import/template").andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsByteArray();
		List<String> headers = headerRow(template);
		assertThat(headers).contains("Name", "Price", "Formula", "Schedule").doesNotContain("OEM number");

		byte[] bad = xlsx(List.of(List.of("Name", "Price", "Barcode", "Category", "Unit", "Formula", "Schedule", "Unknown col"),
				List.of("Panadol", "40", "8960000000011", "Tablets", "strip", "Paracetamol", "OTC", "x"),
				List.of("", "10", "", "", "", "", "", ""),
				List.of("Brufen", "abc", "8960000000011", "Pain", "strip", "Ibuprofen", "SOMETIMES", "")));
		var dry = o.uploadWith("/api/v1/products/import", "p.xlsx", bad, "dryRun", "true").andExpect(status().isOk());
		assertThat((Boolean) json(dry, "$.committed")).isFalse();
		List<String> columns = json(dry, "$.errors[*].column");
		assertThat(columns).contains("Name", "Price", "Barcode", "Schedule");
		assertThat((List<String>) json(dry, "$.ignoredColumns")).contains("Unknown col");
		var refused = o.uploadWith("/api/v1/products/import", "p.xlsx", bad, "dryRun", "false")
			.andExpect(status().isUnprocessableContent());
		assertThat((String) json(refused, "$.code")).isEqualTo("import_has_errors");
		assertThat(TestDatabase.superuser().queryForObject("select count(*) from product where shop_id = ?", Long.class,
				shop.id())).isZero();

		byte[] good = xlsx(List.of(List.of("SKU", "Name", "Price", "Cost price", "Barcode", "Category", "Brand", "Unit", "Formula"),
				List.of("MED-1", "Panadol", "40", "31", "8960000000011", "Tablets", "GSK", "strip", "Paracetamol"),
				List.of("MED-2", "Brufen", "55.5", "", "8960000000028,8960000000035", "Pain relief", "Abbott", "box", "Ibuprofen")));
		var preview = o.uploadWith("/api/v1/products/import", "p.xlsx", good, "dryRun", "true").andExpect(status().isOk());
		assertThat((Integer) json(preview, "$.toCreate")).isEqualTo(2);
		assertThat((List<String>) json(preview, "$.newCategories")).containsExactly("Pain relief");
		assertThat((List<String>) json(preview, "$.newBrands")).containsExactly("Abbott", "GSK");
		var done = o.uploadWith("/api/v1/products/import", "p.xlsx", good, "dryRun", "false").andExpect(status().isOk());
		assertThat((Boolean) json(done, "$.committed")).isTrue();
		var brufen = o.get("/api/v1/products/lookup?code=8960000000035").andExpect(status().isOk());
		assertThat((String) json(brufen, "$.pharma.formula")).isEqualTo("Ibuprofen");
		assertThat((String) json(brufen, "$.categoryName")).isEqualTo("Pain relief");

		// Same SKUs again update instead of create.
		byte[] update = xlsx(List.of(List.of("SKU", "Name", "Price"), List.of("MED-1", "Panadol 500", "42")));
		var updated = o.uploadWith("/api/v1/products/import", "p.xlsx", update, "dryRun", "false").andExpect(status().isOk());
		assertThat((Integer) json(updated, "$.toUpdate")).isEqualTo(1);
		assertThat((String) json(o.get("/api/v1/products/lookup?code=MED-1"), "$.price")).isEqualTo("42.00");

		// CSV works too.
		byte[] csv = "Name,Price,Unit\n\"Syrup, cough\",120,bottle\n".getBytes();
		o.uploadWith("/api/v1/products/import", "p.csv", csv, "dryRun", "false").andExpect(status().isOk());
		assertThat((Integer) json(o.get("/api/v1/products?q=cough"), "$.total")).isEqualTo(1);

		// Export contains this shop's products.
		byte[] export = o.get("/api/v1/products/export").andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsByteArray();
		List<String> names = column(export, "Name");
		assertThat(names).contains("Panadol 500", "Brufen", "Syrup, cough");
		o.uploadWith("/api/v1/products/import", "p.txt", "garbage".getBytes(), "dryRun", "true").andExpect(status().isUnprocessableContent());
	}

	@Test
	void importRespectsThePlanLimitAndPlatformCanImportForAClient() throws Exception {
		TestShop shop = newShop("general", "starter");
		TestDatabase.superuser().update("update plan set max_products = 2 where plan_key = 'starter'");
		try {
			byte[] three = xlsx(List.of(List.of("Name", "Price"), List.of("A", "1"), List.of("B", "1"), List.of("C", "1")));
			var dry = shop.owner().uploadWith("/api/v1/products/import", "p.xlsx", three, "dryRun", "true").andExpect(status().isOk());
			assertThat((List<String>) json(dry, "$.errors[*].message")).anySatisfy(m -> assertThat(m).contains("plan allows 2"));
		}
		finally {
			TestDatabase.superuser().update("update plan set max_products = 3000 where plan_key = 'starter'");
		}
		ApiClient onboarding = platformUser("ONBOARDING");
		byte[] file = xlsx(List.of(List.of("Name", "Price", "Cost price"), List.of("Client item", "99", "70")));
		var imported = onboarding.uploadWith("/api/v1/platform/shops/" + shop.id() + "/import/products", "p.xlsx", file,
				"dryRun", "false").andExpect(status().isOk());
		assertThat((Boolean) json(imported, "$.committed")).isTrue();
		assertThat((Integer) json(shop.owner().get("/api/v1/products?q=client"), "$.total")).isEqualTo(1);
	}

	@Test
	void labelsUseTheRightBarcodeFormat() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		int piece = unit(o, "piece");
		Integer ean = json(o.post("/api/v1/products", "{\"name\":\"Soap\",\"price\":\"90\",\"unitId\":" + piece
				+ ",\"barcodes\":[\"4006381333931\"]}"), "$.id");
		Integer none = json(o.post("/api/v1/products", "{\"name\":\"Loose item\",\"price\":\"10\",\"unitId\":" + piece + "}"),
				"$.id");
		var labels = o.post("/api/v1/labels", "{\"items\":[{\"productId\":" + ean + ",\"copies\":3},{\"productId\":" + none
				+ "}]}").andExpect(status().isOk());
		assertThat((List<String>) json(labels, "$[*].format")).containsExactly("EAN13", "CODE128");
		var generated = o.post("/api/v1/products/" + none + "/generate-barcode").andExpect(status().isOk());
		String code = ((List<String>) json(generated, "$.barcodes")).getFirst();
		assertThat(code).hasSize(13).startsWith("200");
		assertThat((List<String>) json(o.post("/api/v1/labels", "{\"items\":[{\"productId\":" + none + "}]}"), "$[*].format"))
			.containsExactly("EAN13");
		o.post("/api/v1/products/" + none + "/generate-barcode").andExpect(status().isUnprocessableContent());
		o.post("/api/v1/labels", "{\"items\":[{\"productId\":" + ean + ",\"copies\":0}]}").andExpect(status().isBadRequest());
	}

	@Test
	void categoriesFormATreeWithoutCyclesAndFilterProducts() throws Exception {
		TestShop shop = newShop("general", "enterprise");
		ApiClient o = shop.owner();
		Integer dairy = json(o.post("/api/v1/categories", "{\"name\":\"Dairy\"}").andExpect(status().isCreated()), "$.id");
		Integer milk = json(o.post("/api/v1/categories", "{\"name\":\"Milk\",\"parentId\":" + dairy + "}"), "$.id");
		Integer uht = json(o.post("/api/v1/categories", "{\"name\":\"UHT\",\"parentId\":" + milk + "}"), "$.id");
		o.post("/api/v1/categories", "{\"name\":\"dairy\"}").andExpect(status().isConflict());
		o.patch("/api/v1/categories/" + dairy, "{\"parentId\":" + uht + "}").andExpect(status().isUnprocessableContent());
		o.post("/api/v1/products", "{\"name\":\"Olpers\",\"price\":\"300\",\"categoryId\":" + uht + ",\"unitId\":"
				+ unit(o, "piece") + "}").andExpect(status().isCreated());
		assertThat((Integer) json(o.get("/api/v1/products?categoryId=" + dairy), "$.total")).isEqualTo(1);
		var list = o.get("/api/v1/categories").andExpect(status().isOk());
		assertThat((List<Integer>) json(list, "$[?(@.name=='UHT')].productCount")).containsExactly(1);

		o.post("/api/v1/units", "{\"name\":\"bundle\",\"decimalsAllowed\":false}").andExpect(status().isCreated());
		o.post("/api/v1/units", "{\"name\":\"Bundle\"}").andExpect(status().isConflict());
		Integer brand = json(o.post("/api/v1/brands", "{\"name\":\"Olpers\"}"), "$.id");
		o.patch("/api/v1/brands/" + brand, "{\"active\":false}").andExpect(status().isOk());
	}

	// ------------------------------------------------------------------ helpers

	private int unit(ApiClient client, String name) throws Exception {
		List<Integer> ids = json(client.get("/api/v1/units"), "$[?(@.name=='" + name + "')].id");
		if (!ids.isEmpty()) {
			return ids.getFirst();
		}
		return json(client.post("/api/v1/units", "{\"name\":\"" + name + "\",\"decimalsAllowed\":true}"), "$.id");
	}

	static byte[] xlsx(List<List<String>> rows) throws Exception {
		try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = book.createSheet("Products");
			for (int r = 0; r < rows.size(); r++) {
				Row row = sheet.createRow(r);
				for (int c = 0; c < rows.get(r).size(); c++) {
					row.createCell(c).setCellValue(rows.get(r).get(c));
				}
			}
			book.write(out);
			return out.toByteArray();
		}
	}

	static List<String> headerRow(byte[] xlsx) throws Exception {
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			List<String> headers = new ArrayList<>();
			book.getSheetAt(0).getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
			return headers;
		}
	}

	static List<String> column(byte[] xlsx, String header) throws Exception {
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
			Sheet sheet = book.getSheetAt(0);
			int index = headerRow(xlsx).indexOf(header);
			List<String> values = new ArrayList<>();
			for (int r = 1; r <= sheet.getLastRowNum(); r++) {
				values.add(sheet.getRow(r).getCell(index).getStringCellValue());
			}
			return values;
		}
	}
}
