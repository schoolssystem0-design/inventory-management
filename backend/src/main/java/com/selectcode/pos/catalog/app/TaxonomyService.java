package com.selectcode.pos.catalog.app;

import com.selectcode.pos.catalog.domain.Brand;
import com.selectcode.pos.catalog.domain.Category;
import com.selectcode.pos.catalog.domain.Unit;
import com.selectcode.pos.catalog.infra.BrandRepository;
import com.selectcode.pos.catalog.infra.CategoryRepository;
import com.selectcode.pos.catalog.infra.UnitRepository;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.web.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Categories (a tree), brands and units (CAT-03), plus the type template seeding of a new shop (ONB-07). */
@Service
public class TaxonomyService {

	public record CategoryView(long id, Long parentId, String name, int sortOrder, boolean active, long productCount,
			long version) {
	}

	public record CategoryInput(Long parentId, String name, Integer sortOrder, Boolean active) {
	}

	public record BrandView(long id, String name, boolean active, long productCount, long version) {
	}

	public record UnitView(long id, String name, boolean decimalsAllowed, boolean active, long version) {
	}

	public record NamedInput(String name, Boolean active, Boolean decimalsAllowed) {
	}

	public record UnitSeed(String name, boolean decimals) {
	}

	private final CategoryRepository categories;

	private final BrandRepository brands;

	private final UnitRepository units;

	private final JdbcTemplate jdbc;

	private final AuditService audit;

