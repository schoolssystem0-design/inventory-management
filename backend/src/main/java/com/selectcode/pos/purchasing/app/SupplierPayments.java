package com.selectcode.pos.purchasing.app;

import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.Idempotency;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier payments (PUR-06): cash, bank, cheque or wallet; the amount clears the oldest unpaid bills first, anything
 * left over stays as an advance on the supplier's account.
 */
@Service
public class SupplierPayments {

	public record SupplierPaymentInput(Long supplierId, Long branchId, String amount, String method, String reference,
			String note) {
	}

	public record Allocation(long grnId, String grnNumber, String supplierBillNo, String amount) {
	}

	public record SupplierPaymentView(long id, String number, long supplierId, String supplierName, String amount,
			String method, String reference, String note, String unallocated, String balanceAfter, String actorName,
			LocalDate date, Instant at, List<Allocation> allocations) {
	}

	private final JdbcTemplate jdbc;

	private final SupplierService suppliers;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final Idempotency idempotency;

	private final AuditService audit;

	private final ShopTime time;

	SupplierPayments(JdbcTemplate jdbc, SupplierService suppliers, BranchAccess branches, ShopConfigService numbering,
			Idempotency idempotency, AuditService audit, ShopTime time) {
		this.jdbc = jdbc;
		this.suppliers = suppliers;
		this.branches = branches;
		this.numbering = numbering;
		this.idempotency = idempotency;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public SupplierPaymentView pay(SupplierPaymentInput in, String idempotencyKey) {
		var earlier = idempotency.claim("SUPPLIER_PAYMENT", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return get(earlier.get());
		}
		if (in.supplierId() == null) {
			throw ApiException.field("supplierId", "required", "Choose a supplier");
		}
		Map<String, Object> supplier = suppliers.lock(in.supplierId());
		BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
		if (amount.signum() <= 0) {
			throw ApiException.field("amount", "invalid", "Enter an amount above zero");
		}
		String method = in.method() == null || in.method().isBlank() ? "CASH" : in.method().trim().toUpperCase();
		checkMethod(jdbc, method);
		Long branchId = in.branchId();
		if (branchId != null) {
			branches.requireActive(branchId);
		}
		String number = numbering.nextNumber("SUPPLIER_PAYMENT", branchId);
		long id = jdbc.queryForObject("""
				insert into supplier_payment (shop_id, number, supplier_id, branch_id, amount, method_key, reference, note,
				                              created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), number,
				in.supplierId(), branchId, amount, method, SupplierService.trim(in.reference(), 100),
				SupplierService.trim(in.note(), 500), PurchaseOrders.userId(), CurrentActor.name(),
				Date.valueOf(time.today()));
		BigDecimal left = amount;
		for (Map<String, Object> grn : jdbc.queryForList("""
				select id, due from grn where supplier_id = ? and due > 0 order by bill_date, id for update""",
				in.supplierId())) {
			if (left.signum() <= 0) {
				break;
			}
			BigDecimal take = left.min((BigDecimal) grn.get("due"));
			long grnId = ((Number) grn.get("id")).longValue();
			jdbc.update("update grn set paid = paid + ? where id = ?", take, grnId);
			jdbc.update("insert into supplier_payment_allocation (shop_id, payment_id, grn_id, amount) values (?, ?, ?, ?)",
					TenantContext.requireShopId(), id, grnId, take);
			left = left.subtract(take);
		}
		jdbc.update("update supplier_payment set unallocated = ? where id = ?", left, id);
		suppliers.post(in.supplierId(), "SUPPLIER_PAYMENT", id, number,
				"Payment " + number + " (" + method.toLowerCase() + ")", amount, null);
		idempotency.complete("SUPPLIER_PAYMENT", idempotencyKey, id);
		audit.record("SUPPLIER_PAID", "supplier_payment", id, null,
				Map.of("number", number, "supplier", supplier.get("name"), "amount", amount.toPlainString()));
		return get(id);
	}

	@Transactional(readOnly = true)
	public SupplierPaymentView get(long id) {
		Map<String, Object> p = jdbc.queryForList("""
				select p.*, s.name as supplier_name, s.balance from supplier_payment p join supplier s on s.id = p.supplier_id
				where p.id = ?""", id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier payment"));
		List<Allocation> allocations = jdbc.query("""
				select a.grn_id, g.number, g.supplier_bill_no, a.amount from supplier_payment_allocation a
				join grn g on g.id = a.grn_id where a.payment_id = ? order by g.bill_date, g.id""",
				(rs, i) -> new Allocation(rs.getLong("grn_id"), rs.getString("number"), rs.getString("supplier_bill_no"),
						Amounts.text(rs.getBigDecimal("amount"))),
				id);
		return new SupplierPaymentView(id, (String) p.get("number"), ((Number) p.get("supplier_id")).longValue(),
				(String) p.get("supplier_name"), Amounts.text((BigDecimal) p.get("amount")), (String) p.get("method_key"),
				(String) p.get("reference"), (String) p.get("note"), Amounts.text((BigDecimal) p.get("unallocated")),
				Amounts.text((BigDecimal) p.get("balance")), (String) p.get("actor_name"),
				((Date) p.get("business_date")).toLocalDate(), ((Timestamp) p.get("at")).toInstant(), allocations);
	}

	@Transactional(readOnly = true)
	public PageResponse<SupplierPaymentView> list(Long supplierId, LocalDate from, LocalDate to, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (supplierId != null) {
			where.append(" and supplier_id = ?");
			args.add(supplierId);
		}
		if (from != null) {
			where.append(" and business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and business_date <= ?");
			args.add(Date.valueOf(to));
		}
		long total = jdbc.queryForObject("select count(*) from supplier_payment" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from supplier_payment" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	/** The method must be one of the shop's payment methods that is switched on (not "on account"). */
	static void checkMethod(JdbcTemplate jdbc, String method) {
		if ("ACCOUNT".equals(method) || "VOUCHER".equals(method)) {
			throw ApiException.field("method", "invalid", "Choose cash, bank, cheque or a wallet");
		}
		Integer ok = jdbc.queryForObject("select count(*) from payment_method where method_key = ? and active",
				Integer.class, method);
		if (ok == null || ok == 0) {
			throw ApiException.field("method", "invalid", "This payment method is not switched on for the shop");
		}
	}
}
