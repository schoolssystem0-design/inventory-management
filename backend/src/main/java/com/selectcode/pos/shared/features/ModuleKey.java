package com.selectcode.pos.shared.features;

import java.util.Arrays;
import java.util.Optional;

/**
 * Switchable modules (docs/requirements/SHOP-TYPES-AND-MODULES.md §2). Core modules are always on and not listed here. The key is
 * what is stored in {@code shop_module} and returned to the app.
 */
public enum ModuleKey {

	SERIALS("serials", "IMEI / serial numbers", 1, true),
	USED_BUYBACK("used_buyback", "Buy used items from customers", 1, true),
	WARRANTY("warranty", "Warranty cards and claims", 1, true),
	REPAIRS("repairs", "Repair job cards", 2, true),
	INSTALLMENTS("installments", "Installments", 2, true),
	VARIANTS("variants", "Sizes, colours and shades", 1, true),
	BATCHES("batches", "Batches and expiry dates", 1, false),
	PACK_UNITS("pack_units", "Units and pack sizes", 1, true),
	FORMULA("formula", "Formula (generic) search", 1, true),
	PRESCRIPTIONS("prescriptions", "Prescriptions and controlled medicines", 2, true),
	WEIGHED("weighed", "Weighed items (kg)", 1, true),
	SCALE_BARCODES("scale_barcodes", "Weighing-scale barcodes", 2, true),
	PROMOTIONS("promotions", "Offers and coupons", 2, true),
	LOYALTY("loyalty", "Loyalty points", 2, true),
	GIFT_VOUCHERS("gift_vouchers", "Gift vouchers", 2, true),
	MULTI_TILL("multi_till", "Several tills and shift closing", 1, true),
	QUOTATIONS("quotations", "Quotations and sales orders", 1, true),
	DELIVERY("delivery", "Delivery and challans", 2, true),
	BUNDLES("bundles", "Bundles and sets", 1, true),
	PART_NUMBERS("part_numbers", "Part numbers", 1, true),
	VEHICLE_FIT("vehicle_fit", "Vehicle fitment", 2, true),
	LAYAWAY("layaway", "Advance booking", 2, true),
	SPECIAL_ORDERS("special_orders", "Special orders", 2, true),
	CONSIGNMENT("consignment", "Consignment stock", 3, true),
	RECIPES("recipes", "Recipes and production", 2, true),
	DISTRIBUTION("distribution", "Routes, order booking and recovery", 2, false),
	ACCOUNTING("accounting", "Accounting", 2, false),
	STAFF("staff", "Commission, attendance and salaries", 2, true),
	ONLINE_STORE("online_store", "Online store", 3, false),
	FBR("fbr", "FBR POS integration", 2, false),
	SMS("sms", "SMS receipts and reminders", 1, true),
	WHATSAPP("whatsapp", "WhatsApp", 2, true),
	PRICE_LEVELS("price_levels", "Wholesale and special prices", 2, true),
	JEWELLERY("jewellery", "Jewellery weight and gold rate", 3, false),
	OPTICAL("optical", "Eye prescriptions and lens orders", 3, false);

	private final String key;

	private final String label;

	private final int phase;

	private final boolean ownerCanToggle;

	ModuleKey(String key, String label, int phase, boolean ownerCanToggle) {
		this.key = key;
		this.label = label;
		this.phase = phase;
		this.ownerCanToggle = ownerCanToggle;
	}

	public String key() {
		return key;
	}

	public String label() {
		return label;
	}

	/** Release phase in which the module's features are built. */
	public int phase() {
		return phase;
	}

	public boolean ownerCanToggle() {
		return ownerCanToggle;
	}

	public static Optional<ModuleKey> of(String key) {
		return Arrays.stream(values()).filter(m -> m.key.equals(key)).findFirst();
	}
}
