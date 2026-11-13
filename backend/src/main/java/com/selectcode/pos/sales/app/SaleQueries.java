package com.selectcode.pos.sales.app;

import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchAccess;
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
 * Reading sales (SAL-01): the invoice with its lines, payments and returns, the sales list with filters, and receipt
 * data for printing (POS-06). Cost and profit only for users with {@code cost.view}; a user tied to branches only sees
 * their branches' sales (ACC-03).
 */
@Service
public class SaleQueries {

	public record SaleLineView(long id, int lineNo, long productId, String productName, String unitName,
			String qty, String price, String listPrice, String discount, String billShare, String net, String taxRate,
			String tax, String total, String batchNo, LocalDate expiry, String serial, LocalDate warrantyUntil,
			String qtyReturned, String unitCost, String costTotal, String promoDiscount, String offerName, String note) {
	}

	public record SalePaymentView(long id, String kind, String method, String amount, String tendered,
			String change, String reference, Instant at) {
	}

	public record SaleReturnSummary(long id, String number, String value, String refund, String refundMethod,
			Instant at) {
	}

	public record SaleView(long id, String number, long branchId, String branchName, Long tillSessionId,
			Long customerId, String customerName, String customerPhone, String cashierName, String status,
			String subtotal, String lineDiscount, String billDiscount, String tax, String total, String returned,
			String paid, String due, String change, String costTotal, String profit, String approvalNote,
			String note, LocalDate date, Instant at, Instant cancelledAt, String cancelReason, List<SaleLineView> lines,
			List<SalePaymentView> payments, List<SaleReturnSummary> returns, String promoDiscount, String couponCode,
			String billOfferName, Long prescriptionId, int pointsEarned, int pointsUsed, String orderType, String tableNo,
			Integer tokenNo) {
	}

	public record SaleListItem(long id, String number, String branchName, String customerName, String cashierName,
			String status, String total, String paid, String due, String profit, int items, LocalDate date,
			Instant at) {
	}

	public record SaleList(List<SaleListItem> items, int page, int size, long total, String totalAmount,
			String totalDue) {
	}

	/** Receipt: the shop's header and footer with the sale (printed at 80 mm, 58 mm or A4). */
	public record ReceiptView(String businessName, String urduName, String address, String phones, String ntn,
			String strn, Long logoFileId, String header, String footer, String returnPolicy, String size,
			String language, boolean showBarcode, boolean showTax, boolean showSavings, String savings, SaleView sale) {
	}

	private final JdbcTemplate jdbc;

	private final BranchAccess branches;

	SaleQueries(JdbcTemplate jdbc, BranchAccess branches) {
		this.jdbc = jdbc;
		this.branches = branches;
	}

