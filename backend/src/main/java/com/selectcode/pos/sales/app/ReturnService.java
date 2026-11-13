package com.selectcode.pos.sales.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.BundlePart;
import com.selectcode.pos.customers.app.CustomerLedger;
import com.selectcode.pos.iam.app.ApprovalService;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.SalesService.Tender;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.Idempotency;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns, exchanges, credit notes and same-day cancellation (SAL-02, SAL-03, SAL-05, GAR-04).
 *
 * <p>A return's value is the line total per unit returned. It first clears what the customer still owes on the
 * invoice; only the rest can be refunded (in cash, to their account or as a credit note) and anything not refunded
 * is a return charge kept by the shop (TC-SAL-02). Restocked items go back into stock at the cost they were sold at;
 * damaged items do not, and their cost shows as a loss.
 */
@Service
public class ReturnService {

	/** @param restock false = damaged, not back in stock; {@code condition} for a returned phone (NEW / USED) */
	public record ReturnLineInput(Long saleLineId, String qty, Boolean restock, String condition) {
	}

	/**
	 * @param refundMethod CASH (or another money method), ACCOUNT (customer's account) or CREDIT_NOTE
	 * @param refundAmount how much to give back (default: as much as allowed)
	 */
	public record ReturnInput(Long tillSessionId, String reason, List<ReturnLineInput> lines, String refundMethod,
			String refundAmount) {
	}

	public record ExchangeInput(Long saleId, String reason, List<ReturnLineInput> returnLines, SaleInput newSale) {
	}

	public record ExchangeView(ReturnView returned, SaleQueries.SaleView sale) {
	}

	public record ReturnLineView(long saleLineId, String productName, String qty, boolean restock, String value,
			String serial, String batchNo) {
	}

	public record ReturnView(long id, String number, long saleId, String saleNumber, String reason, String value,
			String appliedToDue, String refundAmount, String refundMethod, String returnCharge, String creditNoteNumber,
			String costRestocked, String costDamaged, String approvedByName, String actorName, LocalDate date,
			Instant at, List<ReturnLineView> lines) {
	}

	public record CreditNoteView(long id, String number, Long customerId, String customerName, String amount,
			String balance, LocalDate expiresOn, boolean expired, Instant createdAt) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final CustomerLedger ledger;

	private final TillService tills;

	private final SalesService sales;

	private final SaleQueries queries;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final ApprovalService approvals;

	private final Idempotency idempotency;

	private final AuditService audit;

	private final ShopTime time;

	private final LoyaltyService loyalty;

	private final PromotionService promotions;

	private final PrescriptionService prescriptions;

	private final KitchenService kitchen;

	ReturnService(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, CustomerLedger ledger,
			TillService tills, SalesService sales, SaleQueries queries, BranchAccess branches,
			ShopConfigService numbering, ApprovalService approvals, Idempotency idempotency, AuditService audit,
			ShopTime time, LoyaltyService loyalty, PromotionService promotions, PrescriptionService prescriptions,
			KitchenService kitchen) {
		this.kitchen = kitchen;
		this.loyalty = loyalty;
		this.promotions = promotions;
		this.prescriptions = prescriptions;
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.ledger = ledger;
		this.tills = tills;
		this.sales = sales;
		this.queries = queries;
		this.branches = branches;
		this.numbering = numbering;
		this.approvals = approvals;
		this.idempotency = idempotency;
		this.audit = audit;
		this.time = time;
	}

	@Transactional
	public ReturnView returnItems(long saleId, ReturnInput in, String idempotencyKey, ApprovalRequest approval) {
		Map<String, Object> sale = lockSale(saleId);
		var earlier = idempotency.claim("SALE_RETURN", idempotencyKey);
		if (earlier.isPresent()) {
			return get(earlier.get());
		}
		long id = returnInternal(sale, in.tillSessionId(), in.reason(), in.lines(), in.refundMethod(),
				in.refundAmount(), approval, false);
		idempotency.complete("SALE_RETURN", idempotencyKey, id);
		return get(id);
	}

