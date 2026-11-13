package com.selectcode.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.selectcode.pos.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Writes the OpenAPI document to {@code target/openapi.json} and {@code openapi.json} (committed). The frontend
 * generates its API types from it ({@code npm run gen:api}); CI fails if the committed copy drifts.
 */
class OpenApiExportIT extends IntegrationTest {

	@Test
	void exportOpenApiDocument() throws Exception {
		String json = mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(json).contains("/api/v1/auth/login", "/api/v1/platform/shops", "/api/v1/products", "/api/v1/shop/context");
		Path target = Path.of("target", "openapi.json");
		Files.createDirectories(target.getParent());
		Files.writeString(target, json, StandardCharsets.UTF_8);
		Files.writeString(Path.of("openapi.json"), json, StandardCharsets.UTF_8);
	}
}
