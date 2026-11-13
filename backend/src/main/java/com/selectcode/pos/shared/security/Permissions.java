package com.selectcode.pos.shared.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The shop permission catalogue (docs/architecture/ARCHITECTURE.md §5). Codes are {@code area.action}. Constants are compile-time
 * strings so they can be used in {@code @PreAuthorize}. Permissions for later stages are listed now so system roles
 * are complete from the first shop onwards.
 */
public final class Permissions {

	// ---- Shop setup
	public static final String SETTINGS_VIEW = "settings.view";

	public static final String SETTINGS_EDIT = "settings.edit";

	public static final String BRANCHES_MANAGE = "branches.manage";

	// ---- Users and security
	public static final String USERS_VIEW = "users.view";

	public static final String USERS_MANAGE = "users.manage";

	public static final String ROLES_MANAGE = "roles.manage";

	public static final String AUDIT_VIEW = "audit.view";

	public static final String LOGIN_HISTORY_VIEW = "login_history.view";

	// ---- Catalogue
	public static final String CATALOG_VIEW = "catalog.view";

	public static final String CATALOG_MANAGE = "catalog.manage";

	public static final String CATALOG_PRICES = "catalog.prices";

	public static final String CATALOG_IMPORT = "catalog.import";

	public static final String CATALOG_EXPORT = "catalog.export";

	public static final String LABELS_PRINT = "labels.print";

	/** See cost price, average cost, profit and supplier prices anywhere (screens, prints, exports). */
	public static final String COST_VIEW = "cost.view";

	// ---- Selling (Stage 3)
	public static final String POS_SELL = "pos.sell";

	public static final String POS_DISCOUNT = "pos.discount";

	public static final String POS_PRICE_OVERRIDE = "pos.price_override";

	public static final String SALES_VIEW = "sales.view";

	public static final String SALES_RETURN = "sales.return";

	public static final String SALES_CANCEL = "sales.cancel";

	public static final String QUOTATIONS_MANAGE = "quotations.manage";

	public static final String TILL_MANAGE = "till.manage";

	// ---- Customers (Stage 3)
	public static final String CUSTOMERS_VIEW = "customers.view";

	public static final String CUSTOMERS_MANAGE = "customers.manage";

	public static final String CUSTOMERS_PAYMENTS = "customers.payments";

	/** See full CNIC and personal details of customers and sellers. */
	public static final String CUSTOMERS_PII = "customers.pii";

	// ---- Purchasing (Stage 3)
	public static final String PURCHASING_VIEW = "purchasing.view";

	public static final String PURCHASING_MANAGE = "purchasing.manage";

	public static final String SUPPLIERS_PAYMENTS = "suppliers.payments";

	// ---- Stock (Stage 3)
	public static final String STOCK_VIEW = "stock.view";

	public static final String STOCK_ADJUST = "stock.adjust";

	public static final String STOCK_COUNT = "stock.count";

	public static final String STOCK_TRANSFER = "stock.transfer";

	// ---- Cash and reports (Stage 3)
	public static final String CASH_VIEW = "cash.view";

	public static final String EXPENSES_MANAGE = "expenses.manage";

	public static final String REPORTS_VIEW = "reports.view";

	public static final String DATA_EXPORT = "data.export";

	// ---- Approvals
	/** May approve over-limit actions at the till with their PIN (manager approval). */
	public static final String APPROVALS_GRANT = "approvals.grant";

	// ---- Shop-type modules (Stage 3)
	public static final String SERIALS_MANAGE = "serials.manage";

	public static final String BUYBACK_MANAGE = "buyback.manage";

	public static final String WARRANTY_MANAGE = "warranty.manage";

	public static final String REPAIRS_MANAGE = "repairs.manage";

	public static final String PRESCRIPTIONS_APPROVE = "prescriptions.approve";

	// ---- Phase 2 modules (Stage 4b)
	public static final String PROMOTIONS_MANAGE = "promotions.manage";

	public static final String LOYALTY_MANAGE = "loyalty.manage";

	public static final String INSTALLMENTS_MANAGE = "installments.manage";

	public static final String DELIVERY_MANAGE = "delivery.manage";

	// ---- Fast food (Stage 4c)
	public static final String KITCHEN_VIEW = "kitchen.view";

	public static final String SUBSCRIPTION_VIEW = "subscription.view";

	public static final String SUPPORT_APPROVE = "support.approve";

	/** Group → (code → label), in the order the app shows them. */
	public static final Map<String, Map<String, String>> CATALOGUE;

	public static final Set<String> CODES;

