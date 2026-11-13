package com.selectcode.pos.iam.domain;

import static com.selectcode.pos.shared.security.Permissions.*;

import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.security.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * System roles every shop gets (ACC-01, requirements §2 "Shop side"). The owner role holds every permission. Roles for
 * later releases (order booker, rider) are added in their stage.
 */
public final class RoleTemplates {

	public record Template(String key, String name, String description, List<String> permissions) {
	}

	public static final String OWNER = "OWNER";

	private RoleTemplates() {
	}

	/** System roles for a shop with these modules switched on. */
	public static List<Template> forModules(Set<ModuleKey> modules) {
		List<Template> roles = new ArrayList<>();
		roles.add(new Template(OWNER, "Owner", "Everything in the shop", Permissions.all()));
		roles.add(new Template("PARTNER", "Partner (view only)", "All reports and dashboards; cannot change anything",
				List.of(SETTINGS_VIEW, USERS_VIEW, AUDIT_VIEW, LOGIN_HISTORY_VIEW, CATALOG_VIEW, COST_VIEW, SALES_VIEW,
						CUSTOMERS_VIEW, PURCHASING_VIEW, STOCK_VIEW, CASH_VIEW, REPORTS_VIEW, SUBSCRIPTION_VIEW)));
		roles.add(new Template("MANAGER", "Branch manager",
				"Stock, purchases, returns, reports and approvals for their branches; no shop settings",
				List.of(SETTINGS_VIEW, USERS_VIEW, AUDIT_VIEW, LOGIN_HISTORY_VIEW, CATALOG_VIEW, CATALOG_MANAGE,
						CATALOG_PRICES, CATALOG_EXPORT, LABELS_PRINT, COST_VIEW, POS_SELL, POS_DISCOUNT,
						POS_PRICE_OVERRIDE, SALES_VIEW, SALES_RETURN, SALES_CANCEL, QUOTATIONS_MANAGE, TILL_MANAGE,
						CUSTOMERS_VIEW, CUSTOMERS_MANAGE, CUSTOMERS_PAYMENTS, CUSTOMERS_PII, PURCHASING_VIEW,
						PURCHASING_MANAGE, SUPPLIERS_PAYMENTS, STOCK_VIEW, STOCK_ADJUST, STOCK_COUNT, STOCK_TRANSFER,
						CASH_VIEW, EXPENSES_MANAGE, REPORTS_VIEW, APPROVALS_GRANT, SERIALS_MANAGE, BUYBACK_MANAGE,
						WARRANTY_MANAGE, REPAIRS_MANAGE, PROMOTIONS_MANAGE, LOYALTY_MANAGE, INSTALLMENTS_MANAGE,
						DELIVERY_MANAGE)));
		roles.add(new Template("CASHIER", "Cashier", "Sells at the till and takes payments; never sees cost or profit",
				List.of(CATALOG_VIEW, POS_SELL, SALES_VIEW, QUOTATIONS_MANAGE, TILL_MANAGE, CUSTOMERS_VIEW,
						CUSTOMERS_MANAGE, CUSTOMERS_PAYMENTS)));
		roles.add(new Template("SALESMAN", "Salesman (floor)", "Makes draft bills and quotations for the cashier",
				List.of(CATALOG_VIEW, QUOTATIONS_MANAGE, CUSTOMERS_VIEW)));
		roles.add(new Template("STOCK_KEEPER", "Stock keeper", "Receives goods, counts, transfers and prints labels",
				List.of(CATALOG_VIEW, LABELS_PRINT, PURCHASING_VIEW, PURCHASING_MANAGE, STOCK_VIEW, STOCK_COUNT,
						STOCK_TRANSFER, SERIALS_MANAGE)));
		roles.add(new Template("PURCHASER", "Purchaser", "Purchase orders, supplier prices and purchase returns",
				List.of(CATALOG_VIEW, COST_VIEW, PURCHASING_VIEW, PURCHASING_MANAGE, STOCK_VIEW)));
		roles.add(new Template("ACCOUNTANT", "Accountant", "Payments, expenses, cash closing and reports",
				List.of(CATALOG_VIEW, COST_VIEW, SALES_VIEW, CUSTOMERS_VIEW, CUSTOMERS_PAYMENTS, PURCHASING_VIEW,
						SUPPLIERS_PAYMENTS, CASH_VIEW, EXPENSES_MANAGE, REPORTS_VIEW, DATA_EXPORT)));
		if (modules.contains(ModuleKey.FORMULA) || modules.contains(ModuleKey.PRESCRIPTIONS)) {
			roles.add(new Template("PHARMACIST", "Pharmacist",
					"Cashier rights plus approving prescription and controlled medicines with their PIN",
					List.of(CATALOG_VIEW, POS_SELL, SALES_VIEW, TILL_MANAGE, CUSTOMERS_VIEW, CUSTOMERS_MANAGE,
							CUSTOMERS_PAYMENTS, STOCK_VIEW, PRESCRIPTIONS_APPROVE, APPROVALS_GRANT)));
		}
		if (modules.contains(ModuleKey.REPAIRS)) {
			roles.add(new Template("TECHNICIAN", "Technician", "Repair jobs assigned to them and parts used",
					List.of(CATALOG_VIEW, CUSTOMERS_VIEW, REPAIRS_MANAGE)));
		}
		return roles;
	}
}
