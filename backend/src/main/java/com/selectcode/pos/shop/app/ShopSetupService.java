package com.selectcode.pos.shop.app;

import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shop.domain.Branch;
import com.selectcode.pos.shop.domain.Till;
import com.selectcode.pos.shop.infra.BranchRepository;
import com.selectcode.pos.shop.infra.TillRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting data of a new shop (ONB-07, SET-01..07): settings, branches with one till each, payment methods and
 * document numbering. Called by the platform's shop creation inside its transaction, with the new shop in context.
 */
@Service
public class ShopSetupService {

	public record ShopInit(String businessName, String address, String phone, String email, String ntn,
			String taxMode, BigDecimal taxRate, String receiptLanguage, List<String> branchNames) {
	}

	/** Document types and their default prefixes. */
	public static final Map<String, String> DOC_PREFIXES = new java.util.LinkedHashMap<>();

	static {
		DOC_PREFIXES.put("SALE", "INV");
		DOC_PREFIXES.put("SALE_RETURN", "RET");
		DOC_PREFIXES.put("QUOTATION", "QT");
		DOC_PREFIXES.put("CUSTOMER_PAYMENT", "RCV");
		DOC_PREFIXES.put("PURCHASE_ORDER", "PO");
		DOC_PREFIXES.put("GRN", "GRN");
		DOC_PREFIXES.put("PURCHASE_RETURN", "PRT");
		DOC_PREFIXES.put("SUPPLIER_PAYMENT", "PAY");
		DOC_PREFIXES.put("ADJUSTMENT", "ADJ");
		DOC_PREFIXES.put("STOCK_COUNT", "CNT");
		DOC_PREFIXES.put("TRANSFER", "TRF");
		DOC_PREFIXES.put("EXPENSE", "EXP");
		DOC_PREFIXES.put("CREDIT_NOTE", "CN");
		DOC_PREFIXES.put("BUYBACK", "BUY");
		DOC_PREFIXES.put("WARRANTY_CLAIM", "WCL");
		DOC_PREFIXES.put("REPAIR", "JOB");
		DOC_PREFIXES.put("INSTALLMENT_PLAN", "INS");
		DOC_PREFIXES.put("PRESCRIPTION", "RX");
		DOC_PREFIXES.put("CHALLAN", "DC");
		DOC_PREFIXES.put("DELIVERY", "DLV");
		DOC_PREFIXES.put("CUSTOMER", "C");
		DOC_PREFIXES.put("SUPPLIER", "S");
	}

	/** Expense categories every shop starts with (CSH-01). */
	public static final List<String> EXPENSE_CATEGORIES = List.of("Rent", "Salaries", "Electricity", "Gas and water",
			"Internet and phone", "Tea and food", "Transport", "Repairs and maintenance", "Stationery", "Other");

	private static final List<Object[]> PAYMENT_METHODS = List.of(new Object[] { "CASH", "Cash", true },
			new Object[] { "CARD", "Card", true }, new Object[] { "BANK", "Bank transfer", true },
			new Object[] { "JAZZCASH", "JazzCash", true }, new Object[] { "EASYPAISA", "Easypaisa", true },
			new Object[] { "CHEQUE", "Cheque", false }, new Object[] { "ACCOUNT", "On account (credit)", true },
			new Object[] { "VOUCHER", "Gift voucher / credit note", false });

	private final JdbcTemplate jdbc;

	private final BranchRepository branches;

	private final TillRepository tills;

	ShopSetupService(JdbcTemplate jdbc, BranchRepository branches, TillRepository tills) {
		this.jdbc = jdbc;
		this.branches = branches;
		this.tills = tills;
	}

	/** @return ids of the created branches, the default branch first */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Long> initialise(ShopInit init) {
		long shopId = TenantContext.requireShopId();
		jdbc.update("""
				insert into shop_settings (shop_id, business_name, address, phones, email, ntn, tax_mode, tax_rate,
				                           receipt_language, receipt_footer)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Thank you for shopping with us')""", shopId, init.businessName(),
				init.address(), init.phone(), init.email(), init.ntn(), init.taxMode() == null ? "NONE" : init.taxMode(),
				init.taxRate() == null ? BigDecimal.ZERO : init.taxRate(),
				init.receiptLanguage() == null ? "EN" : init.receiptLanguage());

		List<String> names = new ArrayList<>(new LinkedHashSet<>(init.branchNames() == null ? List.of()
				: init.branchNames().stream().map(String::trim).filter(s -> !s.isEmpty()).toList()));
		if (names.isEmpty()) {
			names.add("Main shop");
		}
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			Branch.Kind kind = name.toLowerCase().contains("godown") || name.toLowerCase().contains("warehouse")
					? Branch.Kind.GODOWN : Branch.Kind.SHOP;
			Branch branch = branches.save(new Branch(name, "B" + (i + 1), kind, i == 0));
			ids.add(branch.getId());
			if (kind == Branch.Kind.SHOP) {
				tills.save(new Till(branch.getId(), "Till 1"));
			}
		}
		int order = 0;
		for (Object[] method : PAYMENT_METHODS) {
			jdbc.update("""
					insert into payment_method (shop_id, method_key, label, active, sort_order) values (?, ?, ?, ?, ?)""",
					shopId, method[0], method[1], method[2], order++);
		}
		DOC_PREFIXES.forEach((type, prefix) -> jdbc.update(
				"insert into numbering (shop_id, doc_type, prefix, next_no, digits) values (?, ?, ?, 1, 6)", shopId, type,
				prefix));
		EXPENSE_CATEGORIES.forEach(
				name -> jdbc.update("insert into expense_category (shop_id, name) values (?, ?)", shopId, name));
		return ids;
	}
}
