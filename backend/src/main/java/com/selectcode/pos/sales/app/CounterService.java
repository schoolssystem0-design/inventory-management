package com.selectcode.pos.sales.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.catalog.app.ProductCatalog.UnitRef;
import com.selectcode.pos.customers.app.CustomerLedger;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.sales.app.SalesService.PaymentInput;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.SalesService.SaleLineInput;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rest of the counter: held bills (POS-04), quotations that turn into invoices (SAL-06, HRD-02) and customer
 * payments that clear the oldest invoices first (CUS-03).
 */
@Service
public class CounterService {

	// ---- held sales

	/** @param cart the till's cart as the app keeps it (lines, customer, discounts) */
	public record HoldInput(Long branchId, String label, JsonNode cart) {
	}

	public record HeldSaleView(long id, long branchId, String label, String actorName, Instant createdAt,
			JsonNode cart) {
	}

	// ---- quotations

	public record QuotationLineInput(Long productId, Long unitId, String qty, String price, String discount) {
	}

	public record QuotationInput(Long branchId, Long customerId, String customerName, String customerPhone,
			LocalDate validUntil, String billDiscount, String note, List<QuotationLineInput> lines) {
	}

	public record QuotationLineView(long productId, String productName, Long unitId, String unitName, String qty,
			String price, String discount, String net, String tax, String total) {
	}

	public record QuotationView(long id, String number, long branchId, Long customerId, String customerName,
			String customerPhone, LocalDate validUntil, boolean expired, String status, String subtotal,
			String discount, String tax, String total, String note, Long convertedSaleId, String actorName,
			LocalDate date, List<QuotationLineView> lines) {
	}

	public record ConvertQuotation(Long tillSessionId, List<PaymentInput> payments) {
	}

	// ---- customer payments

	public record CustomerPaymentInput(Long customerId, String amount, String method, String reference,
			Long tillSessionId, String note) {
	}

	public record PaymentAllocation(long saleId, String saleNumber, LocalDate saleDate, String amount) {
	}

	public record CustomerPaymentView(long id, String number, long customerId, String customerName, String amount,
			String method, String reference, String unallocated, String balanceAfter, String actorName,
			LocalDate date, Instant at, List<PaymentAllocation> allocations) {
	}

	private final JdbcTemplate jdbc;

	private final ProductCatalog catalog;

	private final CustomerLedger ledger;

	private final SalesService sales;

	private final SaleQueries saleQueries;

	private final TillService tills;

	private final BranchAccess branches;

	private final ShopConfigService numbering;

	private final Idempotency idempotency;

	private final AuditService audit;

	private final ShopTime time;

	private final JsonMapper json;

	CounterService(JdbcTemplate jdbc, ProductCatalog catalog, CustomerLedger ledger, SalesService sales,
			SaleQueries saleQueries, TillService tills, BranchAccess branches, ShopConfigService numbering,
			Idempotency idempotency, AuditService audit, ShopTime time, JsonMapper json) {
		this.jdbc = jdbc;
		this.catalog = catalog;
		this.ledger = ledger;
		this.sales = sales;
		this.saleQueries = saleQueries;
		this.tills = tills;
		this.branches = branches;
		this.numbering = numbering;
		this.idempotency = idempotency;
		this.audit = audit;
		this.time = time;
		this.json = json;
	}

	// ------------------------------------------------------------------ held sales

	@Transactional
	public HeldSaleView hold(HoldInput in) {
		long branchId = in.branchId() == null ? branches.defaultBranchId() : in.branchId();
		branches.requireActive(branchId);
		if (in.cart() == null || in.cart().isNull()) {
			throw ApiException.field("cart", "required", "Nothing to hold");
		}
		String cart = json.writeValueAsString(in.cart());
		if (cart.length() > 200_000) {
			throw ApiException.field("cart", "too_large", "This bill is too large to hold");
		}
		String label = in.label() == null || in.label().isBlank() ? "Bill " + time.now().toString().substring(11, 16)
				: in.label().trim();
		long id = jdbc.queryForObject("""
				insert into held_sale (shop_id, branch_id, user_id, actor_name, label, cart) values (?, ?, ?, ?, ?, ?::jsonb)
				returning id""", Long.class, TenantContext.requireShopId(), branchId, TillService.userId(),
				CurrentActor.name(), label.length() > 100 ? label.substring(0, 100) : label, cart);
		return held(id);
	}

