package com.selectcode.pos.catalog.app;

import com.selectcode.pos.shared.persistence.NumberSequences;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.PlanLimits;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Variants (GAR-01, OTH-01): one design with a size × colour (or shade) grid; each variant is a product of its own
 * with its own SKU, barcode and stock. Size sets (GAR-02) are reusable size lists. Bundles (OTH-02): a set sold as one
 * item that takes stock from each item in it.
 */
@Service
public class VariantService {

	/** @param prices optional price per size (e.g. XL costs more); missing sizes use the design's price */
	public record VariantGridInput(List<String> sizes, List<String> colours, List<String> shades,
			Map<String, String> prices) {
	}

	public record VariantCell(long productId, String sku, String size, String colour, String shade, String price,
			String stock, boolean active, List<String> barcodes) {
	}

	public record VariantGrid(long parentId, String design, List<String> sizes, List<String> colours,
			List<VariantCell> variants) {
	}

	public record SizeSetView(long id, String name, List<String> sizes) {
	}

	public record SizeSetInput(String name, List<String> sizes) {
	}

	public record BundleItemInput(Long productId, String qty) {
	}

	public record BundleItemView(long productId, String name, String sku, String qty, String price) {
	}

	public record BundleView(long productId, String name, String price, String itemsTotal, List<BundleItemView> items) {
	}

	private final JdbcTemplate jdbc;

	private final PlanLimits limits;

	private final AuditService audit;

	private final NumberSequences sequences;

	VariantService(JdbcTemplate jdbc, PlanLimits limits, AuditService audit, NumberSequences sequences) {
		this.sequences = sequences;
		this.jdbc = jdbc;
		this.limits = limits;
		this.audit = audit;
	}

