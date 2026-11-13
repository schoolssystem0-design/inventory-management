package com.selectcode.pos.catalog.app;

import com.selectcode.pos.catalog.domain.Product;
import com.selectcode.pos.catalog.infra.ProductRepository;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.PlanLimits;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.persistence.NumberSequences;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.math.BigDecimal;
import java.sql.Array;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Products (CAT-01, 02, 05, 08, 09) with module-gated extras: pack units, medicine fields, part numbers and book
 * fields. Cost is only returned to users with {@code cost.view} (ACC-05).
 */
@Service
public class ProductService {

	public record PharmaFields(String formula, String strength, String dosageForm, String company, String packSize,
			String schedule, String rack, String registrationNo) {
	}

	public record PartFields(String oemNumber, List<String> alternateNumbers) {
	}

	public record BookFields(String isbn, String author, String publisher, String edition, String className) {
	}

	public record PackUnit(long unitId, String unitName, String factor, String price, String barcode) {
	}

	public record PackUnitInput(Long unitId, String factor, String price, String barcode) {
	}

	public record ProductView(long id, String sku, String name, String urduName, Long categoryId, String categoryName,
			Long brandId, String brandName, long unitId, String unitName, boolean unitDecimals, String kind,
			String costAvg, String price, String wholesalePrice, String minPrice, String taxRate, String reorderLevel,
			String reorderQty, Long imageFileId, boolean weighed, int warrantyDays, String description, boolean active,
			List<String> barcodes, List<PackUnit> packUnits, PharmaFields pharma, PartFields part, BookFields book,
			Instant createdAt, Instant updatedAt, long version, String scaleCode) {
	}

	public record ProductListItem(long id, String sku, String name, String urduName, Long categoryId,
			String categoryName, String brandName, String unitName, String kind, String price, String costAvg,
			String barcode, boolean active, Long imageFileId, String formula) {
	}

	/**
	 * Create and update input. On update every field is optional (null = unchanged; empty list = remove all).
	 * {@code costPrice} on create is the opening average cost.
	 */
	public record ProductInput(String sku, String name, String urduName, Long categoryId, Long brandId, Long unitId,
			String kind, String costPrice, String price, String wholesalePrice, String minPrice, String taxRate,
			String reorderLevel, String reorderQty, Long imageFileId, Boolean weighed, Integer warrantyDays,
			String description,
			List<String> barcodes, List<PackUnitInput> packUnits, PharmaFields pharma, PartFields part, BookFields book,
			String priceChangeReason, Long version, String scaleCode) {
	}

	public record HistoryEntry(Instant at, String type, String description, String actorName, String oldPrice,
			String newPrice, String oldCost, String newCost) {
	}

	public record ProductQuery(String q, Long categoryId, Long brandId, Boolean active, String kind, String sort,
			int page, int size) {
	}

	private static final Map<String, String> SORTS = Map.of("name", "p.name", "sku", "p.sku", "price", "p.price",
			"updated", "p.updated_at desc", "created", "p.created_at desc");

	private final ProductRepository products;

	private final JdbcTemplate jdbc;

	private final ShopFeatures features;

	private final PlanLimits limits;

	private final NumberSequences sequences;

	private final AuditService audit;

	private final Clock clock;

