package com.selectcode.pos.catalog.app;

import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read access to products for the trading modules (stock, purchasing, sales): what a product is, how it is counted
 * and which units it can be bought and sold in (SUP-04, MED-06, HRD-01).
 */
@Service
public class ProductCatalog {

	/**
	 * @param kind STANDARD, SERIAL, BATCH, VARIANT_PARENT, BUNDLE or SERVICE
	 * @param decimals quantities may have decimals (weighed items, metres, kg)
	 */
	public record ProductRef(long id, String sku, String name, String kind, long unitId, String unitName,
			boolean decimals, BigDecimal price, BigDecimal costAvg, BigDecimal minPrice, BigDecimal wholesalePrice,
			BigDecimal taxRate, boolean active, int warrantyDays, BigDecimal reorderLevel, Long supplierId) {

		public boolean tracksStock() {
			return !"SERVICE".equals(kind) && !"BUNDLE".equals(kind) && !"VARIANT_PARENT".equals(kind)
					&& !"RECIPE".equals(kind);
		}

		public boolean serial() {
			return "SERIAL".equals(kind);
		}

		public boolean batch() {
			return "BATCH".equals(kind);
		}
	}

	/**
	 * A unit a product is handled in: its base unit (factor 1) or a pack unit with its own price and barcode.
	 * @param price price for one of this unit (pack price, or base price × factor when the pack has none)
	 */
	public record UnitRef(long unitId, String name, BigDecimal factor, BigDecimal price, boolean decimals) {
	}

	public record BundlePart(long productId, BigDecimal qty) {
	}

	private static final String SELECT = """
			select p.*, u.name as unit_name, (u.decimals_allowed or p.weighed) as decimals
			from product p join unit u on u.id = p.unit_id
			""";

	private final JdbcTemplate jdbc;

	ProductCatalog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The product, or 404. */
	public ProductRef require(long productId) {
		return jdbc.query(SELECT + "where p.id = ?", (rs, i) -> ref(rs), productId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Product"));
	}

	/** Products by id (missing ids are absent from the map). */
	public Map<Long, ProductRef> find(Collection<Long> ids) {
		Map<Long, ProductRef> map = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return map;
		}
		jdbc.query(SELECT + "where p.id = any(?)", (rs, i) -> ref(rs), (Object) ids.toArray(Long[]::new))
			.forEach(p -> map.put(p.id(), p));
		return map;
	}

	/**
	 * The unit to use for a line: the base unit when {@code unitId} is null or the base unit, else one of the product's
	 * pack units; 422 {@code unit_not_allowed} otherwise.
	 */
	public UnitRef unit(ProductRef product, Long unitId) {
		if (unitId == null || unitId == product.unitId()) {
			return new UnitRef(product.unitId(), product.unitName(), BigDecimal.ONE, product.price(), product.decimals());
		}
		return jdbc.query("""
				select pu.unit_id, u.name, pu.factor, pu.price, u.decimals_allowed from product_unit pu
				join unit u on u.id = pu.unit_id where pu.product_id = ? and pu.unit_id = ?""",
				(rs, i) -> new UnitRef(rs.getLong("unit_id"), rs.getString("name"), rs.getBigDecimal("factor"),
						rs.getBigDecimal("price") != null ? rs.getBigDecimal("price")
								: product.price().multiply(rs.getBigDecimal("factor")).setScale(2,
										java.math.RoundingMode.HALF_UP),
						rs.getBoolean("decimals_allowed")),
				product.id(), unitId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.rule("unit_not_allowed",
					product.name() + " is not sold or bought in that unit"));
	}

	/** Units of a product: base first, then pack units. */
	public List<UnitRef> units(ProductRef product) {
		List<UnitRef> units = new ArrayList<>();
		units.add(unit(product, null));
		units.addAll(jdbc.query("""
				select pu.unit_id from product_unit pu where pu.product_id = ? order by pu.factor""",
				(rs, i) -> unit(product, rs.getLong("unit_id")), product.id()));
		return units;
	}

	/** Items in a bundle (OTH-02 book sets). */
	public List<BundlePart> bundle(long bundleProductId) {
		return jdbc.query("select item_product_id, qty from bundle_item where bundle_product_id = ? order by item_product_id",
				(rs, i) -> new BundlePart(rs.getLong("item_product_id"), rs.getBigDecimal("qty")), bundleProductId);
	}

	/** Checks a quantity fits the unit (whole numbers unless the unit or product allows decimals). */
	public static void checkQty(ProductRef product, UnitRef unit, BigDecimal qty, String field) {
		if (qty == null || qty.signum() <= 0) {
			throw ApiException.field(field, "invalid", "Enter a quantity above zero");
		}
		boolean decimals = unit.factor().compareTo(BigDecimal.ONE) == 0 ? product.decimals() : unit.decimals();
		if (!decimals && qty.stripTrailingZeros().scale() > 0) {
			throw ApiException.field(field, "whole_number", product.name() + " is sold in whole " + unit.name() + "s");
		}
	}

	private static ProductRef ref(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new ProductRef(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("kind"),
				rs.getLong("unit_id"), rs.getString("unit_name"), rs.getBoolean("decimals"), rs.getBigDecimal("price"),
				rs.getBigDecimal("cost_avg"), rs.getBigDecimal("min_price"), rs.getBigDecimal("wholesale_price"),
				rs.getBigDecimal("tax_rate"), rs.getBoolean("active"), rs.getInt("warranty_days"),
				rs.getBigDecimal("reorder_level"), (Long) rs.getObject("supplier_id"));
	}
}