	/**
	 * Adds the missing variants of a design for every size × colour (or shade) combination. The design becomes a
	 * VARIANT_PARENT (not sold itself); existing variants are kept.
	 */
	@Transactional
	public VariantGrid createGrid(long parentId, VariantGridInput in) {
		Map<String, Object> parent = jdbc.queryForList("select * from product where id = ? for update", parentId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Product"));
		if (jdbc.queryForObject("select count(*) from product_variant where product_id = ?", Integer.class, parentId) > 0) {
			throw ApiException.rule("is_variant", "This is a variant; add variants to its design");
		}
		List<String> sizes = clean(in.sizes());
		List<String> colours = clean(in.colours());
		List<String> shades = clean(in.shades());
		if (sizes.isEmpty() && colours.isEmpty() && shades.isEmpty()) {
			throw ApiException.field("sizes", "required", "Enter sizes, colours or shades");
		}
		Integer stock = jdbc.queryForObject("select count(*) from stock_movement where product_id = ?", Integer.class,
				parentId);
		if (!"VARIANT_PARENT".equals(parent.get("kind")) && stock != null && stock > 0) {
			throw ApiException.rule("has_stock", "This product already has stock; create a new design for its variants");
		}
		List<String> rows = sizes.isEmpty() ? java.util.Collections.singletonList(null) : sizes;
		List<String> cols = !colours.isEmpty() ? colours : shades;
		boolean shade = colours.isEmpty() && !shades.isEmpty();
		List<String> columns = cols.isEmpty() ? java.util.Collections.singletonList(null) : cols;
		long existing = jdbc.queryForObject("select count(*) from product", Long.class);
		int toCreate = 0;
		for (String size : rows) {
			for (String col : columns) {
				if (!variantExists(parentId, size, shade ? null : col, shade ? col : null)) {
					toCreate++;
				}
			}
		}
		limits.check(PlanLimits.Limit.PRODUCTS, existing, toCreate);
		jdbc.update("update product set kind = 'VARIANT_PARENT', version = version + 1 where id = ?", parentId);
		String baseSku = (String) parent.get("sku");
		for (String size : rows) {
			for (String col : columns) {
				String colour = shade ? null : col;
				String sh = shade ? col : null;
				if (variantExists(parentId, size, colour, sh)) {
					continue;
				}
				String suffix = String.join("-", java.util.stream.Stream.of(size, col).filter(java.util.Objects::nonNull)
					.map(v -> v.toUpperCase().replaceAll("[^A-Z0-9]", ""))
					.toList());
				String sku = (baseSku + "-" + suffix);
				sku = sku.length() > 40 ? sku.substring(0, 40) : sku;
				String label = String.join(" / ", java.util.stream.Stream.of(size, col).filter(java.util.Objects::nonNull).toList());
				BigDecimal price = in.prices() != null && size != null && in.prices().get(size) != null
						? Amounts.requireMoney(in.prices().get(size), "prices." + size) : (BigDecimal) parent.get("price");
				long id;
				try {
					id = jdbc.queryForObject("""
							insert into product (shop_id, sku, name, urdu_name, category_id, brand_id, unit_id, kind, cost_avg,
							                     price, wholesale_price, min_price, tax_rate, reorder_level, reorder_qty,
							                     image_file_id, weighed, warranty_days, description, supplier_id)
							select shop_id, ?, name || ' - ' || ?, urdu_name, category_id, brand_id, unit_id, 'STANDARD', cost_avg,
							       ?, wholesale_price, min_price, tax_rate, reorder_level, reorder_qty, image_file_id, weighed,
							       warranty_days, description, supplier_id
							from product where id = ? returning id""", Long.class, sku, label, price, parentId);
				}
				catch (DuplicateKeyException e) {
					throw ApiException.conflict("duplicate_sku", "SKU " + sku + " is already used");
				}
				jdbc.update("""
						insert into product_variant (shop_id, product_id, parent_id, size, colour, shade) values (?, ?, ?, ?, ?, ?)""",
						TenantContext.requireShopId(), id, parentId, size, colour, sh);
				// Each size/colour scans on its own at the till (GAR-01): an in-store EAN-13.
				String code;
				do {
					code = BarcodeFormats.inStoreEan13(sequences.next("in_store_barcode"));
				}
				while (!jdbc.queryForList("select 1 from product_barcode where barcode = ?", code).isEmpty());
				jdbc.update("insert into product_barcode (shop_id, product_id, barcode) values (?, ?, ?)",
						TenantContext.requireShopId(), id, code);
			}
		}
		audit.record("VARIANTS_CREATED", "product", parentId, null, Map.of("sizes", sizes, "colours", cols));
		return grid(parentId);
	}

	@Transactional(readOnly = true)
	public VariantGrid grid(long parentId) {
		Map<String, Object> parent = jdbc.queryForList("select id, name from product where id = ?", parentId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Product"));
		List<VariantCell> cells = jdbc.query("""
				select v.*, p.sku, p.price, p.active,
				       (select coalesce(sum(qty), 0) from product_stock s where s.product_id = p.id) as stock,
				       (select array_agg(barcode order by id) from product_barcode b where b.product_id = p.id) as barcodes
				from product_variant v join product p on p.id = v.product_id where v.parent_id = ? order by v.size, v.colour, v.shade""",
				(rs, i) -> {
					java.sql.Array arr = rs.getArray("barcodes");
					return new VariantCell(rs.getLong("product_id"), rs.getString("sku"), rs.getString("size"),
							rs.getString("colour"), rs.getString("shade"), Amounts.text(rs.getBigDecimal("price")),
							Amounts.qtyText(rs.getBigDecimal("stock")), rs.getBoolean("active"),
							arr == null ? List.of() : List.of((String[]) arr.getArray()));
				}, parentId);
		List<String> sizes = new ArrayList<>(new LinkedHashSet<>(cells.stream().map(VariantCell::size)
			.filter(java.util.Objects::nonNull).toList()));
		List<String> colours = new ArrayList<>(new LinkedHashSet<>(cells.stream()
			.map(c -> c.colour() != null ? c.colour() : c.shade())
			.filter(java.util.Objects::nonNull)
			.toList()));
		return new VariantGrid(parentId, (String) parent.get("name"), sizes, colours, cells);
	}

	// ------------------------------------------------------------------ size sets

	@Transactional(readOnly = true)
	public List<SizeSetView> sizeSets() {
		return jdbc.query("select * from size_set order by name", (rs, i) -> new SizeSetView(rs.getLong("id"),
				rs.getString("name"), List.of((String[]) rs.getArray("sizes").getArray())));
	}

	@Transactional
	public SizeSetView createSizeSet(SizeSetInput in) {
		String name = in.name() == null ? "" : in.name().trim();
		List<String> sizes = clean(in.sizes());
		if (name.isEmpty() || name.length() > 60) {
			throw ApiException.field("name", "required", "Enter a name");
		}
		if (sizes.isEmpty()) {
			throw ApiException.field("sizes", "required", "Enter the sizes");
		}
		try {
			long id = jdbc.queryForObject("insert into size_set (shop_id, name, sizes) values (?, ?, ?) returning id",
					Long.class, TenantContext.requireShopId(), name, sizes.toArray(String[]::new));
			return new SizeSetView(id, name, sizes);
		}
		catch (DuplicateKeyException e) {
			throw ApiException.conflict("duplicate_name", "A size set with this name exists");
		}
	}

	@Transactional
	public void deleteSizeSet(long id) {
		if (jdbc.update("delete from size_set where id = ?", id) == 0) {
			throw ApiException.notFound("Size set");
		}
	}

	// ------------------------------------------------------------------ bundles

	/** Sets the items of a bundle (kind BUNDLE), e.g. a class's book set. */
	@Transactional
	public BundleView setBundle(long bundleId, List<BundleItemInput> items) {
		Map<String, Object> bundle = jdbc.queryForList("select * from product where id = ?", bundleId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Product"));
		if (!"BUNDLE".equals(bundle.get("kind"))) {
			throw ApiException.rule("not_bundle", "Make the product a bundle (set) first");
		}
		if (items == null || items.isEmpty()) {
			throw ApiException.field("items", "required", "Add the items in the set");
		}
		jdbc.update("delete from bundle_item where bundle_product_id = ?", bundleId);
		for (int i = 0; i < items.size(); i++) {
			BundleItemInput item = items.get(i);
			if (item.productId() == null) {
				throw ApiException.field("items[" + i + "].productId", "required", "Choose an item");
			}
			Map<String, Object> p = jdbc.queryForList("select kind from product where id = ?", item.productId())
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("Product"));
			if (List.of("BUNDLE", "VARIANT_PARENT", "SERIAL").contains(p.get("kind"))) {
				throw ApiException.rule("bundle_item_kind", "Sets can hold normal or batch items only");
			}
			BigDecimal qty = Amounts.parseQty(item.qty() == null ? "1" : item.qty(), "items[" + i + "].qty");
			try {
				jdbc.update("insert into bundle_item (shop_id, bundle_product_id, item_product_id, qty) values (?, ?, ?, ?)",
						TenantContext.requireShopId(), bundleId, item.productId(), qty);
			}
			catch (org.springframework.dao.DataIntegrityViolationException e) {
				throw ApiException.rule("bundle_item_invalid", "An item is in the set twice or is the set itself");
			}
		}
		audit.record("BUNDLE_SET", "product", bundleId, null, Map.of("items", items.size()));
		return bundle(bundleId);
	}

	@Transactional(readOnly = true)
	public BundleView bundle(long bundleId) {
		Map<String, Object> bundle = jdbc.queryForList("select * from product where id = ?", bundleId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Product"));
		List<BundleItemView> items = jdbc.query("""
				select b.item_product_id, p.name, p.sku, b.qty, p.price from bundle_item b join product p on p.id = b.item_product_id
				where b.bundle_product_id = ? order by p.name""",
				(rs, i) -> new BundleItemView(rs.getLong("item_product_id"), rs.getString("name"), rs.getString("sku"),
						Amounts.qtyText(rs.getBigDecimal("qty")), Amounts.text(rs.getBigDecimal("price"))),
				bundleId);
		BigDecimal sum = items.stream().map(i -> new BigDecimal(i.price()).multiply(new BigDecimal(i.qty())))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new BundleView(bundleId, (String) bundle.get("name"), Amounts.text((BigDecimal) bundle.get("price")),
				Amounts.text(sum), items);
	}

	private boolean variantExists(long parentId, String size, String colour, String shade) {
		Integer n = jdbc.queryForObject("""
				select count(*) from product_variant where parent_id = ? and coalesce(size, '') = ? and coalesce(colour, '') = ?
				and coalesce(shade, '') = ?""", Integer.class, parentId, size == null ? "" : size,
				colour == null ? "" : colour, shade == null ? "" : shade);
		return n != null && n > 0;
	}

	private static List<String> clean(List<String> values) {
		if (values == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>(new LinkedHashSet<>(values.stream()
			.filter(v -> v != null && !v.isBlank())
			.map(String::trim)
			.map(v -> v.length() > 30 ? v.substring(0, 30) : v)
			.toList()));
		if (out.size() > 40) {
			throw ApiException.field("sizes", "too_many", "At most 40 sizes or colours");
		}
		return out;
	}

	@SuppressWarnings("unused")
	private static boolean costVisible() {
		return CurrentActor.hasPermission(Permissions.COST_VIEW);
	}
}