	static {
		Map<String, Map<String, String>> c = new LinkedHashMap<>();
		c.put("Shop setup", ordered(SETTINGS_VIEW, "View settings", SETTINGS_EDIT, "Change settings", BRANCHES_MANAGE,
				"Manage branches and tills", SUBSCRIPTION_VIEW, "View subscription", SUPPORT_APPROVE,
				"Allow SelectCode support to make changes"));
		c.put("Users", ordered(USERS_VIEW, "View users", USERS_MANAGE, "Add and change users", ROLES_MANAGE,
				"Manage roles", AUDIT_VIEW, "View activity history", LOGIN_HISTORY_VIEW, "View login history"));
		c.put("Products", ordered(CATALOG_VIEW, "View products", CATALOG_MANAGE, "Add and change products",
				CATALOG_PRICES, "Change prices", CATALOG_IMPORT, "Import products", CATALOG_EXPORT, "Export products",
				LABELS_PRINT, "Print labels", COST_VIEW, "See cost and profit"));
		c.put("Selling", ordered(POS_SELL, "Sell at the till", POS_DISCOUNT, "Give discounts", POS_PRICE_OVERRIDE,
				"Change prices at the till", SALES_VIEW, "View sales", SALES_RETURN, "Take returns", SALES_CANCEL,
				"Cancel invoices", QUOTATIONS_MANAGE, "Quotations", TILL_MANAGE, "Open and close tills"));
		c.put("Customers", ordered(CUSTOMERS_VIEW, "View customers", CUSTOMERS_MANAGE, "Add and change customers",
				CUSTOMERS_PAYMENTS, "Receive customer payments", CUSTOMERS_PII, "See CNIC and personal details"));
		c.put("Purchasing", ordered(PURCHASING_VIEW, "View purchases and suppliers", PURCHASING_MANAGE,
				"Purchase orders and receiving", SUPPLIERS_PAYMENTS, "Pay suppliers"));
		c.put("Stock", ordered(STOCK_VIEW, "View stock", STOCK_ADJUST, "Adjust stock", STOCK_COUNT, "Stock counts",
				STOCK_TRANSFER, "Transfers"));
		c.put("Money and reports", ordered(CASH_VIEW, "View cash", EXPENSES_MANAGE, "Expenses", REPORTS_VIEW,
				"View reports", DATA_EXPORT, "Export all data"));
		c.put("Approvals", ordered(APPROVALS_GRANT, "Approve with PIN (discounts, returns, write-offs)"));
		c.put("Shop type features", ordered(SERIALS_MANAGE, "IMEI and serial numbers", BUYBACK_MANAGE,
				"Buy used items", WARRANTY_MANAGE, "Warranty claims", REPAIRS_MANAGE, "Repair jobs",
				PRESCRIPTIONS_APPROVE, "Approve prescription and controlled medicines", PROMOTIONS_MANAGE,
				"Offers and coupons", LOYALTY_MANAGE, "Change loyalty points", INSTALLMENTS_MANAGE,
				"Installment plans", DELIVERY_MANAGE, "Deliveries and challans", KITCHEN_VIEW,
				"Kitchen screen and order status"));
		CATALOGUE = java.util.Collections.unmodifiableMap(c);
		CODES = c.values().stream().flatMap(m -> m.keySet().stream()).collect(Collectors.toUnmodifiableSet());
	}

	private Permissions() {
	}

	public static boolean exists(String code) {
		return CODES.contains(code);
	}

	public static List<String> all() {
		return CATALOGUE.values().stream().flatMap(m -> m.keySet().stream()).toList();
	}

	private static Map<String, String> ordered(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	// SpEL helpers for @PreAuthorize
	public static final String HAS_SETTINGS_VIEW = "hasAuthority('" + SETTINGS_VIEW + "')";

	public static final String HAS_SETTINGS_EDIT = "hasAuthority('" + SETTINGS_EDIT + "')";

	public static final String HAS_BRANCHES_MANAGE = "hasAuthority('" + BRANCHES_MANAGE + "')";

	public static final String HAS_USERS_VIEW = "hasAuthority('" + USERS_VIEW + "')";

	public static final String HAS_USERS_MANAGE = "hasAuthority('" + USERS_MANAGE + "')";

	public static final String HAS_AUDIT_VIEW = "hasAuthority('" + AUDIT_VIEW + "')";

	public static final String HAS_LOGIN_HISTORY_VIEW = "hasAuthority('" + LOGIN_HISTORY_VIEW + "')";

	public static final String HAS_CATALOG_VIEW = "hasAuthority('" + CATALOG_VIEW + "')";

	public static final String HAS_CATALOG_MANAGE = "hasAuthority('" + CATALOG_MANAGE + "')";

	public static final String HAS_CATALOG_PRICES = "hasAuthority('" + CATALOG_PRICES + "')";

	public static final String HAS_CATALOG_IMPORT = "hasAuthority('" + CATALOG_IMPORT + "')";

	public static final String HAS_CATALOG_EXPORT = "hasAuthority('" + CATALOG_EXPORT + "')";

	public static final String HAS_LABELS_PRINT = "hasAuthority('" + LABELS_PRINT + "')";

	public static final String HAS_SUPPORT_APPROVE = "hasAuthority('" + SUPPORT_APPROVE + "')";
}