	/** Exchange (SAL-03): the returned goods pay for the new sale; the customer pays or gets the difference. */
	@Transactional
	public ExchangeView exchange(ExchangeInput in, String idempotencyKey, ApprovalRequest approval) {
		if (in.saleId() == null) {
			throw ApiException.field("saleId", "required", "Choose the invoice being exchanged");
		}
		Map<String, Object> sale = lockSale(in.saleId());
		var earlier = idempotency.claim("EXCHANGE", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			ReturnView r = get(earlier.get());
			Long newSale = jdbc.queryForObject("select exchange_sale_id from sale_return where id = ?", Long.class, r.id());
			return new ExchangeView(r, newSale == null ? null : queries.get(newSale));
		}
		if (in.newSale() == null) {
			throw ApiException.field("newSale", "required", "Add the items the customer takes instead");
		}
		long returnId = returnInternal(sale, in.newSale().tillSessionId(), in.reason(), in.returnLines(), "EXCHANGE",
				null, approval, true);
		BigDecimal credit = jdbc.queryForObject("select refund_amount from sale_return where id = ?", BigDecimal.class,
				returnId);
		List<Tender> tenders = credit.signum() > 0 ? List.of(new Tender("EXCHANGE", credit, null)) : List.of();
		SaleInput next = in.newSale();
		if (next.customerId() == null && sale.get("customer_id") != null) {
			next = new SaleInput(next.tillSessionId(), ((Number) sale.get("customer_id")).longValue(), null,
					next.customerName(), next.customerPhone(), next.salesmanId(), next.lines(), next.billDiscount(),
					next.billDiscountPercent(), next.payments(), next.note(), null, next.couponCode(),
					next.prescription());
		}
		long newSaleId;
		try {
			newSaleId = sales.postInternal(next, approval, tenders, returnId);
		}
		catch (ApiException e) {
			if ("overpaid".equals(e.code())) {
				throw ApiException.rule("exchange_credit_left", "The returned items are worth more than the new items. "
						+ "Add items or take a return with a refund instead.");
			}
			throw e;
		}
		jdbc.update("update sale_return set exchange_sale_id = ? where id = ?", newSaleId, returnId);
		idempotency.complete("EXCHANGE", idempotencyKey, returnId);
		return new ExchangeView(get(returnId), queries.get(newSaleId));
	}