	@Transactional(readOnly = true)
	public SaleView get(long id) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		Map<String, Object> s = jdbc.queryForList("""
				select s.*, b.name as branch_name from sale s join branch b on b.id = s.branch_id where s.id = ?"""
				+ branches.filter("s.branch_id"), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Sale"));
		List<SaleLineView> lines = jdbc.query("""
				select l.*, bt.batch_no, bt.expiry_date, su.serial, su.warranty_until, pr.name as offer_name from sale_line l
				left join batch bt on bt.id = l.batch_id left join serial_unit su on su.id = l.serial_id
				left join promotion pr on pr.id = l.promotion_id
				where l.sale_id = ? order by l.line_no""", (rs, i) -> new SaleLineView(rs.getLong("id"),
				rs.getInt("line_no"), rs.getLong("product_id"), rs.getString("product_name"), rs.getString("unit_name"),
				Amounts.qtyText(rs.getBigDecimal("qty")), Amounts.text(rs.getBigDecimal("price")),
				Amounts.text(rs.getBigDecimal("list_price")), Amounts.text(rs.getBigDecimal("discount")),
				Amounts.text(rs.getBigDecimal("bill_share")), Amounts.text(rs.getBigDecimal("net")),
				Amounts.percentText(rs.getBigDecimal("tax_rate")), Amounts.text(rs.getBigDecimal("tax")),
				Amounts.text(rs.getBigDecimal("total")), rs.getString("batch_no"),
				rs.getObject("expiry_date", LocalDate.class), rs.getString("serial"),
				rs.getObject("warranty_until", LocalDate.class), Amounts.qtyText(rs.getBigDecimal("qty_returned")),
				cost ? Amounts.text(rs.getBigDecimal("unit_cost")) : null,
				cost ? Amounts.text(rs.getBigDecimal("cost_total")) : null, Amounts.text(rs.getBigDecimal("promo_discount")),
				rs.getString("offer_name"), rs.getString("note")), id);
		List<SalePaymentView> payments = jdbc.query("select * from sale_payment where sale_id = ? order by id",
				(rs, i) -> new SalePaymentView(rs.getLong("id"), rs.getString("kind"), rs.getString("method_key"),
						Amounts.text(rs.getBigDecimal("amount")), Amounts.text(rs.getBigDecimal("tendered")),
						Amounts.text(rs.getBigDecimal("change_given")), rs.getString("reference"),
						rs.getTimestamp("at").toInstant()),
				id);
		List<SaleReturnSummary> returns = jdbc.query("select * from sale_return where sale_id = ? order by id",
				(rs, i) -> new SaleReturnSummary(rs.getLong("id"), rs.getString("number"),
						Amounts.text(rs.getBigDecimal("value")), Amounts.text(rs.getBigDecimal("refund_amount")),
						rs.getString("refund_method"), rs.getTimestamp("at").toInstant()),
				id);
		BigDecimal total = (BigDecimal) s.get("total");
		BigDecimal costTotal = (BigDecimal) s.get("cost_total");
		BigDecimal returnedCost = jdbc.queryForObject("""
				select coalesce(sum(r.cost_restocked), 0) from sale_return r where r.sale_id = ?""", BigDecimal.class, id);
		BigDecimal returned = (BigDecimal) s.get("returned");
		BigDecimal tax = (BigDecimal) s.get("tax");
		boolean cancelled = "CANCELLED".equals(s.get("status"));
		BigDecimal profit = cancelled ? BigDecimal.ZERO
				: total.subtract(taxOnTop(tax)).subtract(returned).subtract(costTotal.subtract(returnedCost));
		boolean pii = CurrentActor.hasPermission(Permissions.CUSTOMERS_PII);
		String phone = (String) s.get("customer_phone");
		return new SaleView(id, (String) s.get("number"), ((Number) s.get("branch_id")).longValue(),
				(String) s.get("branch_name"),
				s.get("till_session_id") == null ? null : ((Number) s.get("till_session_id")).longValue(),
				s.get("customer_id") == null ? null : ((Number) s.get("customer_id")).longValue(),
				(String) s.get("customer_name"), pii ? phone : CustomerService.maskPhone(phone),
				(String) s.get("actor_name"), (String) s.get("status"), Amounts.text((BigDecimal) s.get("subtotal")),
				Amounts.text((BigDecimal) s.get("line_discount")), Amounts.text((BigDecimal) s.get("bill_discount")),
				Amounts.text(tax), Amounts.text(total), Amounts.text(returned), Amounts.text((BigDecimal) s.get("paid")),
				Amounts.text((BigDecimal) s.get("due")), Amounts.text((BigDecimal) s.get("change_given")),
				cost ? Amounts.text(costTotal) : null, cost ? Amounts.text(profit) : null,
				(String) s.get("approval_note"), (String) s.get("note"), ((Date) s.get("business_date")).toLocalDate(),
				((Timestamp) s.get("at")).toInstant(),
				s.get("cancelled_at") == null ? null : ((Timestamp) s.get("cancelled_at")).toInstant(),
				(String) s.get("cancel_reason"), lines, payments, returns, Amounts.text((BigDecimal) s.get("promo_discount")),
				(String) s.get("coupon_code"),
				s.get("bill_promotion_id") == null ? null
						: jdbc.queryForList("select name from promotion where id = ?", String.class, s.get("bill_promotion_id"))
							.stream().findFirst().orElse(null),
				s.get("prescription_id") == null ? null : ((Number) s.get("prescription_id")).longValue(),
				points(id, "EARN"), -points(id, "REDEEM"), (String) s.get("order_type"), (String) s.get("table_no"),
				s.get("token_no") == null ? null : ((Number) s.get("token_no")).intValue());
	}

	private int points(long saleId, String kind) {
		Integer n = jdbc.queryForObject("select coalesce(sum(points), 0) from loyalty_txn where sale_id = ? and kind = ?",
				Integer.class, saleId, kind);
		return n == null ? 0 : n;
	}

	/** Tax counted in a sale's total that is not the shop's income (sales tax added on top or contained). */
	private static BigDecimal taxOnTop(BigDecimal tax) {
		return tax;
	}