	ProductService(ProductRepository products, JdbcTemplate jdbc, ShopFeatures features, PlanLimits limits,
			NumberSequences sequences, AuditService audit, Clock clock) {
		this.products = products;
		this.jdbc = jdbc;
		this.features = features;
		this.limits = limits;
		this.sequences = sequences;
		this.audit = audit;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ queries

	@Transactional(readOnly = true)
	public PageResponse<ProductListItem> search(ProductQuery query) {
		boolean showCost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (query.active() == null || query.active()) {
			where.append(" and p.active");
		}
		else {
			where.append(" and not p.active");
		}
		if (query.categoryId() != null) {
			where.append(" and p.category_id in (with recursive c(id) as (select ?::bigint union all "
					+ "select x.id from category x join c on x.parent_id = c.id) select id from c)");
			args.add(query.categoryId());
		}
		if (query.brandId() != null) {
			where.append(" and p.brand_id = ?");
			args.add(query.brandId());
		}
		if (query.kind() != null && !query.kind().isBlank()) {
			where.append(" and p.kind = ?");
			args.add(query.kind().trim().toUpperCase());
		}
		if (query.q() != null && !query.q().isBlank()) {
			String q = query.q().trim().toLowerCase();
			String like = "%" + q.replace("%", "\\%").replace("_", "\\_") + "%";
			where.append(" " + """
					and (lower(p.name) like ? or lower(p.sku) like ? or lower(coalesce(p.urdu_name, '')) like ?
					      or exists (select 1 from product_barcode b where b.product_id = p.id and b.barcode = ?)
					      or exists (select 1 from product_unit u where u.product_id = p.id and u.barcode = ?)
					      or exists (select 1 from product_pharma f where f.product_id = p.id and lower(f.formula) like ?)
					      or exists (select 1 from product_part pp where pp.product_id = p.id
					                 and (lower(pp.oem_number) like ? or ? = any(pp.alternate_numbers))))""");
			args.addAll(List.of(like, like, like, query.q().trim(), query.q().trim(), like, like, query.q().trim()));
		}
		String order = SORTS.getOrDefault(query.sort() == null ? "name" : query.sort(), "p.name");
		Long total = jdbc.queryForObject("select count(*) from product p" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(query.size());
		pageArgs.add(query.page() * query.size());
		List<ProductListItem> items = jdbc.query("""
				select p.*, c.name as category_name, b.name as brand_name, u.name as unit_name,
				       (select barcode from product_barcode pb where pb.product_id = p.id order by pb.id limit 1) as barcode,
				       (select formula from product_pharma f where f.product_id = p.id) as formula
				from product p
				left join category c on c.id = p.category_id
				left join brand b on b.id = p.brand_id
				join unit u on u.id = p.unit_id""" + where + " order by " + order + ", p.id limit ? offset ?",
				(rs, i) -> new ProductListItem(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
						rs.getString("urdu_name"), (Long) rs.getObject("category_id"), rs.getString("category_name"),
						rs.getString("brand_name"), rs.getString("unit_name"), rs.getString("kind"),
						Amounts.text(rs.getBigDecimal("price")),
						showCost ? Amounts.text(rs.getBigDecimal("cost_avg")) : null, rs.getString("barcode"),
						rs.getBoolean("active"), (Long) rs.getObject("image_file_id"), rs.getString("formula")),
				pageArgs.toArray());
		return PageResponse.of(items, query.page(), query.size(), total == null ? 0 : total);
	}

	@Transactional(readOnly = true)
	public ProductView get(long id) {
		boolean showCost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		List<ProductView> found = jdbc.query("""
				select p.*, c.name as category_name, b.name as brand_name, u.name as unit_name,
				       u.decimals_allowed as unit_decimals
				from product p
				left join category c on c.id = p.category_id
				left join brand b on b.id = p.brand_id
				join unit u on u.id = p.unit_id where p.id = ?""", (rs, i) -> {
			long pid = rs.getLong("id");
			return new ProductView(pid, rs.getString("sku"), rs.getString("name"), rs.getString("urdu_name"),
					(Long) rs.getObject("category_id"), rs.getString("category_name"), (Long) rs.getObject("brand_id"),
					rs.getString("brand_name"), rs.getLong("unit_id"), rs.getString("unit_name"),
					rs.getBoolean("unit_decimals"), rs.getString("kind"),
					showCost ? Amounts.text(rs.getBigDecimal("cost_avg")) : null, Amounts.text(rs.getBigDecimal("price")),
					Amounts.text(rs.getBigDecimal("wholesale_price")), Amounts.text(rs.getBigDecimal("min_price")),
					Amounts.percentText(rs.getBigDecimal("tax_rate")), Amounts.qtyText(rs.getBigDecimal("reorder_level")),
					Amounts.qtyText(rs.getBigDecimal("reorder_qty")), (Long) rs.getObject("image_file_id"),
					rs.getBoolean("weighed"), rs.getInt("warranty_days"), rs.getString("description"),
					rs.getBoolean("active"), barcodes(pid),
					packUnits(pid), pharma(pid), part(pid), book(pid), rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"), rs.getString("scale_code"));
		}, id);
		return found.stream().findFirst().orElseThrow(() -> ApiException.notFound("Product"));
	}

	/** Finds a product by barcode, pack-unit barcode or SKU (scanner input). */
	@Transactional(readOnly = true)
	public Optional<ProductView> lookup(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		String c = code.trim();
		List<Long> ids = jdbc.queryForList("""
				select product_id from product_barcode where barcode = ?
				union all select product_id from product_unit where barcode = ?
				union all select id from product where lower(sku) = lower(?)
				limit 1""", Long.class, c, c, c);
		if (ids.isEmpty() && features.isOn(ModuleKey.SCALE_BARCODES)) {
			String mode = jdbc.queryForObject("select scale_value_kind from shop_settings where shop_id = ?", String.class,
					com.selectcode.pos.shared.tenant.TenantContext.requireShopId());
			ids = ScaleBarcode.read(c, mode)
				.map(r -> jdbc.queryForList("select id from product where scale_code = ?", Long.class, r.itemCode()))
				.orElse(List.of());
		}
		return ids.stream().findFirst().map(this::get);
	}

	@Transactional(readOnly = true)
	public List<HistoryEntry> history(long id) {
		boolean showCost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		get(id);
		List<HistoryEntry> entries = new ArrayList<>(jdbc.query("""
				select at, reason, actor_name, old_price, new_price, old_cost, new_cost from price_change
				where product_id = ? order by at desc limit 200""", (rs, i) -> new HistoryEntry(
				rs.getTimestamp("at").toInstant(), "PRICE_CHANGE",
				rs.getString("reason") == null ? "Price changed" : rs.getString("reason"), rs.getString("actor_name"),
				Amounts.text(rs.getBigDecimal("old_price")), Amounts.text(rs.getBigDecimal("new_price")),
				showCost ? Amounts.text(rs.getBigDecimal("old_cost")) : null,
				showCost ? Amounts.text(rs.getBigDecimal("new_cost")) : null), id));
		entries.addAll(jdbc.query("""
				select at, action, actor_name from audit_log where entity_type = 'product' and entity_id = ?
				order by at desc limit 200""", (rs, i) -> new HistoryEntry(rs.getTimestamp("at").toInstant(),
				rs.getString("action"), describe(rs.getString("action")), rs.getString("actor_name"), null, null, null,
				null), id));
		entries.sort(java.util.Comparator.comparing(HistoryEntry::at).reversed());
		return entries;
	}

	// ------------------------------------------------------------------ commands

	@Transactional
	public ProductView create(ProductInput in) {
		limits.check(PlanLimits.Limit.PRODUCTS, products.countByActiveTrue(), 1);
		String name = text(in.name(), "name", 200, true);
		Product.Kind kind = kind(in.kind());
		BigDecimal price = Amounts.requireMoney(in.price(), "price");
		if (in.unitId() == null) {
			throw ApiException.field("unitId", "required", "Choose a unit");
		}
		String sku = in.sku() == null || in.sku().isBlank() ? nextSku() : sku(in.sku());
		ensureSkuFree(sku, -1);
		Product p = new Product(sku, name, requireUnit(in.unitId()), price);
		p.setKind(kind);
		p.setUrduName(text(in.urduName(), "urduName", 200, false));
		p.setCategoryId(category(in.categoryId()));
		p.setBrandId(brand(in.brandId()));
		BigDecimal cost = Amounts.parseMoney(in.costPrice(), "costPrice");
		if (cost != null && !CurrentActor.hasPermission(Permissions.COST_VIEW)) {
			throw ApiException.forbidden("forbidden", "You cannot set cost prices");
		}
		p.setCostAvg(cost == null ? BigDecimal.ZERO.setScale(2) : cost);
		p.setWholesalePrice(Amounts.parseMoney(in.wholesalePrice(), "wholesalePrice"));
		p.setMinPrice(Amounts.parseMoney(in.minPrice(), "minPrice"));
		p.setTaxRate(Amounts.parsePercent(in.taxRate(), "taxRate"));
		p.setReorderLevel(Amounts.nz(Amounts.parseQty(in.reorderLevel(), "reorderLevel")));
		p.setReorderQty(Amounts.nz(Amounts.parseQty(in.reorderQty(), "reorderQty")));
		p.setImageFileId(image(in.imageFileId()));
		p.setWeighed(weighed(in.weighed()));
		p.setWarrantyDays(warrantyDays(in.warrantyDays()));
		p.setDescription(text(in.description(), "description", 1000, false));
		checkPrices(p);
		p = products.saveAndFlush(p);
		long id = p.getId();
		replaceBarcodes(id, in.barcodes() == null ? List.of() : in.barcodes());
		saveExtras(id, in);
		scaleCode(id, in.scaleCode());
		recordPriceChange(id, null, p.getPrice(), null, p.getCostAvg(), "Product created");
		audit.record("PRODUCT_CREATED", "product", id, null, Map.of("sku", sku, "name", name));
		return get(id);
	}

	@Transactional
	public ProductView update(long id, ProductInput in) {
		Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
		if (in.version() != null && in.version() != p.getVersion()) {
			throw ApiException.conflict("stale_version", "Someone else changed this product just now. Reload and try again.");
		}
		Map<String, Object> before = snapshot(p);
		BigDecimal oldPrice = p.getPrice();
		BigDecimal oldCost = p.getCostAvg();
		if (in.name() != null) {
			p.setName(text(in.name(), "name", 200, true));
		}
		if (in.sku() != null) {
			String sku = sku(in.sku());
			ensureSkuFree(sku, id);
			p.setSku(sku);
		}
		if (in.urduName() != null) {
			p.setUrduName(text(in.urduName(), "urduName", 200, false));
		}
		if (in.categoryId() != null) {
			p.setCategoryId(in.categoryId() <= 0 ? null : category(in.categoryId()));
		}
		if (in.brandId() != null) {
			p.setBrandId(in.brandId() <= 0 ? null : brand(in.brandId()));
		}
		if (in.unitId() != null) {
			p.setUnitId(requireUnit(in.unitId()));
		}
		if (in.kind() != null) {
			p.setKind(kind(in.kind()));
		}
		boolean pricesChanged = false;
		if (in.price() != null) {
			pricesChanged |= p.getPrice().compareTo(Amounts.requireMoney(in.price(), "price")) != 0;
			p.setPrice(Amounts.requireMoney(in.price(), "price"));
		}
		if (in.wholesalePrice() != null) {
			BigDecimal v = Amounts.parseMoney(in.wholesalePrice(), "wholesalePrice");
			pricesChanged |= !sameMoney(v, p.getWholesalePrice());
			p.setWholesalePrice(v);
		}
		if (in.minPrice() != null) {
			BigDecimal v = Amounts.parseMoney(in.minPrice(), "minPrice");
			pricesChanged |= !sameMoney(v, p.getMinPrice());
			p.setMinPrice(v);
		}
		if (in.costPrice() != null) {
			if (!CurrentActor.hasPermission(Permissions.COST_VIEW)) {
				throw ApiException.forbidden("forbidden", "You cannot change cost prices");
			}
			BigDecimal v = Amounts.requireMoney(in.costPrice(), "costPrice");
			pricesChanged |= v.compareTo(p.getCostAvg()) != 0;
			p.setCostAvg(v);
		}
		if (pricesChanged && !CurrentActor.hasPermission(Permissions.CATALOG_PRICES)) {
			throw ApiException.forbidden("forbidden", "You cannot change prices");
		}
		if (in.taxRate() != null) {
			p.setTaxRate(Amounts.parsePercent(in.taxRate(), "taxRate"));
		}
		if (in.reorderLevel() != null) {
			p.setReorderLevel(Amounts.nz(Amounts.parseQty(in.reorderLevel(), "reorderLevel")));
		}
		if (in.reorderQty() != null) {
			p.setReorderQty(Amounts.nz(Amounts.parseQty(in.reorderQty(), "reorderQty")));
		}
		if (in.imageFileId() != null) {
			p.setImageFileId(in.imageFileId() <= 0 ? null : image(in.imageFileId()));
		}
		if (in.weighed() != null) {
			p.setWeighed(weighed(in.weighed()));
		}
		if (in.warrantyDays() != null) {
			p.setWarrantyDays(warrantyDays(in.warrantyDays()));
		}
		if (in.description() != null) {
			p.setDescription(text(in.description(), "description", 1000, false));
		}
		checkPrices(p);
		p.touch(clock.instant());
		products.saveAndFlush(p);
		if (in.barcodes() != null) {
			replaceBarcodes(id, in.barcodes());
		}
		saveExtras(id, in);
		scaleCode(id, in.scaleCode());
		if (oldPrice.compareTo(p.getPrice()) != 0 || oldCost.compareTo(p.getCostAvg()) != 0) {
			recordPriceChange(id, oldPrice, p.getPrice(), oldCost, p.getCostAvg(), in.priceChangeReason());
		}
		audit.record("PRODUCT_UPDATED", "product", id, before, snapshot(p));
		return get(id);
	}

	@Transactional
	public ProductView setActive(long id, boolean active) {
		Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
		if (active && !p.isActive()) {
			limits.check(PlanLimits.Limit.PRODUCTS, products.countByActiveTrue(), 1);
		}
		p.setActive(active);
		p.touch(clock.instant());
		audit.record(active ? "PRODUCT_ACTIVATED" : "PRODUCT_DEACTIVATED", "product", id, null, null);
		products.flush();
		return get(id);
	}

	/** Gives a product without a barcode an in-store EAN-13 (CAT-07). */
	@Transactional
	public ProductView generateBarcode(long id) {
		Product p = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
		if (!barcodes(id).isEmpty()) {
			throw ApiException.rule("has_barcode", "This product already has a barcode");
		}
		String code;
		do {
			code = BarcodeFormats.inStoreEan13(sequences.next("in_store_barcode"));
		}
		while (barcodeOwner(code).isPresent());
		jdbc.update("insert into product_barcode (shop_id, product_id, barcode) values (?, ?, ?)",
				TenantContext.requireShopId(), id, code);
		p.touch(clock.instant());
		audit.record("BARCODE_GENERATED", "product", id, null, Map.of("barcode", code));
		return get(id);
	}

	// ------------------------------------------------------------------ bulk price update (CAT-05)

	public record BulkPriceRequest(Long categoryId, Long brandId, List<Long> productIds, Boolean allProducts,
			String field, String mode, String value, String rounding, String reason) {
	}

	public record BulkPriceLine(long id, String sku, String name, String oldPrice, String newPrice) {
	}

	public record BulkPriceResult(boolean applied, int count, List<BulkPriceLine> lines) {
	}

	@Transactional
	public BulkPriceResult bulkPrice(BulkPriceRequest req, boolean apply) {
		String field = req.field() == null ? "PRICE" : req.field().trim().toUpperCase();
		String column = switch (field) {
			case "PRICE" -> "price";
			case "WHOLESALE" -> "wholesale_price";
			default -> throw ApiException.field("field", "invalid", "Choose PRICE or WHOLESALE");
		};
		String mode = req.mode() == null ? "" : req.mode().trim().toUpperCase();
		if (!Set.of("PERCENT", "AMOUNT", "SET").contains(mode)) {
			throw ApiException.field("mode", "invalid", "Choose PERCENT, AMOUNT or SET");
		}
		if (req.value() == null || req.value().isBlank()) {
			throw ApiException.field("value", "required", "Enter a value");
		}
		BigDecimal value;
		try {
			value = new BigDecimal(req.value().trim().replace(",", ""));
		}
		catch (NumberFormatException e) {
			throw ApiException.field("value", "invalid", "Enter a number like 5 or -10");
		}
		if (mode.equals("SET") && value.signum() < 0) {
			throw ApiException.field("value", "negative", "A price cannot be negative");
		}
		StringBuilder where = new StringBuilder(" where p.active");
		List<Object> args = new ArrayList<>();
		boolean filtered = false;
		if (req.categoryId() != null) {
			where.append(" and p.category_id in (with recursive c(id) as (select ?::bigint union all "
					+ "select x.id from category x join c on x.parent_id = c.id) select id from c)");
			args.add(req.categoryId());
			filtered = true;
		}
		if (req.brandId() != null) {
			where.append(" and p.brand_id = ?");
			args.add(req.brandId());
			filtered = true;
		}
		if (req.productIds() != null && !req.productIds().isEmpty()) {
			where.append(" and p.id = any(?)");
			args.add(req.productIds().toArray(new Long[0]));
			filtered = true;
		}
		if (!filtered && !Boolean.TRUE.equals(req.allProducts())) {
			throw ApiException.rule("no_filter", "Choose a category, brand or products (or confirm all products)");
		}
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select p.id, p.sku, p.name, p." + column + " as old from product p" + where + " order by p.name", args.toArray());
		List<BulkPriceLine> lines = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			BigDecimal old = (BigDecimal) row.get("old");
			if (old == null && !mode.equals("SET")) {
				continue;
			}
			BigDecimal next = switch (mode) {
				case "PERCENT" -> old.add(old.multiply(value).divide(BigDecimal.valueOf(100), 4, Amounts.ROUNDING));
				case "AMOUNT" -> old.add(value);
				default -> value;
			};
			next = round(next.max(BigDecimal.ZERO), req.rounding());
			if (old != null && old.compareTo(next) == 0) {
				continue;
			}
			lines.add(new BulkPriceLine(((Number) row.get("id")).longValue(), (String) row.get("sku"),
					(String) row.get("name"), Amounts.text(old), Amounts.text(next)));
		}
		if (apply) {
			String reason = req.reason() == null || req.reason().isBlank() ? "Bulk price update" : req.reason().trim();
			for (BulkPriceLine line : lines) {
				jdbc.update("update product set " + column + " = ?, updated_at = now(), version = version + 1 where id = ?",
						new BigDecimal(line.newPrice()), line.id());
				if (column.equals("price")) {
					recordPriceChange(line.id(), new BigDecimal(line.oldPrice()), new BigDecimal(line.newPrice()), null,
							null, reason);
				}
			}
			audit.record("BULK_PRICE_UPDATE", "product", null, null,
					Map.of("count", lines.size(), "field", field, "mode", mode, "value", value.toPlainString()));
		}
		return new BulkPriceResult(apply, lines.size(), lines.size() > 500 && !apply ? lines.subList(0, 500) : lines);
	}

	static BigDecimal round(BigDecimal value, String rounding) {
		int step = switch (rounding == null ? "NONE" : rounding.trim().toUpperCase()) {
			case "ONE" -> 1;
			case "FIVE" -> 5;
			case "TEN" -> 10;
			default -> 0;
		};
		if (step == 0) {
			return Amounts.money(value);
		}
		BigDecimal s = BigDecimal.valueOf(step);
		return value.divide(s, 0, java.math.RoundingMode.HALF_UP).multiply(s).setScale(2);
	}

	// ------------------------------------------------------------------ used by import

	/** Saves barcodes and module extras for an imported product (called inside the import transaction). */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public long saveImported(ProductInput in, Long existingId) {
		if (existingId == null) {
			return create(in).id();
		}
		return update(existingId, in).id();
	}

	// ------------------------------------------------------------------ helpers

	private void saveExtras(long id, ProductInput in) {
		long shopId = TenantContext.requireShopId();
		if (in.packUnits() != null) {
			features.require(ModuleKey.PACK_UNITS);
			jdbc.update("delete from product_unit where product_id = ?", id);
			Set<Long> seen = new java.util.HashSet<>();
			for (PackUnitInput u : in.packUnits()) {
				if (u.unitId() == null) {
					throw ApiException.field("packUnits", "required", "Choose a unit for each pack size");
				}
				requireUnit(u.unitId());
				if (!seen.add(u.unitId())) {
					throw ApiException.field("packUnits", "duplicate", "Each pack unit can be used once");
				}
				BigDecimal factor = Amounts.parseQty(u.factor(), "packUnits.factor");
				if (factor == null || factor.signum() <= 0) {
					throw ApiException.field("packUnits", "invalid", "Enter how many base units are in each pack");
				}
				String barcode = u.barcode() == null || u.barcode().isBlank() ? null : barcode(u.barcode());
				if (barcode != null) {
					barcodeOwner(barcode).ifPresent(owner -> {
						throw ApiException.conflict("duplicate_barcode", "Barcode " + barcode + " is already used");
					});
				}
				jdbc.update("""
						insert into product_unit (shop_id, product_id, unit_id, factor, price, barcode)
						values (?, ?, ?, ?, ?, ?)""", shopId, id, u.unitId(), factor,
						Amounts.parseMoney(u.price(), "packUnits.price"), barcode);
			}
		}
		if (in.pharma() != null) {
			features.require(ModuleKey.FORMULA);
			PharmaFields f = in.pharma();
			String schedule = f.schedule() == null || f.schedule().isBlank() ? "OTC" : f.schedule().trim().toUpperCase();
			if (!Set.of("OTC", "RX", "CONTROLLED").contains(schedule)) {
				throw ApiException.field("pharma.schedule", "invalid", "Choose OTC, RX or CONTROLLED");
			}
			jdbc.update("""
					insert into product_pharma (shop_id, product_id, formula, strength, dosage_form, company, pack_size,
					                            schedule, rack, registration_no)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					on conflict (shop_id, product_id) do update set formula = excluded.formula,
					  strength = excluded.strength, dosage_form = excluded.dosage_form, company = excluded.company,
					  pack_size = excluded.pack_size, schedule = excluded.schedule, rack = excluded.rack,
					  registration_no = excluded.registration_no""", shopId, id, cut(f.formula(), 200),
					cut(f.strength(), 60), cut(f.dosageForm(), 40), cut(f.company(), 120), cut(f.packSize(), 40), schedule,
					cut(f.rack(), 30), cut(f.registrationNo(), 40));
		}
		if (in.part() != null) {
			features.require(ModuleKey.PART_NUMBERS);
			List<String> alternates = in.part().alternateNumbers() == null ? List.of()
					: in.part().alternateNumbers().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
			jdbc.update("""
					insert into product_part (shop_id, product_id, oem_number, alternate_numbers) values (?, ?, ?, ?)
					on conflict (shop_id, product_id) do update set oem_number = excluded.oem_number,
					  alternate_numbers = excluded.alternate_numbers""", shopId, id, cut(in.part().oemNumber(), 60),
					alternates.toArray(new String[0]));
		}
		if (in.book() != null) {
			features.require(ModuleKey.BUNDLES);
			BookFields b = in.book();
			jdbc.update("""
					insert into product_book (shop_id, product_id, isbn, author, publisher, edition, class_name)
					values (?, ?, ?, ?, ?, ?, ?)
					on conflict (shop_id, product_id) do update set isbn = excluded.isbn, author = excluded.author,
					  publisher = excluded.publisher, edition = excluded.edition, class_name = excluded.class_name""",
					shopId, id, cut(b.isbn(), 20), cut(b.author(), 200), cut(b.publisher(), 200), cut(b.edition(), 40),
					cut(b.className(), 40));
		}
	}

	private void replaceBarcodes(long id, List<String> codes) {
		Set<String> clean = new LinkedHashSet<>();
		for (String raw : codes) {
			if (raw != null && !raw.isBlank()) {
				clean.add(barcode(raw));
			}
		}
		for (String code : clean) {
			Optional<Long> owner = barcodeOwner(code);
			if (owner.isPresent() && owner.get() != id) {
				throw ApiException.conflict("duplicate_barcode", "Barcode " + code + " is already used by another product");
			}
		}
		jdbc.update("delete from product_barcode where product_id = ?", id);
		long shopId = TenantContext.requireShopId();
		for (String code : clean) {
			jdbc.update("insert into product_barcode (shop_id, product_id, barcode) values (?, ?, ?)", shopId, id, code);
		}
	}

	/** Which product uses this barcode (as a product barcode or a pack-unit barcode). */
	Optional<Long> barcodeOwner(String code) {
		return jdbc.queryForList("""
				select product_id from product_barcode where barcode = ?
				union all select product_id from product_unit where barcode = ? limit 1""", Long.class, code, code)
			.stream()
			.findFirst();
	}

	private void recordPriceChange(long id, BigDecimal oldPrice, BigDecimal newPrice, BigDecimal oldCost,
			BigDecimal newCost, String reason) {
		jdbc.update("""
				insert into price_change (shop_id, product_id, old_price, new_price, old_cost, new_cost, reason,
				                          actor_type, actor_id, actor_name)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), id, oldPrice, newPrice, oldCost,
				newCost, cut(reason, 200), CurrentActor.type().name(), CurrentActor.id(), CurrentActor.name());
	}

	private String nextSku() {
		String sku;
		do {
			sku = NumberSequences.format("P", sequences.next("product_sku"), 5);
		}
		while (products.findBySkuIgnoreCase(sku).isPresent());
		return sku;
	}

	private void ensureSkuFree(String sku, long exceptId) {
		products.findBySkuIgnoreCase(sku).filter(p -> p.getId() != exceptId).ifPresent(p -> {
			throw ApiException.conflict("duplicate_sku", "SKU " + sku + " is already used by " + p.getName());
		});
	}

	private Product.Kind kind(String value) {
		Product.Kind kind;
		try {
			kind = value == null || value.isBlank() ? Product.Kind.STANDARD
					: Product.Kind.valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw ApiException.field("kind", "invalid", "Unknown product kind");
		}
		switch (kind) {
			case SERIAL -> features.require(ModuleKey.SERIALS);
			case BATCH -> features.require(ModuleKey.BATCHES);
			case VARIANT_PARENT -> features.require(ModuleKey.VARIANTS);
			case BUNDLE -> features.require(ModuleKey.BUNDLES);
			case RECIPE -> features.require(ModuleKey.RECIPES);
			default -> {
			}
		}
		return kind;
	}

	/**
	 * The item code the weighing scale prints in its barcodes (module scale_barcodes): 1–5 digits, unique; null leaves
	 * it, empty removes it.
	 */
	private void scaleCode(long id, String raw) {
		if (raw == null) {
			return;
		}
		String code = raw.trim();
		if (code.isEmpty()) {
			jdbc.update("update product set scale_code = null, updated_at = now() where id = ?", id);
			return;
		}
		features.require(ModuleKey.SCALE_BARCODES);
		if (!code.matches("[0-9]{1,5}")) {
			throw ApiException.field("scaleCode", "invalid", "The scale code is 1 to 5 digits");
		}
		code = String.format("%05d", Integer.parseInt(code));
		try {
			jdbc.update("update product set scale_code = ?, updated_at = now() where id = ?", code, id);
		}
		catch (org.springframework.dao.DuplicateKeyException e) {
			throw ApiException.field("scaleCode", "duplicate", "Another product already has scale code " + code);
		}
	}

	private boolean weighed(Boolean value) {
		if (Boolean.TRUE.equals(value)) {
			features.require(ModuleKey.WEIGHED);
			return true;
		}
		return false;
	}

	private void checkPrices(Product p) {
		if (p.getMinPrice() != null && p.getMinPrice().compareTo(p.getPrice()) > 0) {
			throw ApiException.field("minPrice", "above_price", "The minimum price cannot be more than the sale price");
		}
	}

	private long requireUnit(long unitId) {
		Integer found = jdbc.queryForObject("select count(*) from unit where id = ?", Integer.class, unitId);
		if (found == null || found == 0) {
			throw ApiException.field("unitId", "not_found", "Unit not found");
		}
		return unitId;
	}

	private Long category(Long id) {
		if (id == null) {
			return null;
		}
		Integer found = jdbc.queryForObject("select count(*) from category where id = ?", Integer.class, id);
		if (found == null || found == 0) {
			throw ApiException.field("categoryId", "not_found", "Category not found");
		}
		return id;
	}

	private Long brand(Long id) {
		if (id == null) {
			return null;
		}
		Integer found = jdbc.queryForObject("select count(*) from brand where id = ?", Integer.class, id);
		if (found == null || found == 0) {
			throw ApiException.field("brandId", "not_found", "Brand not found");
		}
		return id;
	}

	private Long image(Long id) {
		if (id == null) {
			return null;
		}
		Integer found = jdbc.queryForObject("select count(*) from stored_file where id = ? and purpose = 'PRODUCT_IMAGE'",
				Integer.class, id);
		if (found == null || found == 0) {
			throw ApiException.field("imageFileId", "not_found", "Upload the image first");
		}
		return id;
	}

	private List<String> barcodes(long id) {
		return jdbc.queryForList("select barcode from product_barcode where product_id = ? order by id", String.class, id);
	}

	private List<PackUnit> packUnits(long id) {
		return jdbc.query("""
				select pu.*, u.name as unit_name from product_unit pu join unit u on u.id = pu.unit_id
				where pu.product_id = ? order by pu.factor""", (rs, i) -> new PackUnit(rs.getLong("unit_id"),
				rs.getString("unit_name"), Amounts.qtyText(rs.getBigDecimal("factor")),
				Amounts.text(rs.getBigDecimal("price")), rs.getString("barcode")), id);
	}

	private PharmaFields pharma(long id) {
		return jdbc.query("select * from product_pharma where product_id = ?", (rs, i) -> new PharmaFields(
				rs.getString("formula"), rs.getString("strength"), rs.getString("dosage_form"), rs.getString("company"),
				rs.getString("pack_size"), rs.getString("schedule"), rs.getString("rack"),
				rs.getString("registration_no")), id).stream().findFirst().orElse(null);
	}

	private PartFields part(long id) {
		return jdbc.query("select * from product_part where product_id = ?", (rs, i) -> {
			Array array = rs.getArray("alternate_numbers");
			List<String> alternates = array == null ? List.of() : List.of((String[]) array.getArray());
			return new PartFields(rs.getString("oem_number"), alternates);
		}, id).stream().findFirst().orElse(null);
	}

	private BookFields book(long id) {
		return jdbc.query("select * from product_book where product_id = ?", (rs, i) -> new BookFields(
				rs.getString("isbn"), rs.getString("author"), rs.getString("publisher"), rs.getString("edition"),
				rs.getString("class_name")), id).stream().findFirst().orElse(null);
	}

	private static Map<String, Object> snapshot(Product p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("sku", p.getSku());
		m.put("name", p.getName());
		m.put("price", Amounts.text(p.getPrice()));
		m.put("categoryId", p.getCategoryId());
		m.put("brandId", p.getBrandId());
		m.put("unitId", p.getUnitId());
		m.put("active", p.isActive());
		return m;
	}

	private static String describe(String action) {
		return switch (action) {
			case "PRODUCT_CREATED" -> "Product created";
			case "PRODUCT_UPDATED" -> "Details changed";
			case "PRODUCT_DEACTIVATED" -> "Deactivated";
			case "PRODUCT_ACTIVATED" -> "Activated";
			case "BARCODE_GENERATED" -> "Barcode generated";
			case "PRODUCT_IMPORTED" -> "Updated by import";
			default -> action;
		};
	}

	private static boolean sameMoney(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	static String sku(String value) {
		String s = value.trim();
		if (!s.matches("[A-Za-z0-9._/-]{1,40}")) {
			throw ApiException.field("sku", "invalid", "Use up to 40 letters, numbers, dots, dashes or slashes");
		}
		return s;
	}

	static String barcode(String value) {
		String s = value.trim();
		if (!BarcodeFormats.acceptable(s)) {
			throw ApiException.field("barcodes", "invalid", "Barcodes are 3 to 64 characters without spaces");
		}
		return s;
	}

	static String text(String value, String field, int max, boolean required) {
		String s = value == null ? "" : value.trim();
		if (required && s.isEmpty()) {
			throw ApiException.field(field, "required", "This field is required");
		}
		if (s.length() > max) {
			throw ApiException.field(field, "too_long", "Use at most " + max + " characters");
		}
		return s.isEmpty() ? null : s;
	}

	private static String cut(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String s = value.trim();
		return s.length() > max ? s.substring(0, max) : s;
	}

	static boolean equalsIgnoreCase(String a, String b) {
		return Objects.equals(a == null ? null : a.toLowerCase(), b == null ? null : b.toLowerCase());
	}

	/** Shop warranty in days (MOB-06), 0 = none. */
	private static int warrantyDays(Integer days) {
		if (days == null) {
			return 0;
		}
		if (days < 0 || days > 3650) {
			throw ApiException.field("warrantyDays", "out_of_range", "Warranty is 0 to 3650 days");
		}
		return days;
	}
}
