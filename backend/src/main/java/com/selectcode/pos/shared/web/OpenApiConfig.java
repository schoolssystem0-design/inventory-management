package com.selectcode.pos.shared.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the OpenAPI document describe responses as they are: the API always writes every field (nulls included), so
 * every property of a schema used only in responses is marked required. The generated frontend types then need no
 * "possibly undefined" checks. Schemas also used in request bodies stay optional, so partial updates work.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

	@Bean
	OpenApiCustomizer responsePropertiesAreAlwaysPresent() {
		return api -> {
			if (api.getComponents() == null || api.getComponents().getSchemas() == null || api.getPaths() == null) {
				return;
			}
			Map<String, Schema> schemas = api.getComponents().getSchemas();
			Set<String> inResponses = new HashSet<>();
			Set<String> inRequests = new HashSet<>();
			api.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
				collectOperation(op, schemas, inResponses, inRequests);
			}));
			for (String name : inResponses) {
				if (inRequests.contains(name)) {
					continue;
				}
				Schema<?> schema = schemas.get(name);
				if (schema != null && schema.getProperties() != null) {
					schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
				}
			}
		};
	}

	private static void collectOperation(Operation op, Map<String, Schema> schemas, Set<String> responses,
			Set<String> requests) {
		if (op.getRequestBody() != null) {
			collectContent(op.getRequestBody().getContent(), schemas, requests);
		}
		if (op.getResponses() != null) {
			op.getResponses().values().forEach(r -> collectContent(r.getContent(), schemas, responses));
		}
	}

	private static void collectContent(Content content, Map<String, Schema> schemas, Set<String> into) {
		if (content == null) {
			return;
		}
		content.values().forEach(media -> collect(media.getSchema(), schemas, into));
	}

	@SuppressWarnings("rawtypes")
	private static void collect(Schema schema, Map<String, Schema> schemas, Set<String> into) {
		if (schema == null) {
			return;
		}
		if (schema.get$ref() != null) {
			String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
			if (into.add(name)) {
				collect(schemas.get(name), schemas, into);
			}
			return;
		}
		if (schema.getItems() != null) {
			collect(schema.getItems(), schemas, into);
		}
		if (schema.getAdditionalProperties() instanceof Schema extra) {
			collect(extra, schemas, into);
		}
		if (schema.getProperties() != null) {
			((Map<String, Schema>) schema.getProperties()).values().forEach(p -> collect(p, schemas, into));
		}
		if (schema.getAllOf() != null) {
			((java.util.List<Schema>) schema.getAllOf()).forEach(s -> collect(s, schemas, into));
		}
		if (schema.getOneOf() != null) {
			((java.util.List<Schema>) schema.getOneOf()).forEach(s -> collect(s, schemas, into));
		}
	}
}
