package com.selectcode.pos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.selectcode.pos.shared.persistence.ShopScopedEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Architecture rules (docs/ARCHITECTURE.md §2, docs/SECURITY.md §2, docs/CODING-STANDARDS.md). */
class ArchitectureTest {

	static JavaClasses classes;

	@BeforeAll
	static void importClasses() {
		classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.selectcode.pos");
	}

	@Test
	void moduleBoundariesAreRespected() {
		ApplicationModules.of(PosApplication.class).verify();
	}

	@Test
	void noFloatingPointFields() {
		// Money and quantities are never double/float.
		fields().should()
			.notHaveRawType(double.class)
			.andShould()
			.notHaveRawType(float.class)
			.andShould()
			.notHaveRawType(Double.class)
			.andShould()
			.notHaveRawType(Float.class)
			.check(classes);
	}

	@Test
	void entitiesOutsidePlatformAreShopScoped() {
		com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
			.that()
			.areAnnotatedWith(Entity.class)
			.and()
			.resideOutsideOfPackage("com.selectcode.pos.platform..")
			.should()
			.beAssignableTo(ShopScopedEntity.class)
			.check(classes);
	}

	@Test
	void everyEndpointDeclaresItsAccessRule() {
		// Deny by default: each handler states a permission, "any signed-in user" or "public".
		methods().that()
			.areDeclaredInClassesThat()
			.areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
			.and()
			.areAnnotatedWith(org.springframework.web.bind.annotation.GetMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.PostMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.PutMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.PatchMapping.class)
			.or()
			.areAnnotatedWith(org.springframework.web.bind.annotation.DeleteMapping.class)
			.should()
			.beAnnotatedWith(org.springframework.security.access.prepost.PreAuthorize.class)
			.orShould()
			.beAnnotatedWith(com.selectcode.pos.shared.security.AnyAuthenticated.class)
			.orShould()
			.beAnnotatedWith(com.selectcode.pos.shared.security.PublicEndpoint.class)
			.check(classes);
	}

	@Test
	void controllersDoNotUseRepositories() {
		noClasses().that()
			.resideInAPackage("..api..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("..infra..")
			.check(classes);
	}

	@Test
	void crossShopAccessOnlyFromPlatform() {
		noClasses().that()
			.resideOutsideOfPackage("com.selectcode.pos.platform..")
			.should()
			.callMethod(com.selectcode.pos.shared.tenant.TenantContext.class, "callAcrossAllShops",
					java.util.function.Supplier.class)
			.check(classes);
	}

	@Test
	void onlyTheStockModuleWritesStock() throws Exception {
		// D1, CODING-STANDARDS §2: stock balances and movements change only through stock.app.StockService.
		java.util.regex.Pattern write = java.util.regex.Pattern.compile(
				"(?i)(insert\\s+into|update|delete\\s+from)\\s+(product_stock|stock_movement)\\b");
		java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java", "com", "selectcode", "pos");
		java.util.List<String> offenders = new java.util.ArrayList<>();
		try (var files = java.nio.file.Files.walk(root)) {
			for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String source = java.nio.file.Files.readString(file);
				boolean stockModule = file.toString().replace('\\', '/').contains("/pos/stock/app/StockService.java");
				if (!stockModule && write.matcher(source).find()) {
					offenders.add(root.relativize(file).toString());
				}
			}
		}
		org.assertj.core.api.Assertions.assertThat(offenders).as("classes writing stock tables directly").isEmpty();
	}

	@Test
	void apiTypesHaveUniqueNames() {
		// OpenAPI (and the generated frontend types) name schemas by simple class name.
		java.util.Map<String, java.util.Set<String>> byName = new java.util.TreeMap<>();
		java.util.Set<Class<?>> seen = new java.util.HashSet<>();
		for (var javaClass : classes) {
			Class<?> type = javaClass.reflect();
			if (type.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class)) {
				for (var method : type.getDeclaredMethods()) {
					collect(method.getGenericReturnType(), seen);
					for (var parameter : method.getGenericParameterTypes()) {
						collect(parameter, seen);
					}
				}
			}
		}
		seen.forEach(t -> byName.computeIfAbsent(t.getSimpleName(), k -> new java.util.TreeSet<>()).add(t.getName()));
		var duplicates = byName.entrySet().stream().filter(e -> e.getValue().size() > 1).toList();
		org.assertj.core.api.Assertions.assertThat(duplicates).as("API types sharing a simple name").isEmpty();
	}

	private static void collect(java.lang.reflect.Type type, java.util.Set<Class<?>> seen) {
		if (type instanceof java.lang.reflect.ParameterizedType p) {
			collect(p.getRawType(), seen);
			for (var argument : p.getActualTypeArguments()) {
				collect(argument, seen);
			}
		}
		else if (type instanceof java.lang.reflect.WildcardType w) {
			for (var bound : w.getUpperBounds()) {
				collect(bound, seen);
			}
		}
		else if (type instanceof Class<?> c) {
			if (c.isArray()) {
				collect(c.getComponentType(), seen);
				return;
			}
			if (!c.getName().startsWith("com.selectcode.pos.") || !seen.add(c)) {
				return;
			}
			if (c.isRecord()) {
				for (var component : c.getRecordComponents()) {
					collect(component.getGenericType(), seen);
				}
			}
		}
	}
}