	private long returnInternal(Map<String, Object> sale, Long sessionIdIn, String reasonIn,
			List<ReturnLineInput> inputs, String refundMethodIn, String refundAmountIn, ApprovalRequest approval,
			boolean exchange) {
		long saleId = ((Number) sale.get("id")).longValue();
		if ("CANCELLED".equals(sale.get("status"))) {
			throw ApiException.rule("sale_cancelled", "This invoice was cancelled");
		}
		if (inputs == null || inputs.isEmpty()) {
			throw ApiException.field("lines", "required", "Choose the items being returned");
		}
		String reason = reasonIn == null || reasonIn.isBlank() ? null : reasonIn.trim();
		if (reason == null) {
			throw ApiException.field("reason", "required", "Write why the items are returned");
		}
		Map<String, Object> settings = jdbc.queryForMap("""
				select return_days, return_limit, credit_note_days, exchange_credit_note_only from shop_settings
				where shop_id = ?""", TenantContext.requireShopId());
		LocalDate today = time.today();
		long branchId = ((Number) sale.get("branch_id")).longValue();
		Long customerId = sale.get("customer_id") == null ? null : ((Number) sale.get("customer_id")).longValue();

		record Planned(Map<String, Object> line, BigDecimal qty, BigDecimal baseQty, boolean restock, String condition,
				BigDecimal value) {
		}
		List<Planned> planned = new ArrayList<>();
		BigDecimal value = BigDecimal.ZERO;
		for (int i = 0; i < inputs.size(); i++) {
			ReturnLineInput input = inputs.get(i);
			String f = "lines[" + i + "]";
			if (input.saleLineId() == null) {
				throw ApiException.field(f + ".saleLineId", "required", "Choose the line");
			}
			Map<String, Object> line = jdbc.queryForList("select * from sale_line where id = ? and sale_id = ? for update",
					input.saleLineId(), saleId).stream().findFirst()
				.orElseThrow(() -> ApiException.field(f + ".saleLineId", "invalid", "This line is not on the invoice"));
			BigDecimal sold = (BigDecimal) line.get("qty");
			BigDecimal already = (BigDecimal) line.get("qty_returned");
			BigDecimal qty = line.get("serial_id") != null ? BigDecimal.ONE.setScale(3)
					: Amounts.parseQty(input.qty(), f + ".qty");
			if (qty == null || qty.signum() <= 0) {
				throw ApiException.field(f + ".qty", "required", "Enter how many are returned");
			}
			if (already.add(qty).compareTo(sold) > 0) {
				throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "already_returned",
						"Only " + sold.subtract(already).stripTrailingZeros().toPlainString() + " of " + line.get("product_name")
								+ " can still be returned",
						Map.of("saleLineId", input.saleLineId(), "returnable", Amounts.qtyText(sold.subtract(already))));
			}
			BigDecimal lineValue = SaleMath.returnValue((BigDecimal) line.get("total"), sold, already, qty);
			BigDecimal factor = (BigDecimal) line.get("unit_factor");
			planned.add(new Planned(line, qty, Amounts.qty(qty.multiply(factor)), !Boolean.FALSE.equals(input.restock()),
					input.condition() == null || input.condition().isBlank() ? null : input.condition().trim().toUpperCase(),
					lineValue));
			value = value.add(lineValue);
		}

		// Approvals: late returns and big returns (ACC-04).
		LocalDate sold = ((Date) sale.get("business_date")).toLocalDate();
		int returnDays = ((Number) settings.get("return_days")).intValue();
		BigDecimal returnLimit = (BigDecimal) settings.get("return_limit");
		Long approvedBy = null;
		if (sold.plusDays(returnDays).isBefore(today)) {
			approvedBy = approvals.require(approval, Permissions.SALES_RETURN, "Return after " + returnDays + " days",
					"return after " + returnDays + " days");
		}
		if (value.compareTo(returnLimit) > 0) {
			approvedBy = approvals.require(approval, Permissions.SALES_RETURN, "Return of Rs " + value.toPlainString(),
					"return above Rs " + returnLimit.toPlainString());
		}

		// Money: clear the amount owed first, then refund up to what is left (TC-SAL-02).
		BigDecimal total = (BigDecimal) sale.get("total");
		BigDecimal returned = (BigDecimal) sale.get("returned");
		BigDecimal paid = (BigDecimal) sale.get("paid");
		BigDecimal due = SaleMath.dueBefore(total, returned, paid);
		BigDecimal maxRefund = SaleMath.maxRefund(total, returned, paid, value);
		BigDecimal appliedToDue = value.min(due);
		String method = exchange ? "EXCHANGE"
				: refundMethodIn == null || refundMethodIn.isBlank() ? "CASH" : refundMethodIn.trim().toUpperCase();
		if (!exchange && Boolean.TRUE.equals(settings.get("exchange_credit_note_only")) && maxRefund.signum() > 0
				&& !"CREDIT_NOTE".equals(method)) {
			throw ApiException.rule("credit_note_only", "This shop gives a credit note for returns, not money back");
		}
		BigDecimal refund = refundAmountIn == null || refundAmountIn.isBlank() ? maxRefund
				: Amounts.requireMoney(refundAmountIn, "refundAmount");
		if (refund.compareTo(maxRefund) > 0) {
			throw ApiException.rule("refund_too_large", "At most Rs " + maxRefund.toPlainString()
					+ " can be refunded: the return first clears Rs " + due.toPlainString() + " still owed on the invoice");
		}
		BigDecimal charge = value.subtract(appliedToDue).subtract(refund);
		Map<String, Object> session = null;
		if (refund.signum() > 0 && !exchange) {
			if ("ACCOUNT".equals(method)) {
				if (customerId == null) {
					throw ApiException.rule("no_customer", "This invoice has no customer account to refund to");
				}
			}
			else if (!"CREDIT_NOTE".equals(method)) {
				Integer ok = jdbc.queryForObject("select count(*) from payment_method where method_key = ? and active",
						Integer.class, method);
				if (ok == null || ok == 0 || Set.of("VOUCHER").contains(method)) {
					throw ApiException.field("refundMethod", "invalid", "Choose cash, card, a wallet, account or credit note");
				}
				session = tills.requireOpenSession(sessionIdIn, branchId);
			}
		}
		if (session == null && sessionIdIn != null) {
			session = tills.requireOpenSession(sessionIdIn, null);
		}
		Long sessionId = session == null ? null : ((Number) session.get("id")).longValue();

