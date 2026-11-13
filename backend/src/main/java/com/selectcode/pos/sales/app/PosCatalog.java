package com.selectcode.pos.sales.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shop.app.BranchAccess;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The till's product list (POS-09, NFR-10): every sellable product of a branch in one compact response, loaded once
 * and kept in the browser so searching and scanning never wait for the network. An ETag lets the till refresh cheaply
 * (304 when nothing changed).
 */
@Service
public class PosCatalog {

	public record PosUnit(long unitId, String name, String factor, String price, String barcode) {
	}

	/** @param stock at the branch in base units (null for services and bundles) */
	/**
	 * @param scaleCode item code in weighing-scale barcodes (module scale_barcodes)
	 * @param schedule OTC, RX or CONTROLLED (pharmacy; null for other items)
	 */
	public record PosItem(long id, String sku, String name, String urduName, String kind, String price,
			String minPrice, String taxRate, boolean decimals, String unitName, String stock, List<String> barcodes,
			List<PosUnit> units, String formula, List<String> partNumbers, Long categoryId, String scaleCode,
			String schedule) {
	}

	/** An offer as the till needs it to work out the same discounts as the server (PromotionEngine). */
	public record PosOffer(long id, String name, String kind, String value, String buyQty, String getQty,
			String minAmount, String scope, List<Long> categoryIds, List<Long> productIds, List<Integer> days,
			String fromTime, String toTime, boolean coupon, String summary) {
	}

	/** Till settings that depend on switched-on modules. */
	public record PosSettings(String scaleValueKind, boolean prescriptionRequired, boolean loyalty,
			String loyaltyPointValue, int loyaltyMinRedeem, String loyaltySpendPerPoint) {
	}

	public record PosCatalogView(long branchId, String version, int count, List<PosItem> items, List<PosOffer> offers,
			PosSettings settings) {
	}

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	private final PromotionService promotions;

	private final com.selectcode.pos.shared.features.ShopFeatures features;