	@Transactional(readOnly = true)
	public List<HeldSaleView> heldSales(Long branchId) {
		String where = " where 1=1" + branches.filter("branch_id") + (branchId == null ? "" : " and branch_id = " + branchId);
		return jdbc.queryForList("select id from held_sale" + where + " order by created_at", Long.class)
			.stream()
			.map(this::held)
			.toList();
	}

	@Transactional(readOnly = true)
	public HeldSaleView held(long id) {
		return jdbc.query("select * from held_sale where id = ?" + branches.filter("branch_id"),
				(rs, i) -> new HeldSaleView(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("label"),
						rs.getString("actor_name"), rs.getTimestamp("created_at").toInstant(),
						json.readTree(rs.getString("cart"))),
				id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Held bill"));
	}

	/** Resuming (or discarding) a held bill removes it. */
	@Transactional
	public void release(long id) {
		held(id);
		jdbc.update("delete from held_sale where id = ?", id);
	}

	// ------------------------------------------------------------------ quotations

	@Transactional
	public QuotationView quote(QuotationInput in) {
		long branchId = in.branchId() == null ? branches.defaultBranchId() : in.branchId();
		branches.requireActive(branchId);
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}
		Map<String, Object> settings = jdbc.queryForMap("select tax_mode, tax_rate from shop_settings where shop_id = ?",
				TenantContext.requireShopId());
		String taxMode = (String) settings.get("tax_mode");
		CustomerLedger.Account account = in.customerId() == null ? null : ledger.account(in.customerId(), false);
		LocalDate today = time.today();
		LocalDate validUntil = in.validUntil() == null ? today.plusDays(7) : in.validUntil();
		if (validUntil.isBefore(today)) {
			throw ApiException.field("validUntil", "past", "Validity must be today or later");
		}
		record Planned(ProductRef product, UnitRef unit, BigDecimal qty, BigDecimal price, BigDecimal discount,
				BigDecimal rate) {
		}
		List<Planned> planned = new ArrayList<>();
		boolean override = CurrentActor.hasPermission(Permissions.POS_PRICE_OVERRIDE);
		for (int i = 0; i < in.lines().size(); i++) {
			QuotationLineInput line = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (line.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(line.productId());
			UnitRef unit = catalog.unit(product, line.unitId());
			BigDecimal qty = Amounts.parseQty(line.qty(), f + ".qty");
			ProductCatalog.checkQty(product, unit, qty, f + ".qty");
			BigDecimal price = Amounts.parseMoney(line.price(), f + ".price");
			if (price == null || (!override && price.compareTo(unit.price()) != 0)) {
				price = unit.price();
			}
			BigDecimal discount = Amounts.nz(Amounts.parseMoney(line.discount(), f + ".discount"));
			if (discount.signum() > 0 && !CurrentActor.hasPermission(Permissions.POS_DISCOUNT)) {
				discount = BigDecimal.ZERO;
			}
			planned.add(new Planned(product, unit, qty, price, Amounts.money(discount),
					product.taxRate() != null ? product.taxRate() : (BigDecimal) settings.get("tax_rate")));
		}
		BigDecimal subtotal = BigDecimal.ZERO;
		BigDecimal lineDiscount = BigDecimal.ZERO;
		List<BigDecimal> amounts = new ArrayList<>();
		for (Planned p : planned) {
			BigDecimal gross = SaleMath.lineAmount(p.qty(), p.price());
			subtotal = subtotal.add(gross);
			lineDiscount = lineDiscount.add(p.discount());
			amounts.add(gross.subtract(p.discount()));
		}
		BigDecimal billDiscount = CurrentActor.hasPermission(Permissions.POS_DISCOUNT)
				? Amounts.nz(Amounts.parseMoney(in.billDiscount(), "billDiscount")) : BigDecimal.ZERO.setScale(2);
		List<BigDecimal> shares = SaleMath.spread(amounts, billDiscount);
		String number = numbering.nextNumber("QUOTATION", branchId);
		long id = jdbc.queryForObject("""
				insert into quotation (shop_id, number, branch_id, customer_id, customer_name, customer_phone, valid_until,
				                       subtotal, line_discount, bill_discount, total, note, created_by, actor_name,
				                       business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?) returning id""", Long.class,
				TenantContext.requireShopId(), number, branchId, in.customerId(),
				account == null ? SalesService.trim(in.customerName(), 200) : account.name(),
				account == null ? SalesService.trim(in.customerPhone(), 30) : account.phone(), Date.valueOf(validUntil),
				subtotal, lineDiscount, billDiscount, SalesService.trim(in.note(), 500), TillService.userId(),
				CurrentActor.name(), Date.valueOf(today));
		BigDecimal tax = BigDecimal.ZERO;
		BigDecimal total = BigDecimal.ZERO;
		for (int i = 0; i < planned.size(); i++) {
			Planned p = planned.get(i);
			BigDecimal net = amounts.get(i).subtract(shares.get(i));
			BigDecimal lineTax = SaleMath.tax(taxMode, net, p.rate());
			BigDecimal lineTotal = SaleMath.lineTotal(taxMode, net, lineTax);
			tax = tax.add(lineTax);
			total = total.add(lineTotal);
			jdbc.update("""
					insert into quotation_line (shop_id, quotation_id, line_no, product_id, product_name, unit_id,
					                            unit_name, unit_factor, qty, price, discount, bill_share, net, tax_rate,
					                            tax, total)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), id, i + 1,
					p.product().id(), p.product().name(), p.unit().unitId(), p.unit().name(), p.unit().factor(), p.qty(),
					p.price(), p.discount(), shares.get(i), net, p.rate(), lineTax, lineTotal);
		}
		jdbc.update("update quotation set tax = ?, total = ? where id = ?", tax, total, id);
		audit.record("QUOTATION_CREATED", "quotation", id, null, Map.of("number", number, "total", total.toPlainString()));
		return quotation(id);
	}

	/**
	 * Turns an open, valid quotation into an invoice (TC-SAL-07): the quoted prices are kept and stock is checked now.
	 */
	@Transactional
	public SaleQueries.SaleView convert(long id, ConvertQuotation in, String idempotencyKey,
			ApprovalRequest approval) {
		QuotationView q = quotation(id);
		jdbc.queryForList("select id from quotation where id = ? for update", id);
		var earlier = idempotency.claim("SALE", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return saleQueries.get(earlier.get());
		}
		if (!"OPEN".equals(q.status())) {
			throw ApiException.rule("quotation_closed", "This quotation is " + q.status().toLowerCase());
		}
		if (q.expired()) {
			throw ApiException.rule("quotation_expired", "This quotation expired on " + q.validUntil());
		}
		List<SaleLineInput> lines = new ArrayList<>();
		for (QuotationLineView l : q.lines()) {
			lines.add(new SaleLineInput(l.productId(), l.unitId(), l.qty(), l.price(), l.discount(), null, null, null));
		}
		BigDecimal billDiscount = jdbc.queryForObject("select bill_discount from quotation where id = ?",
				BigDecimal.class, id);
		SaleInput sale = new SaleInput(in == null ? null : in.tillSessionId(), q.customerId(), null, q.customerName(),
				q.customerPhone(), null, lines, Amounts.text(billDiscount), null, in == null ? null : in.payments(),
				"From quotation " + q.number(), id, null, null);
		// The quoted prices were approved when the quotation was made, so price changes do not need approval again.
		long saleId = sales.postInternal(sale, approval, List.of(), null, true);
		idempotency.complete("SALE", idempotencyKey, saleId);
		return saleQueries.get(saleId);
	}

	@Transactional
	public QuotationView cancelQuotation(long id) {
		QuotationView q = quotation(id);
		if (!"OPEN".equals(q.status())) {
			throw ApiException.rule("quotation_closed", "This quotation is " + q.status().toLowerCase());
		}
		jdbc.update("update quotation set status = 'CANCELLED' where id = ?", id);
		return quotation(id);
	}

	@Transactional(readOnly = true)
	public QuotationView quotation(long id) {
		Map<String, Object> q = jdbc.queryForList("select * from quotation where id = ?" + branches.filter("branch_id"), id)
			.stream()
			.findFirst()
			.orElseThrow(() -> ApiException.notFound("Quotation"));
		List<QuotationLineView> lines = jdbc.query("select * from quotation_line where quotation_id = ? order by line_no",
				(rs, i) -> new QuotationLineView(rs.getLong("product_id"), rs.getString("product_name"),
						(Long) rs.getObject("unit_id"), rs.getString("unit_name"), Amounts.qtyText(rs.getBigDecimal("qty")),
						Amounts.text(rs.getBigDecimal("price")), Amounts.text(rs.getBigDecimal("discount")),
						Amounts.text(rs.getBigDecimal("net")), Amounts.text(rs.getBigDecimal("tax")),
						Amounts.text(rs.getBigDecimal("total"))),
				id);
		LocalDate valid = ((Date) q.get("valid_until")).toLocalDate();
		return new QuotationView(id, (String) q.get("number"), ((Number) q.get("branch_id")).longValue(),
				q.get("customer_id") == null ? null : ((Number) q.get("customer_id")).longValue(),
				(String) q.get("customer_name"), (String) q.get("customer_phone"), valid,
				valid.isBefore(time.today()), (String) q.get("status"), Amounts.text((BigDecimal) q.get("subtotal")),
				Amounts.text(((BigDecimal) q.get("line_discount")).add((BigDecimal) q.get("bill_discount"))),
				Amounts.text((BigDecimal) q.get("tax")), Amounts.text((BigDecimal) q.get("total")),
				(String) q.get("note"),
				q.get("converted_sale_id") == null ? null : ((Number) q.get("converted_sale_id")).longValue(),
				(String) q.get("actor_name"), ((Date) q.get("business_date")).toLocalDate(), lines);
	}

	@Transactional(readOnly = true)
	public PageResponse<QuotationView> quotations(String status, Long customerId, int page, int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (status != null && !status.isBlank()) {
			where.append(" and status = ?");
			args.add(status.trim().toUpperCase());
		}
		if (customerId != null) {
			where.append(" and customer_id = ?");
			args.add(customerId);
		}
		long total = jdbc.queryForObject("select count(*) from quotation" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from quotation" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::quotation).toList(), page, size, total);
	}

	// ------------------------------------------------------------------ customer payments

	/** Receives money from a customer; it clears the oldest open invoices first, the rest stays as an advance. */
	@Transactional
	public CustomerPaymentView receive(CustomerPaymentInput in, String idempotencyKey) {
		var earlier = idempotency.claim("CUSTOMER_PAYMENT", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return payment(earlier.get());
		}
		long id = receiveInternal(in, null);
		idempotency.complete("CUSTOMER_PAYMENT", idempotencyKey, id);
		return payment(id);
	}

	/**
	 * Books a customer payment in the caller's transaction. {@code firstSaleId} is cleared before the oldest invoices
	 * (an installment pays its own invoice first).
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	long receiveInternal(CustomerPaymentInput in, Long firstSaleId) {
		if (in.customerId() == null) {
			throw ApiException.field("customerId", "required", "Choose the customer");
		}
		CustomerLedger.Account account = ledger.account(in.customerId(), true);
		BigDecimal amount = Amounts.requireMoney(in.amount(), "amount");
		if (amount.signum() <= 0) {
			throw ApiException.field("amount", "invalid", "Enter an amount above zero");
		}
		String method = in.method() == null || in.method().isBlank() ? "CASH" : in.method().trim().toUpperCase();
		if ("ACCOUNT".equals(method) || "VOUCHER".equals(method)) {
			throw ApiException.field("method", "invalid", "Choose cash, card, bank or a wallet");
		}
		Integer ok = jdbc.queryForObject("select count(*) from payment_method where method_key = ? and active",
				Integer.class, method);
		if (ok == null || ok == 0) {
			throw ApiException.field("method", "invalid", "This payment method is not switched on");
		}
		Map<String, Object> session = null;
		if ("CASH".equals(method) || in.tillSessionId() != null) {
			session = tills.requireOpenSession(in.tillSessionId(), null);
		}
		long branchId = session != null ? ((Number) session.get("branch_id")).longValue() : branches.defaultBranchId();
		Long sessionId = session == null ? null : ((Number) session.get("id")).longValue();
		LocalDate today = time.today();
		String number = numbering.nextNumber("CUSTOMER_PAYMENT", branchId);
		long id = jdbc.queryForObject("""
				insert into customer_payment (shop_id, number, customer_id, branch_id, till_session_id, amount, method_key,
				                              reference, note, created_by, actor_name, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, TenantContext.requireShopId(),
				number, account.id(), branchId, sessionId, amount, method, SalesService.trim(in.reference(), 100),
				SalesService.trim(in.note(), 500), TillService.userId(), CurrentActor.name(), Date.valueOf(today));
		BigDecimal left = amount;
		for (Map<String, Object> sale : jdbc.queryForList("""
				select id, due from sale where customer_id = ? and status = 'POSTED' and due > 0
				order by (id = ?) desc, business_date, id for update""", account.id(), firstSaleId == null ? 0L : firstSaleId)) {
			if (left.signum() <= 0) {
				break;
			}
			BigDecimal take = left.min((BigDecimal) sale.get("due"));
			long saleId = ((Number) sale.get("id")).longValue();
			jdbc.update("update sale set paid = paid + ? where id = ?", take, saleId);
			jdbc.update("insert into customer_payment_allocation (shop_id, payment_id, sale_id, amount) values (?, ?, ?, ?)",
					TenantContext.requireShopId(), id, saleId, take);
			sales.insertPayment(saleId, "RECEIPT", method, take, null, null, number, sessionId, branchId,
					"CUSTOMER_PAYMENT", id, today);
			left = left.subtract(take);
		}
		jdbc.update("update customer_payment set unallocated = ? where id = ?", left, id);
		ledger.post(account.id(), "CUSTOMER_PAYMENT", id, number, "Payment received " + number + " ("
				+ method.toLowerCase() + ")", null, amount);
		audit.record("CUSTOMER_PAID", "customer_payment", id, null,
				Map.of("number", number, "customer", account.name(), "amount", amount.toPlainString()));
		return id;
	}

	@Transactional(readOnly = true)
	public CustomerPaymentView payment(long id) {
		Map<String, Object> p = jdbc.queryForList("""
				select p.*, c.name as customer_name, c.balance from customer_payment p join customer c on c.id = p.customer_id
				where p.id = ?""" + branches.filter("p.branch_id"), id).stream().findFirst()
			.orElseThrow(() -> ApiException.notFound("Customer payment"));
		List<PaymentAllocation> allocations = jdbc.query("""
				select a.sale_id, s.number, s.business_date, a.amount from customer_payment_allocation a
				join sale s on s.id = a.sale_id where a.payment_id = ? order by s.business_date, s.id""",
				(rs, i) -> new PaymentAllocation(rs.getLong("sale_id"), rs.getString("number"),
						rs.getObject("business_date", LocalDate.class), Amounts.text(rs.getBigDecimal("amount"))),
				id);
		return new CustomerPaymentView(id, (String) p.get("number"), ((Number) p.get("customer_id")).longValue(),
				(String) p.get("customer_name"), Amounts.text((BigDecimal) p.get("amount")), (String) p.get("method_key"),
				(String) p.get("reference"), Amounts.text((BigDecimal) p.get("unallocated")),
				Amounts.text((BigDecimal) p.get("balance")), (String) p.get("actor_name"),
				((Date) p.get("business_date")).toLocalDate(), ((Timestamp) p.get("at")).toInstant(), allocations);
	}

	@Transactional(readOnly = true)
	public PageResponse<CustomerPaymentView> payments(Long customerId, LocalDate from, LocalDate to, int page,
			int size) {
		StringBuilder where = new StringBuilder(" where 1=1" + branches.filter("branch_id"));
		List<Object> args = new ArrayList<>();
		if (customerId != null) {
			where.append(" and customer_id = ?");
			args.add(customerId);
		}
		if (from != null) {
			where.append(" and business_date >= ?");
			args.add(Date.valueOf(from));
		}
		if (to != null) {
			where.append(" and business_date <= ?");
			args.add(Date.valueOf(to));
		}
		long total = jdbc.queryForObject("select count(*) from customer_payment" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<Long> ids = jdbc.queryForList("select id from customer_payment" + where + " order by id desc limit ? offset ?",
				Long.class, pageArgs.toArray());
		return new PageResponse<>(ids.stream().map(this::payment).toList(), page, size, total);
	}
}