		String number = numbering.nextNumber("SALE_RETURN", branchId);
		Long creditNoteId = null;
		String creditNoteNumber = null;
		if ("CREDIT_NOTE".equals(method) && refund.signum() > 0) {
			creditNoteNumber = numbering.nextNumber("CREDIT_NOTE", null);
			int days = ((Number) settings.get("credit_note_days")).intValue();
			creditNoteId = jdbc.queryForObject("""
					insert into credit_note (shop_id, number, customer_id, amount, balance, expires_on)
					values (?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(), creditNoteNumber,
					customerId, refund, refund, days == 0 ? null : Date.valueOf(today.plusDays(days)));
		}
		long returnId = jdbc.queryForObject("""
				insert into sale_return (shop_id, number, sale_id, customer_id, branch_id, till_session_id, reason, value,
				                         applied_to_due, refund_amount, refund_method, return_charge, credit_note_id,
				                         approved_by, user_id, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, saleId, customerId, branchId, sessionId,
				reason.length() > 300 ? reason.substring(0, 300) : reason, value, appliedToDue, refund,
				refund.signum() > 0 ? method : null, charge, creditNoteId, approvedBy, TillService.userId(),
				CurrentActor.name(), Date.valueOf(today));
		if (creditNoteId != null) {
			jdbc.update("update credit_note set source_return_id = ? where id = ?", returnId, creditNoteId);
		}

		// Stock back in for restocked lines, at the cost they were sold at.
		DocRef doc = new DocRef("SALE_RETURN", returnId, number);
		List<Move> moves = new ArrayList<>();
		BigDecimal costRestocked = BigDecimal.ZERO;
		BigDecimal costDamaged = BigDecimal.ZERO;
		List<Planned> ordered = new ArrayList<>(planned);
		ordered.sort(Comparator.comparingLong(p -> ((Number) p.line().get("product_id")).longValue()));
		for (Planned p : ordered) {
			Map<String, Object> line = p.line();
			long productId = ((Number) line.get("product_id")).longValue();
			Long batchId = line.get("batch_id") == null ? null : ((Number) line.get("batch_id")).longValue();
			Long serialId = line.get("serial_id") == null ? null : ((Number) line.get("serial_id")).longValue();
			BigDecimal unitCost = (BigDecimal) line.get("unit_cost");
			BigDecimal lineCost = Amounts.money(unitCost.multiply(p.baseQty()));
			jdbc.update("update sale_line set qty_returned = qty_returned + ? where id = ?", p.qty(), line.get("id"));
			jdbc.update("""
					insert into sale_return_line (shop_id, return_id, sale_line_id, product_id, batch_id, serial_id, qty,
					                              base_qty, restock, value, unit_cost)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), returnId, line.get("id"),
					productId, batchId, serialId, p.qty(), p.baseQty(), p.restock(), p.value(), unitCost);
			if (serialId != null) {
				stock.lockSerialById(serialId);
				stock.markSerialReturned(serialId, p.restock(), p.condition());
			}
			String kind = catalog.require(productId).kind();
			if ("RECIPE".equals(kind)) {
				// Food that was made cannot go back on the shelf: its cost is a loss.
				costDamaged = costDamaged.add(lineCost);
				continue;
			}
			if (!p.restock()) {
				costDamaged = costDamaged.add(lineCost);
				continue;
			}
			costRestocked = costRestocked.add(lineCost);
			if ("BUNDLE".equals(kind)) {
				for (BundlePart item : catalog.bundle(productId)) {
					if (!catalog.require(item.productId()).tracksStock()) {
						continue; // menu items and services in a deal do not come back into stock
					}
					BigDecimal qty = Amounts.qty(item.qty().multiply(p.baseQty()));
					BigDecimal cost = stock.averageCost(item.productId());
					moves.add(new Move(item.productId(), branchId, null, null, qty, cost, "Return " + number));
				}
			}
			else if (!"SERVICE".equals(kind)) {
				stock.averageIn(productId, p.baseQty(), unitCost);
				moves.add(new Move(productId, branchId, batchId, serialId, p.baseQty(), unitCost, null));
			}
		}
		stock.apply(MoveType.SALE_RETURN, doc, moves);
		jdbc.update("update sale_return set cost_restocked = ?, cost_damaged = ? where id = ?", costRestocked,
				costDamaged, returnId);

		// The invoice: returned value credited (what cleared the debt plus what was refunded); refunds reduce paid.
		BigDecimal credited = appliedToDue.add(refund);
		jdbc.update("update sale set returned = returned + ?, paid = paid - ? where id = ?", credited, refund, saleId);
		if (refund.signum() > 0) {
			sales.insertPayment(saleId, "REFUND", method, refund.negate(), null, null, creditNoteNumber, sessionId,
					branchId, "SALE_RETURN", returnId, today);
		}
		if (customerId != null) {
			if (credited.signum() > 0) {
				ledger.post(customerId, "SALE_RETURN", returnId, number, "Return " + number, null, credited);
			}
			if (refund.signum() > 0 && !"ACCOUNT".equals(method)) {
				String text = "CREDIT_NOTE".equals(method) ? "Credit note " + creditNoteNumber
						: "EXCHANGE".equals(method) ? "Used for exchange" : "Refund paid (" + method.toLowerCase() + ")";
				ledger.post(customerId, "REFUND", returnId, number, text, refund, null);
			}
		}
		loyalty.takeBackForReturn(saleId, returnId, number, value, total, today);
		Map<Long, BigDecimal> returnedLines = new java.util.LinkedHashMap<>();
		for (Planned p : planned) {
			returnedLines.put(((Number) p.line().get("id")).longValue(), p.qty());
		}
		prescriptions.registerReturn(saleId, returnId, returnedLines);
		audit.record("SALE_RETURNED", "sale_return", returnId, null, Map.of("number", number, "sale",
				sale.get("number"), "value", value.toPlainString(), "refund", refund.toPlainString()));
		return returnId;
	}

	/**
	 * Same-day cancellation by a manager (SAL-05): stock goes back, payments are reversed and the invoice stays,
	 * marked cancelled with the reason. Not possible on another day or after a return.
	 */
	@Transactional
	public SaleQueries.SaleView cancel(long saleId, String reason, Long tillSessionId) {
		Map<String, Object> sale = lockSale(saleId);
		if (reason == null || reason.isBlank()) {
			throw ApiException.field("reason", "required", "Write why the invoice is cancelled");
		}
		if ("CANCELLED".equals(sale.get("status"))) {
			throw ApiException.rule("sale_cancelled", "This invoice is already cancelled");
		}
		LocalDate date = ((Date) sale.get("business_date")).toLocalDate();
		if (!date.equals(time.today())) {
			throw ApiException.rule("not_same_day", "Only today's invoices can be cancelled; take a return instead");
		}
		Integer returns = jdbc.queryForObject("select count(*) from sale_return where sale_id = ?", Integer.class, saleId);
		Integer receipts = jdbc.queryForObject("select count(*) from sale_payment where sale_id = ? and kind <> 'SALE'",
				Integer.class, saleId);
		if (returns != null && returns > 0 || receipts != null && receipts > 0) {
			throw ApiException.rule("sale_has_returns", "This invoice already has returns or later payments; take a return instead");
		}
		long branchId = ((Number) sale.get("branch_id")).longValue();
		String number = (String) sale.get("number");
		// Stock back, serials back in stock.
		List<Move> moves = new ArrayList<>();
		for (Map<String, Object> m : jdbc.queryForList("""
				select * from stock_movement where doc_type = 'SALE' and doc_id = ? order by product_id, branch_id, id""",
				saleId)) {
			Long serialId = m.get("serial_id") == null ? null : ((Number) m.get("serial_id")).longValue();
			if (serialId != null) {
				stock.lockSerialById(serialId);
				stock.markSerialReturned(serialId, true, null);
			}
			moves.add(new Move(((Number) m.get("product_id")).longValue(), branchId,
					m.get("batch_id") == null ? null : ((Number) m.get("batch_id")).longValue(), serialId,
					((BigDecimal) m.get("qty")).negate(), (BigDecimal) m.get("unit_cost"), "Cancelled"));
		}
		stock.apply(MoveType.CANCEL, new DocRef("SALE_CANCEL", saleId, number), moves);
		// Payments reversed (cash out of the till that took it, if still open, else the canceller's till).
		Long session = sale.get("till_session_id") == null ? null : ((Number) sale.get("till_session_id")).longValue();
		Boolean sessionOpen = session == null ? false
				: jdbc.queryForObject("select closed_at is null from till_session where id = ?", Boolean.class, session);
		Long refundSession = Boolean.TRUE.equals(sessionOpen) ? session
				: tillSessionId != null ? ((Number) tills.requireOpenSession(tillSessionId, branchId).get("id")).longValue()
						: null;
		for (Map<String, Object> p : jdbc.queryForList("select * from sale_payment where sale_id = ? order by id", saleId)) {
			BigDecimal amount = (BigDecimal) p.get("amount");
			String method = (String) p.get("method_key");
			if ("CREDIT_NOTE".equals(method) && p.get("reference") != null) {
				jdbc.update("update credit_note set balance = balance + ? where upper(number) = ?", amount,
						p.get("reference").toString().toUpperCase());
			}
			sales.insertPayment(saleId, "CANCEL", method, amount.negate(), null, null, (String) p.get("reference"),
					"CASH".equals(method) ? refundSession : null, branchId, "SALE_CANCEL", saleId, time.today());
		}
		BigDecimal total = (BigDecimal) sale.get("total");
		BigDecimal paid = (BigDecimal) sale.get("paid");
		jdbc.update("""
				update sale set status = 'CANCELLED', returned = total, paid = 0, cancelled_at = now(), cancelled_by = ?,
				       cancel_reason = ? where id = ?""", TillService.userId(), reason.trim().length() > 300
				? reason.trim().substring(0, 300) : reason.trim(), saleId);
		if (sale.get("customer_id") != null) {
			long customerId = ((Number) sale.get("customer_id")).longValue();
			ledger.post(customerId, "SALE_CANCEL", saleId, number, "Invoice " + number + " cancelled", null, total);
			if (paid.signum() > 0) {
				ledger.post(customerId, "SALE_CANCEL", saleId, number, "Payment returned on cancel", paid, null);
			}
		}
		loyalty.reverseSale(saleId, number, time.today());
		promotions.releaseUse(saleId);
		kitchen.cancelForSale(saleId);
		prescriptions.registerCancel(saleId);
		audit.record("SALE_CANCELLED", "sale", saleId, null, Map.of("number", number, "reason", reason.trim()));
		return queries.get(saleId);
	}

	@Transactional(readOnly = true)
	public ReturnView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> r = jdbc.queryForList("""
				select r.*, s.number as sale_number, c.number as cn_number, u.display_name as approver from sale_return r
				join sale s on s.id = r.sale_id left join credit_note c on c.id = r.credit_note_id
				left join app_user u on u.id = r.approved_by where r.id = ?""" + branches.filter("r.branch_id"), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Return"));
		List<ReturnLineView> lines = jdbc.query("""
				select rl.*, l.product_name, su.serial, b.batch_no from sale_return_line rl
				join sale_line l on l.id = rl.sale_line_id left join serial_unit su on su.id = rl.serial_id
				left join batch b on b.id = rl.batch_id where rl.return_id = ? order by rl.id""",
				(rs, i) -> new ReturnLineView(rs.getLong("sale_line_id"), rs.getString("product_name"),
						Amounts.qtyText(rs.getBigDecimal("qty")), rs.getBoolean("restock"),
						Amounts.text(rs.getBigDecimal("value")), rs.getString("serial"), rs.getString("batch_no")),
				id);
		return new ReturnView(id, (String) r.get("number"), ((Number) r.get("sale_id")).longValue(),
				(String) r.get("sale_number"), (String) r.get("reason"), Amounts.text((BigDecimal) r.get("value")),
				Amounts.text((BigDecimal) r.get("applied_to_due")), Amounts.text((BigDecimal) r.get("refund_amount")),
				(String) r.get("refund_method"), Amounts.text((BigDecimal) r.get("return_charge")),
				(String) r.get("cn_number"), cost ? Amounts.text((BigDecimal) r.get("cost_restocked")) : null,
				cost ? Amounts.text((BigDecimal) r.get("cost_damaged")) : null, (String) r.get("approver"),
				(String) r.get("actor_name"), ((Date) r.get("business_date")).toLocalDate(),
				((Timestamp) r.get("at")).toInstant(), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<ReturnView> list(LocalDate from, LocalDate to, Long branchId, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (from != null) {
			where.append(" and business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and business_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (branchId != null) {
			where.append(" and branch_id = ?");
			args.add(branchId);
		}
		long total = jdbc.queryForObject("select count(*) from sale_return" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from sale_return" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::get).toList(), page, size, total);
	}

	@Transactional(readOnly = true)
	public List<CreditNoteView> creditNotes(String number, Long customerId) {
		StringBuilder where = new StringBuilder(" where 1=1");
		List<Object> args = new ArrayList<>();
		if (number != null && !number.isBlank()) {
			where.append(" and upper(n.number) = ?");
			args.add(number.trim().toUpperCase());
		}
		if (customerId != null) {
			where.append(" and n.customer_id = ?");
			args.add(customerId);
		}
		LocalDate today = time.today();
		return jdbc.query("""
				select n.*, c.name as customer_name from credit_note n left join customer c on c.id = n.customer_id"""
				+ where + " order by n.id desc limit 100", (rs, i) -> {
			LocalDate expires = rs.getObject("expires_on", LocalDate.class);
			return new CreditNoteView(rs.getLong("id"), rs.getString("number"), (Long) rs.getObject("customer_id"),
					rs.getString("customer_name"), Amounts.text(rs.getBigDecimal("amount")),
					Amounts.text(rs.getBigDecimal("balance")), expires, expires != null && expires.isBefore(today),
					rs.getTimestamp("created_at").toInstant());
		}, args.toArray());
	}

	private Map<String, Object> lockSale(long saleId) {
		Map<String, Object> sale = jdbc.queryForList("select * from sale where id = ? for update", saleId)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Sale"));
		if (!branches.allowed(((Number) sale.get("branch_id")).longValue())) {
			throw ApiException.notFound("Sale");
		}
		return sale;
	}

	@SuppressWarnings("unused")
	private static Instant now() {
		return Instant.now();
	}
}