	PosCatalog(JdbcTemplate jdbc, BranchAccess branches, PromotionService promotions,
			com.selectcode.pos.shared.features.ShopFeatures features) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.promotions = promotions;
		this.features = features;
	}

	/** A short fingerprint of everything the list depends on (products, prices, units and stock at the branch). */
	@Transactional(readOnly = true)
	public String version(Long branchIdIn) {
		long branchId = branchIdIn == null ? branches.defaultBranchId() : branchIdIn;
		branches.require(branchId);
		Map<String, Object> v = jdbc.queryForMap("""
				select (select count(*) from product) as n,
				       (select coalesce(max(updated_at), 'epoch'::timestamptz) from product) as p,
				       (select coalesce(max(version), 0) + coalesce(sum(version), 0) from product) as pv,
				       (select coalesce(max(updated_at), 'epoch'::timestamptz) from product_stock where branch_id = ?) as s,
				       (select count(*) from product_barcode) as b, (select count(*) from product_unit) as u,
				       (select coalesce(max(updated_at), 'epoch'::timestamptz) || '/' || count(*) || '/' || coalesce(sum(uses), 0)
				        from promotion) as o,
				       (select updated_at from shop_settings where shop_id = ?) as st,
				       current_date as d""", branchId, com.selectcode.pos.shared.tenant.TenantContext.requireShopId());
		return hash(branchId + "|" + v + "|" + features.enabled());
	}

	@Transactional(readOnly = true)
	public PosCatalogView catalog(Long branchIdIn) {
		long branchId = branchIdIn == null ? branches.defaultBranchId() : branchIdIn;
		branches.require(branchId);
		Map<Long, List<String>> barcodes = new LinkedHashMap<>();
		jdbc.query("select product_id, barcode from product_barcode order by id",
				rs -> {
					barcodes.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>()).add(rs.getString("barcode"));
				});
		Map<Long, List<PosUnit>> units = new LinkedHashMap<>();
		jdbc.query("""
				select pu.product_id, pu.unit_id, u.name, pu.factor, coalesce(pu.price, p.price * pu.factor) as price, pu.barcode
				from product_unit pu join unit u on u.id = pu.unit_id join product p on p.id = pu.product_id
				order by pu.factor""", rs -> {
			units.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
				.add(new PosUnit(rs.getLong("unit_id"), rs.getString("name"), Amounts.qtyText(rs.getBigDecimal("factor")),
						Amounts.text(rs.getBigDecimal("price")), rs.getString("barcode")));
		});
		List<PosItem> items = jdbc.query("""
				select p.id, p.sku, p.name, p.urdu_name, p.kind, p.price, p.min_price, p.tax_rate,
				       (u.decimals_allowed or p.weighed) as decimals, u.name as unit_name,
				       (select sum(s.qty) from product_stock s where s.product_id = p.id and s.branch_id = ?) as stock,
				       ph.formula, ph.schedule, pt.oem_number, pt.alternate_numbers, p.category_id, p.scale_code
				from product p join unit u on u.id = p.unit_id
				left join product_pharma ph on ph.product_id = p.id left join product_part pt on pt.product_id = p.id
				where p.active and p.kind <> 'VARIANT_PARENT' order by p.name""", (rs, i) -> {
			long id = rs.getLong("id");
			String kind = rs.getString("kind");
			BigDecimal stock = rs.getBigDecimal("stock");
			List<String> parts = new ArrayList<>();
			if (rs.getString("oem_number") != null) {
				parts.add(rs.getString("oem_number"));
			}
			java.sql.Array alt = rs.getArray("alternate_numbers");
			if (alt != null) {
				parts.addAll(List.of((String[]) alt.getArray()));
			}
			return new PosItem(id, rs.getString("sku"), rs.getString("name"), rs.getString("urdu_name"), kind,
					Amounts.text(rs.getBigDecimal("price")), Amounts.text(rs.getBigDecimal("min_price")),
					Amounts.percentText(rs.getBigDecimal("tax_rate")), rs.getBoolean("decimals"),
					rs.getString("unit_name"),
					"SERVICE".equals(kind) || "BUNDLE".equals(kind) ? null
							: Amounts.qtyText(stock == null ? BigDecimal.ZERO : stock),
					barcodes.getOrDefault(id, List.of()), units.getOrDefault(id, List.of()), rs.getString("formula"),
					parts, (Long) rs.getObject("category_id"), rs.getString("scale_code"), rs.getString("schedule"));
		}, branchId);
		List<PosOffer> offers = promotions.today().stream()
			.map(o -> new PosOffer(o.id(), o.name(), o.kind(), Amounts.text(o.value()), Amounts.qtyText(o.buyQty()),
					Amounts.qtyText(o.getQty()), Amounts.text(o.minAmount()), o.scope(), List.copyOf(o.categoryIds()),
					List.copyOf(o.productIds()), o.days().stream().sorted().toList(),
					o.fromTime() == null ? null : o.fromTime().toString(), o.toTime() == null ? null : o.toTime().toString(),
					o.couponCode() != null, PromotionService.summary(o)))
			.toList();
		Map<String, Object> s = jdbc.queryForMap("""
				select scale_value_kind, prescription_required, loyalty_point_value, loyalty_min_redeem,
				       loyalty_spend_per_point from shop_settings where shop_id = ?""",
				com.selectcode.pos.shared.tenant.TenantContext.requireShopId());
		PosSettings settings = new PosSettings((String) s.get("scale_value_kind"),
				Boolean.TRUE.equals(s.get("prescription_required"))
						&& features.isOn(com.selectcode.pos.shared.features.ModuleKey.PRESCRIPTIONS),
				features.isOn(com.selectcode.pos.shared.features.ModuleKey.LOYALTY),
				Amounts.text((java.math.BigDecimal) s.get("loyalty_point_value")),
				((Number) s.get("loyalty_min_redeem")).intValue(),
				Amounts.text((java.math.BigDecimal) s.get("loyalty_spend_per_point")));
		return new PosCatalogView(branchId, version(branchId), items.size(), items, offers, settings);
	}

	private static String hash(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes()))
				.substring(0, 16);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