	TaxonomyService(CategoryRepository categories, BrandRepository brands, UnitRepository units, JdbcTemplate jdbc,
			AuditService audit) {
		this.categories = categories;
		this.brands = brands;
		this.units = units;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	/** Default categories and units from the shop type template. Runs inside the shop creation transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void seed(List<String> categoryNames, List<UnitSeed> unitSeeds) {
		int order = 0;
		for (String name : categoryNames) {
			categories.save(new Category(null, name, order++));
		}
		for (UnitSeed unit : unitSeeds) {
			units.save(new Unit(unit.name(), unit.decimals()));
		}
		if (unitSeeds.isEmpty()) {
			units.save(new Unit("piece", false));
		}
	}

	// ---- Categories

	@Transactional(readOnly = true)
	public List<CategoryView> categories() {
		Map<Long, Long> counts = counts("category_id");
		return categories.findAll()
			.stream()
			.sorted(java.util.Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName))
			.map(c -> new CategoryView(c.getId(), c.getParentId(), c.getName(), c.getSortOrder(), c.isActive(),
					counts.getOrDefault(c.getId(), 0L), c.getVersion()))
			.toList();
	}

	@Transactional
	public CategoryView createCategory(CategoryInput input) {
		String name = name(input.name(), 100);
		Long parent = input.parentId();
		if (parent != null) {
			findCategory(parent);
		}
		ensureCategoryUnique(name, parent, -1);
		Category saved = categories.save(new Category(parent, name, input.sortOrder() == null ? 0 : input.sortOrder()));
		audit.record("CATEGORY_CREATED", "category", saved.getId(), null, Map.of("name", name));
		return categoryView(saved);
	}

	@Transactional
	public CategoryView updateCategory(long id, CategoryInput input) {
		Category category = findCategory(id);
		String name = input.name() == null ? category.getName() : name(input.name(), 100);
		Long parent = input.parentId() == null ? category.getParentId() : (input.parentId() <= 0 ? null : input.parentId());
		if (parent != null) {
			findCategory(parent);
			ensureNoCycle(id, parent);
		}
		ensureCategoryUnique(name, parent, id);
		category.update(parent, name, input.sortOrder() == null ? category.getSortOrder() : input.sortOrder(),
				input.active() == null ? category.isActive() : input.active());
		audit.record("CATEGORY_UPDATED", "category", id, null, Map.of("name", name, "active", category.isActive()));
		return categoryView(category);
	}

	private CategoryView categoryView(Category c) {
		categories.flush();
		return categories().stream().filter(v -> v.id() == c.getId()).findFirst().orElseThrow();
	}

	private Category findCategory(long id) {
		return categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
	}

	private void ensureNoCycle(long id, Long parent) {
		Set<Long> seen = new HashSet<>();
		Long current = parent;
		while (current != null) {
			if (current == id || !seen.add(current)) {
				throw ApiException.rule("category_cycle", "A category cannot sit inside itself");
			}
			current = categories.findById(current).map(Category::getParentId).orElse(null);
		}
	}

	private void ensureCategoryUnique(String name, Long parent, long exceptId) {
		boolean taken = categories.findByNameIgnoreCase(name)
			.stream()
			.anyMatch(c -> c.getId() != exceptId && java.util.Objects.equals(c.getParentId(), parent));
		if (taken) {
			throw ApiException.conflict("duplicate_name", "A category with this name already exists here");
		}
	}

	// ---- Brands

	@Transactional(readOnly = true)
	public List<BrandView> brands() {
		Map<Long, Long> counts = counts("brand_id");
		return brands.findAllByName()
			.stream()
			.map(b -> new BrandView(b.getId(), b.getName(), b.isActive(), counts.getOrDefault(b.getId(), 0L),
					b.getVersion()))
			.toList();
	}

	@Transactional
	public BrandView createBrand(NamedInput input) {
		String name = name(input.name(), 100);
		if (!brands.findByNameIgnoreCase(name).isEmpty()) {
			throw ApiException.conflict("duplicate_name", "A brand with this name already exists");
		}
		Brand saved = brands.save(new Brand(name));
		audit.record("BRAND_CREATED", "brand", saved.getId(), null, Map.of("name", name));
		return new BrandView(saved.getId(), saved.getName(), true, 0, saved.getVersion());
	}

	@Transactional
	public BrandView updateBrand(long id, NamedInput input) {
		Brand brand = brands.findById(id).orElseThrow(() -> ApiException.notFound("Brand"));
		String name = input.name() == null ? brand.getName() : name(input.name(), 100);
		if (brands.findByNameIgnoreCase(name).stream().anyMatch(b -> b.getId() != id)) {
			throw ApiException.conflict("duplicate_name", "A brand with this name already exists");
		}
		brand.update(name, input.active() == null ? brand.isActive() : input.active());
		audit.record("BRAND_UPDATED", "brand", id, null, Map.of("name", name, "active", brand.isActive()));
		brands.flush();
		return brands().stream().filter(b -> b.id() == id).findFirst().orElseThrow();
	}

	// ---- Units

	@Transactional(readOnly = true)
	public List<UnitView> units() {
		return units.findAllByName()
			.stream()
			.map(u -> new UnitView(u.getId(), u.getName(), u.isDecimalsAllowed(), u.isActive(), u.getVersion()))
			.toList();
	}

	@Transactional
	public UnitView createUnit(NamedInput input) {
		String name = name(input.name(), 30);
		if (!units.findByNameIgnoreCase(name).isEmpty()) {
			throw ApiException.conflict("duplicate_name", "A unit with this name already exists");
		}
		Unit saved = units.save(new Unit(name, Boolean.TRUE.equals(input.decimalsAllowed())));
		audit.record("UNIT_CREATED", "unit", saved.getId(), null, Map.of("name", name));
		return new UnitView(saved.getId(), saved.getName(), saved.isDecimalsAllowed(), true, saved.getVersion());
	}

	@Transactional
	public UnitView updateUnit(long id, NamedInput input) {
		Unit unit = units.findById(id).orElseThrow(() -> ApiException.notFound("Unit"));
		String name = input.name() == null ? unit.getName() : name(input.name(), 30);
		if (units.findByNameIgnoreCase(name).stream().anyMatch(u -> u.getId() != id)) {
			throw ApiException.conflict("duplicate_name", "A unit with this name already exists");
		}
		unit.update(name, input.decimalsAllowed() == null ? unit.isDecimalsAllowed() : input.decimalsAllowed(),
				input.active() == null ? unit.isActive() : input.active());
		audit.record("UNIT_UPDATED", "unit", id, null, Map.of("name", name));
		units.flush();
		return units().stream().filter(u -> u.id() == id).findFirst().orElseThrow();
	}

	// ---- helpers used by import

	/** Finds a category by name (top level first) or creates it. */
	@Transactional(propagation = Propagation.MANDATORY)
	public long categoryIdFor(String name) {
		return categories.findByNameIgnoreCase(name.trim())
			.stream()
			.min(java.util.Comparator.comparing(c -> c.getParentId() == null ? 0 : 1))
			.map(Category::getId)
			.orElseGet(() -> categories.save(new Category(null, name.trim(), 999)).getId());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public long brandIdFor(String name) {
		return brands.findByNameIgnoreCase(name.trim())
			.stream()
			.findFirst()
			.map(Brand::getId)
			.orElseGet(() -> brands.save(new Brand(name.trim())).getId());
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public long unitIdFor(String name) {
		return units.findByNameIgnoreCase(name.trim())
			.stream()
			.findFirst()
			.map(Unit::getId)
			.orElseGet(() -> units.save(new Unit(name.trim().toLowerCase(), false)).getId());
	}

	private Map<Long, Long> counts(String column) {
		Map<Long, Long> counts = new java.util.HashMap<>();
		jdbc.query("select " + column + " as id, count(*) as n from product where " + column
				+ " is not null and active group by " + column, rs -> {
					counts.put(rs.getLong("id"), rs.getLong("n"));
				});
		return counts;
	}

	private static String name(String value, int max) {
		String s = value == null ? "" : value.trim();
		if (s.isEmpty()) {
			throw ApiException.field("name", "required", "Enter a name");
		}
		if (s.length() > max) {
			throw ApiException.field("name", "too_long", "Use at most " + max + " characters");
		}
		return s;
	}
}