	@Transactional(readOnly = true)
	public SaleList list(LocalDate from, LocalDate to, Long branchId, Long userId, Long customerId, String method,
			String status, String q, Boolean withDue, int page, int size) {
		boolean cost = CurrentActor.hasPermission(Permissions.COST_VIEW);
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("s.branch_id"));
		List<Object> args = new ArrayList<>();
		if (from != null) {
			where.append(" and s.business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and s.business_date <= ?");
			args.add(Date.valueOf(to));
		}
		if (branchId != null) {
			where.append(" and s.branch_id = ?");
			args.add(branchId);
		}
		if (userId != null) {
			where.append(" and s.user_id = ?");
			args.add(userId);
		}
		if (customerId != null) {
			where.append(" and s.customer_id = ?");
			args.add(customerId);
		}
		if (method != null && !method.isBlank()) {
			where.append(" and exists (select 1 from sale_payment p where p.sale_id = s.id and p.method_key = ?)");
			args.add(method.trim().toUpperCase());
		}
		if (status != null && !status.isBlank()) {
			where.append(" and s.status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (q != null && !q.isBlank()) {
			where.append(" and (upper(s.number) like ? or lower(coalesce(s.customer_name, '')) like ? or s.customer_phone like ?)");
			args.add("%" + q.trim().toUpperCase() + "%");
			args.add("%" + q.trim().toLowerCase() + "%");
			args.add("%" + q.trim() + "%");
		}
		if (Boolean.TRUE.equals(withDue)) {
			where.append(" and s.due > 0 and s.status = 'POSTED'");
		}
		Map<String, Object> totals = jdbc.queryForMap("""
				select count(*) as n, coalesce(sum(total) filter (where status = 'POSTED'), 0) as amount,
				       coalesce(sum(due) filter (where status = 'POSTED'), 0) as due from sale s""" + where,
				args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<SaleListItem> items = jdbc.query("""
				select s.*, b.name as branch_name, (select count(*) from sale_line l where l.sale_id = s.id) as n,
				       (select coalesce(sum(cost_restocked), 0) from sale_return r where r.sale_id = s.id) as rc
				from sale s join branch b on b.id = s.branch_id""" + where + " order by s.id desc limit ? offset ?",
				(rs, i) -> {
					BigDecimal profit = "CANCELLED".equals(rs.getString("status")) ? BigDecimal.ZERO
							: rs.getBigDecimal("total").subtract(rs.getBigDecimal("tax")).subtract(rs.getBigDecimal("returned"))
								.subtract(rs.getBigDecimal("cost_total").subtract(rs.getBigDecimal("rc")));
					return new SaleListItem(rs.getLong("id"), rs.getString("number"), rs.getString("branch_name"),
							rs.getString("customer_name"), rs.getString("actor_name"), rs.getString("status"),
							Amounts.text(rs.getBigDecimal("total")), Amounts.text(rs.getBigDecimal("paid")),
							Amounts.text(rs.getBigDecimal("due")), cost ? Amounts.text(profit) : null, rs.getInt("n"),
							rs.getObject("business_date", LocalDate.class), rs.getTimestamp("at").toInstant());
				}, pageArgs.toArray());
		return new SaleList(items, page, size, ((Number) totals.get("n")).longValue(),
				Amounts.text((BigDecimal) totals.get("amount")), Amounts.text((BigDecimal) totals.get("due")));
	}

	@Transactional(readOnly = true)
	public ReceiptView receipt(long id) {
		SaleView sale = get(id);
		Map<String, Object> s = jdbc.queryForMap("select * from shop_settings limit 1");
		BigDecimal savings = BigDecimal.ZERO;
		for (SaleLineView l : sale.lines()) {
			BigDecimal listAmount = new BigDecimal(l.listPrice()).multiply(new BigDecimal(l.qty()));
			savings = savings.add(listAmount.subtract(new BigDecimal(l.net())));
		}
		return new ReceiptView((String) s.get("business_name"), (String) s.get("urdu_name"), (String) s.get("address"),
				(String) s.get("phones"), (String) s.get("ntn"), (String) s.get("strn"),
				s.get("logo_file_id") == null ? null : ((Number) s.get("logo_file_id")).longValue(),
				(String) s.get("receipt_header"), (String) s.get("receipt_footer"), (String) s.get("return_policy"),
				(String) s.get("receipt_size"), (String) s.get("receipt_language"),
				(Boolean) s.get("receipt_show_barcode"), (Boolean) s.get("receipt_show_tax"),
				(Boolean) s.get("receipt_show_savings"), Amounts.text(savings.max(BigDecimal.ZERO)), sale);
	}

	/** Open invoices of a customer, oldest first (for receiving payments). */
	@Transactional(readOnly = true)
	public PageResponse<SaleListItem> openInvoices(long customerId, int page, int size) {
		if (jdbc.queryForList("select id from customer where id = ?", Long.class, customerId).isEmpty()) {
			throw ApiException.notFound("Customer");
		}
		SaleList list = list(null, null, null, null, customerId, null, "POSTED", null, true, page, size);
		return new PageResponse<>(list.items(), page, size, list.total());
	}
}
