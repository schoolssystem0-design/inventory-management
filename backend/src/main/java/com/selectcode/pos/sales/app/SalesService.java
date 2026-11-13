package com.selectcode.pos.sales.app;

import com.selectcode.pos.catalog.app.ProductCatalog;
import com.selectcode.pos.catalog.app.ProductCatalog.BundlePart;
import com.selectcode.pos.catalog.app.ProductCatalog.ProductRef;
import com.selectcode.pos.catalog.app.ProductCatalog.UnitRef;
import com.selectcode.pos.customers.app.CustomerLedger;
import com.selectcode.pos.customers.app.CustomerService;
import com.selectcode.pos.customers.app.CustomerService.CustomerInput;
import com.selectcode.pos.iam.app.ApprovalService;
import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.shared.audit.AuditService;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.ShopFeatures;
import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.Idempotency;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.stock.app.StockService;
import com.selectcode.pos.stock.app.StockService.BatchPick;
import com.selectcode.pos.stock.app.StockService.DocRef;
import com.selectcode.pos.stock.app.StockService.Move;
import com.selectcode.pos.stock.app.StockService.MoveType;
import com.selectcode.pos.stock.app.StockService.SerialUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posting a sale (POS-01..05, SAL, NFR-03/04/06). One transaction: the invoice, its lines, stock out (rows locked in
 * a fixed order, serials locked one by one, batches picked earliest-expiry-first), the cost at the time of sale on each
 * line, the payments, and the customer's ledger. A retried request with the same {@code Idempotency-Key} returns the
 * first sale (TC-POS-06).
 *
 * <p>Amounts: line amount = qty × price, less the line discount; the bill discount is spread over the lines in
 * proportion with the remainder on the last line; tax per line from the shop's tax mode. Over-limit actions (bigger
 * discount, price changes, below-cost or minimum price, credit above the limit) need a manager's approval (ACC-04).
 */
@Service
public class SalesService {

	/**
	 * @param discount line discount amount; or {@code discountPercent}
	 * @param serials IMEI/serial of each unit for serial items (qty = number of serials)
	 * @param batchId sell from this batch instead of the earliest expiry (MED-03 override)
	 */
	public record SaleLineInput(Long productId, Long unitId, String qty, String price, String discount,
			String discountPercent, Long batchId, List<String> serials, String note) {

		/** @param note for the kitchen, e.g. "no onions" (fast food) */
		public SaleLineInput(Long productId, Long unitId, String qty, String price, String discount,
				String discountPercent, Long batchId, List<String> serials) {
			this(productId, unitId, qty, price, discount, discountPercent, batchId, serials, null);
		}
	}

	/**
	 * @param method CASH, CARD, BANK, JAZZCASH, EASYPAISA, CHEQUE, CREDIT_NOTE (reference = note number) or ACCOUNT
	 *     (the rest goes on the customer's account)
	 */
	public record PaymentInput(String method, String amount, String reference) {
	}

	/** Quick-add of a customer at the till by phone (POS-05). */
	public record QuickCustomer(String name, String phone) {
	}

	/**
	 * @param couponCode coupon typed at the till (module promotions)
	 * @param prescription for RX and CONTROLLED medicines (module prescriptions)
	 */
	public record SaleInput(Long tillSessionId, Long customerId, QuickCustomer newCustomer, String customerName,
			String customerPhone, Long salesmanId, List<SaleLineInput> lines, String billDiscount,
			String billDiscountPercent, List<PaymentInput> payments, String note, Long quotationId, String couponCode,
			PrescriptionService.PrescriptionInput prescription, String orderType, String tableNo) {

		/** @param orderType DINE_IN, TAKEAWAY or DELIVERY (fast food); {@code tableNo} for dine-in */
		public SaleInput(Long tillSessionId, Long customerId, QuickCustomer newCustomer, String customerName,
				String customerPhone, Long salesmanId, List<SaleLineInput> lines, String billDiscount,
				String billDiscountPercent, List<PaymentInput> payments, String note, Long quotationId,
				String couponCode, PrescriptionService.PrescriptionInput prescription) {
			this(tillSessionId, customerId, newCustomer, customerName, customerPhone, salesmanId, lines, billDiscount,
					billDiscountPercent, payments, note, quotationId, couponCode, prescription, null, null);
		}
	}

	/** Internal payment line (exchanges pay with the returned goods' value). */
	record Tender(String method, BigDecimal amount, String reference) {
	}

	private final JdbcTemplate jdbc;

	private final StockService stock;

	private final ProductCatalog catalog;

	private final CustomerLedger ledger;

	private final CustomerService customers;

	private final TillService tills;

	private final ShopConfigService numbering;

	private final ApprovalService approvals;

	private final Idempotency idempotency;

	private final ShopFeatures features;

	private final AuditService audit;

	private final ShopTime time;

	private final SaleQueries queries;

	private final PromotionService promotions;

	private final LoyaltyService loyalty;

	private final PrescriptionService prescriptions;

	private final KitchenService kitchen;

	SalesService(JdbcTemplate jdbc, StockService stock, ProductCatalog catalog, CustomerLedger ledger,
			CustomerService customers, TillService tills, ShopConfigService numbering, ApprovalService approvals,
			Idempotency idempotency, ShopFeatures features, AuditService audit, ShopTime time, SaleQueries queries,
			PromotionService promotions, LoyaltyService loyalty, PrescriptionService prescriptions,
			KitchenService kitchen) {
		this.kitchen = kitchen;
		this.promotions = promotions;
		this.loyalty = loyalty;
		this.prescriptions = prescriptions;
		this.jdbc = jdbc;
		this.stock = stock;
		this.catalog = catalog;
		this.ledger = ledger;
		this.customers = customers;
		this.tills = tills;
		this.numbering = numbering;
		this.approvals = approvals;
		this.idempotency = idempotency;
		this.features = features;
		this.audit = audit;
		this.time = time;
		this.queries = queries;
	}

	@Transactional
	public SaleQueries.SaleView post(SaleInput in, String idempotencyKey, ApprovalRequest approval) {
		var earlier = idempotency.claim("SALE", Idempotency.require(idempotencyKey));
		if (earlier.isPresent()) {
			return queries.get(earlier.get());
		}
		if (in.quotationId() != null) {
			throw ApiException.field("quotationId", "use_convert", "Convert the quotation instead");
		}
		long id = postInternal(in, approval, List.of(), null);
		idempotency.complete("SALE", idempotencyKey, id);
		return queries.get(id);
	}

	/**
	 * Posts a sale in the caller's transaction and returns its id. {@code extraTenders} pay part of it without money
	 * changing hands (the value of goods returned in an exchange).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	long postInternal(SaleInput in, ApprovalRequest approval, List<Tender> extraTenders, Long exchangeOfReturnId) {
		return postInternal(in, approval, extraTenders, exchangeOfReturnId, false);
	}

	/**
	 * @param pricesAgreed prices were set earlier by someone allowed to (repair job, challan): no price-change approval
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	long postInternal(SaleInput in, ApprovalRequest approval, List<Tender> extraTenders, Long exchangeOfReturnId,
			boolean pricesAgreed) {
		Map<String, Object> session = tills.requireOpenSession(in.tillSessionId(), null);
		long sessionId = ((Number) session.get("id")).longValue();
		long branchId = ((Number) session.get("branch_id")).longValue();
		LocalDate today = time.today();
		Map<String, Object> settings = jdbc.queryForMap("""
				select tax_mode, tax_rate, discount_limit_pct from shop_settings where shop_id = ?""",
				TenantContext.requireShopId());
		String taxMode = (String) settings.get("tax_mode");
		BigDecimal shopTax = (BigDecimal) settings.get("tax_rate");
		BigDecimal discountLimit = (BigDecimal) settings.get("discount_limit_pct");
		if (in.lines() == null || in.lines().isEmpty()) {
			throw ApiException.field("lines", "required", "Add at least one item");
		}

		// ---- customer
		Long customerId = in.customerId();
		if (customerId == null && in.newCustomer() != null && in.newCustomer().phone() != null
				&& !in.newCustomer().phone().isBlank()) {
			customerId = customers.byPhone(in.newCustomer().phone())
				.map(CustomerService.CustomerView::id)
				.orElseGet(() -> customers.create(new CustomerInput(
						in.newCustomer().name() == null || in.newCustomer().name().isBlank() ? "Walk-in customer"
								: in.newCustomer().name(),
						in.newCustomer().phone(), null, null, null, null, null, null, null, null)).id());
		}
		CustomerLedger.Account account = customerId == null ? null : ledger.account(customerId, true);
		if (account != null && !account.active()) {
			throw ApiException.rule("customer_inactive", account.name() + " is switched off");
		}

		// ---- lines: products, units, prices, discounts
		Set<String> needs = new LinkedHashSet<>();
		// Prices of a quotation being converted were set (and approved) when it was made (TC-SAL-07).
		boolean canOverride = CurrentActor.hasPermission(Permissions.POS_PRICE_OVERRIDE) || in.quotationId() != null
				|| pricesAgreed;
		List<Line> lines = new ArrayList<>();
		for (int i = 0; i < in.lines().size(); i++) {
			SaleLineInput input = in.lines().get(i);
			String f = "lines[" + i + "]";
			if (input.productId() == null) {
				throw ApiException.field(f + ".productId", "required", "Choose a product");
			}
			ProductRef product = catalog.require(input.productId());
			if (!product.active()) {
				throw ApiException.rule("product_inactive", product.name() + " is no longer sold");
			}
			if ("VARIANT_PARENT".equals(product.kind())) {
				throw ApiException.rule("choose_variant", "Choose the size or colour of " + product.name());
			}
			UnitRef unit = catalog.unit(product, input.unitId());
			BigDecimal qty;
			List<String> serials = input.serials() == null ? List.of()
					: input.serials().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
			if (product.serial()) {
				if (serials.isEmpty()) {
					throw ApiException.field(f + ".serials", "required", "Scan the IMEI/serial of " + product.name());
				}
				if (new LinkedHashSet<>(serials).size() != serials.size()) {
					throw ApiException.field(f + ".serials", "duplicate", "The same IMEI is scanned twice");
				}
				qty = BigDecimal.valueOf(serials.size());
			}
			else {
				qty = Amounts.parseQty(input.qty(), f + ".qty");
				ProductCatalog.checkQty(product, unit, qty, f + ".qty");
			}
			BigDecimal listPrice = unit.price();
			BigDecimal price = Amounts.parseMoney(input.price(), f + ".price");
			if (price == null) {
				price = listPrice;
			}
			else if (price.compareTo(listPrice) != 0 && !canOverride) {
				needs.add(Permissions.POS_PRICE_OVERRIDE + "|price change on " + product.name());
			}
			BigDecimal basePrice = price.divide(unit.factor(), 2, RoundingMode.HALF_UP);
			if (product.minPrice() != null && basePrice.compareTo(product.minPrice()) < 0) {
				needs.add(Permissions.POS_PRICE_OVERRIDE + "|price below the lowest allowed for " + product.name());
			}
			BigDecimal gross = SaleMath.lineAmount(qty, price);
			BigDecimal discount = Amounts.parseMoney(input.discount(), f + ".discount");
			BigDecimal pct = Amounts.parsePercent(input.discountPercent(), f + ".discountPercent");
			if (discount == null) {
				discount = pct == null ? BigDecimal.ZERO : Amounts.money(gross.multiply(pct).divide(SaleMath.HUNDRED));
			}
			discount = Amounts.money(discount);
			if (discount.compareTo(gross) > 0) {
				throw ApiException.field(f + ".discount", "too_large", "Discount is more than the line amount");
			}
			BigDecimal rate = product.taxRate() != null ? product.taxRate() : shopTax;
			Line line = new Line(i, product, unit, qty, price, listPrice, gross, discount, rate, serials, input.batchId());
			line.note = trim(input.note(), 200);
			lines.add(line);
		}

		BigDecimal subtotal = lines.stream().map(Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal manualLineDiscount = lines.stream().map(Line::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal billDiscount = Amounts.parseMoney(in.billDiscount(), "billDiscount");
		BigDecimal billPct = Amounts.parsePercent(in.billDiscountPercent(), "billDiscountPercent");
		boolean billTyped = billDiscount != null && billDiscount.signum() > 0 || billPct != null && billPct.signum() > 0;

		// ---- offers and coupons (SUP-05, XSL-02): they never need approval and are not counted in the limit
		// Converted documents (quotation, repair job, challan) keep the prices agreed on them.
		PromotionEngine.Applied billOffer = pricesAgreed ? new PromotionEngine.Applied(null, null, BigDecimal.ZERO.setScale(2))
				: applyOffers(lines, billTyped, in.couponCode(), today);
		BigDecimal lineDiscount = lines.stream().map(Line::discount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal afterLine = subtotal.subtract(lineDiscount);
		if (billDiscount == null) {
			billDiscount = billPct == null ? BigDecimal.ZERO
					: Amounts.money(afterLine.multiply(billPct).divide(SaleMath.HUNDRED));
		}
		billDiscount = Amounts.money(billDiscount);
		if (billDiscount.compareTo(afterLine) > 0) {
			throw ApiException.field("billDiscount", "too_large", "Discount is more than the bill");
		}
		BigDecimal manualBillDiscount = billDiscount;
		billDiscount = billDiscount.add(billOffer.amount());
		BigDecimal promoTotal = lineDiscount.subtract(manualLineDiscount).add(billOffer.amount());
		BigDecimal totalDiscount = manualLineDiscount.add(manualBillDiscount);
		if (totalDiscount.signum() > 0) {
			if (!CurrentActor.hasPermission(Permissions.POS_DISCOUNT)) {
				needs.add(Permissions.POS_DISCOUNT + "|discount");
			}
			else if (SaleMath.percent(totalDiscount, subtotal).compareTo(discountLimit) > 0) {
				needs.add(Permissions.POS_DISCOUNT + "|discount above " + discountLimit.stripTrailingZeros().toPlainString()
						+ "%");
			}
		}
		List<BigDecimal> shares = SaleMath.spread(lines.stream().map(l -> l.gross().subtract(l.discount())).toList(),
				billDiscount);
		BigDecimal tax = BigDecimal.ZERO;
		BigDecimal total = BigDecimal.ZERO;
		for (int i = 0; i < lines.size(); i++) {
			Line l = lines.get(i);
			BigDecimal net = l.gross().subtract(l.discount()).subtract(shares.get(i));
			BigDecimal lineTax = SaleMath.tax(taxMode, net, l.rate());
			l.share = shares.get(i);
			l.net = net;
			l.tax = lineTax;
			l.total = SaleMath.lineTotal(taxMode, net, lineTax);
			tax = tax.add(lineTax);
			total = total.add(l.total);
		}

		// ---- payments
		List<Tender> tenders = new ArrayList<>(extraTenders);
		Set<String> methods = new java.util.HashSet<>(jdbc.queryForList(
				"select method_key from payment_method where active", String.class));
		if (in.payments() != null) {
			for (int i = 0; i < in.payments().size(); i++) {
				PaymentInput p = in.payments().get(i);
				String method = p.method() == null ? "" : p.method().trim().toUpperCase();
				if ("ACCOUNT".equals(method)) {
					continue;
				}
				BigDecimal amount = Amounts.requireMoney(p.amount(), "payments[" + i + "].amount");
				if (amount.signum() == 0) {
					continue;
				}
				if (!"CREDIT_NOTE".equals(method) && !"POINTS".equals(method) && !methods.contains(method)) {
					throw ApiException.field("payments[" + i + "].method", "invalid",
							"Payment method " + method + " is not switched on");
				}
				tenders.add(new Tender(method, amount, p.reference() == null ? null : p.reference().trim()));
			}
		}
		BigDecimal pointsAmount = tenders.stream().filter(t -> "POINTS".equals(t.method())).map(Tender::amount)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		int pointsUsed = 0;
		if (pointsAmount.signum() > 0) {
			if (account == null) {
				throw ApiException.rule("points_no_customer", "Choose the customer whose points are used");
			}
			pointsUsed = loyalty.checkRedeem(account.id(), pointsAmount);
		}
		BigDecimal tendered = tenders.stream().map(Tender::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal change = BigDecimal.ZERO;
		if (tendered.compareTo(total) > 0) {
			BigDecimal excess = tendered.subtract(total);
			BigDecimal cash = tenders.stream().filter(t -> "CASH".equals(t.method())).map(Tender::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			if (cash.compareTo(excess) < 0) {
				throw ApiException.rule("overpaid", "More was paid than the bill by card or wallet; only cash can have change");
			}
			change = excess;
		}
		BigDecimal applied = tendered.subtract(change);
		BigDecimal due = total.subtract(applied);
		if (due.signum() > 0) {
			if (account == null) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "payment_short",
						"Collect Rs " + due.toPlainString() + " more, or choose a customer to put it on their account",
						Map.of("due", Amounts.text(due)));
			}
			if (!methods.contains("ACCOUNT")) {
				throw ApiException.rule("credit_off", "Selling on account is switched off for this shop");
			}
			if (account.exceedsLimit(due)) {
				needs.add(Permissions.CUSTOMERS_MANAGE + "|credit above " + account.name() + "'s limit of Rs "
						+ account.creditLimit().toPlainString());
			}
		}

		// Selling below cost needs approval too (ACC-04); checked on the cost known now, before locking stock.
		for (Line l : lines) {
			BigDecimal cost = indicativeCost(l);
			BigDecimal basePrice = l.net.divide(l.baseQty(), 2, RoundingMode.HALF_UP);
			if (cost.signum() > 0 && basePrice.compareTo(cost) < 0) {
				needs.add(Permissions.POS_PRICE_OVERRIDE + "|selling " + l.product().name() + " below cost");
			}
		}

		// ---- prescriptions (MED-08) and controlled medicines (MED-09)
		PrescriptionService.Schedules schedules = prescriptions
			.schedules(lines.stream().map(l -> l.product().id()).distinct().toList());
		boolean controlled = schedules.any("CONTROLLED");
		if ((controlled || schedules.any("RX") && prescriptions.required()) && in.prescription() == null) {
			String names = lines.stream().filter(l -> schedules.byProduct().containsKey(l.product().id()))
				.map(l -> l.product().name()).distinct().reduce((a, b) -> a + ", " + b).orElse("");
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "prescription_required",
					"Enter the prescription for " + names, Map.of("products", schedules.byProduct().keySet()));
		}
		PrescriptionService.check(in.prescription());
		if (controlled) {
			needs.add(Permissions.PRESCRIPTIONS_APPROVE + "|controlled medicine " + lines.stream()
				.filter(l -> "CONTROLLED".equals(schedules.byProduct().get(l.product().id())))
				.map(l -> l.product().name()).distinct().reduce((a, b) -> a + ", " + b).orElse(""));
		}

		// ---- approvals (before any stock row is locked)
		Long approvedBy = null;
		Long pharmacistId = null;
		List<String> reasons = new ArrayList<>();
		Map<String, List<String>> byPermission = new LinkedHashMap<>();
		for (String need : needs) {
			String[] parts = need.split("\\|", 2);
			byPermission.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
		}
		for (Map.Entry<String, List<String>> e : byPermission.entrySet()) {
			String what = String.join("; ", e.getValue());
			approvedBy = approvals.require(approval, e.getKey(), "Sale: " + what, what);
			if (Permissions.PRESCRIPTIONS_APPROVE.equals(e.getKey())) {
				pharmacistId = approvedBy;
			}
			reasons.add(what);
		}

		// ---- stock: lock serials, pick batches, work out cost (in product order)
		List<Line> byProduct = new ArrayList<>(lines);
		byProduct.sort(Comparator.comparingLong((Line l) -> l.product().id()).thenComparingInt(Line::index));
		boolean warranty = features.isOn(ModuleKey.WARRANTY);
		for (Line l : byProduct) {
			ProductRef p = l.product();
			BigDecimal baseQty = Amounts.qty(l.qty().multiply(l.unit().factor()));
			if (p.serial()) {
				for (String serial : l.serials()) {
					SerialUnit unit = stock.lockSerial(serial, p.id(), branchId, "IN_STOCK");
					l.parts.add(new Part(p.id(), null, unit, BigDecimal.ONE, unit.cost()));
				}
			}
			else if (p.batch()) {
				if (l.batchId() != null) {
					BatchPick chosen = stock.requireSellableBatch(p.id(), l.batchId(), today);
					l.parts.add(new Part(p.id(), chosen.batchId(), null, baseQty,
							chosen.cost() == null ? p.costAvg() : chosen.cost()));
				}
				else {
					for (BatchPick pick : stock.pickBatches(p.id(), branchId, baseQty, today)) {
						l.parts.add(new Part(p.id(), pick.batchId(), null, pick.qty(),
								pick.cost() == null ? p.costAvg() : pick.cost()));
					}
				}
			}
			else if ("BUNDLE".equals(p.kind()) || "RECIPE".equals(p.kind())) {
				// A deal or menu item: the stock items it is made of leave stock (a deal's menu items are made of
				// their ingredients).
				List<BundlePart> items = stockItems(p, baseQty);
				for (BundlePart item : items) {
					BigDecimal cost = stock.averageCost(item.productId());
					l.bundle.add(new Part(item.productId(), null, null, item.qty(), cost));
				}
				l.parts.add(new Part(p.id(), null, null, baseQty, null));
			}
			else {
				l.parts.add(new Part(p.id(), null, null, baseQty, "SERVICE".equals(p.kind()) ? BigDecimal.ZERO : p.costAvg()));
			}
		}

		// ---- order type, table and token (fast food)
		KitchenService.Order order = kitchen.order(in.orderType(), in.tableNo(), branchId, today);

		// ---- header
		BigDecimal costTotal = lines.stream().map(Line::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
		String number = numbering.nextNumber("SALE", branchId);
		String walkInName = account == null ? trim(in.customerName(), 200) : account.name();
		String walkInPhone = account == null ? trim(in.customerPhone(), 30) : account.phone();
		long saleId = jdbc.queryForObject("""
				insert into sale (shop_id, number, branch_id, till_session_id, customer_id, customer_name, customer_phone,
				                  salesman_id, user_id, actor_name, subtotal, line_discount, bill_discount, tax, total, paid,
				                  change_given, cost_total, approved_by, approval_note, quotation_id, exchange_of_return_id,
				                  note, business_date, promo_discount, bill_promotion_id, coupon_code, order_type, table_no,
				                  token_no)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""",
				Long.class,
				TenantContext.requireShopId(), number, branchId, sessionId, account == null ? null : account.id(),
				walkInName, walkInPhone, in.salesmanId(), TillService.userId(), CurrentActor.name(),
				Amounts.money(subtotal), Amounts.money(lineDiscount), billDiscount, Amounts.money(tax),
				Amounts.money(total), Amounts.money(applied), Amounts.money(change), Amounts.money(costTotal), approvedBy,
				reasons.isEmpty() ? null : trim(String.join("; ", reasons), 300), in.quotationId(), exchangeOfReturnId,
				trim(in.note(), 500), Date.valueOf(today), Amounts.money(promoTotal), billOffer.offerId(),
				in.couponCode() == null || in.couponCode().isBlank() ? null : trim(in.couponCode().toUpperCase(), 30),
				order.type(), order.table(), order.token());
		DocRef doc = new DocRef("SALE", saleId, number);

		// ---- lines and stock
		List<Move> moves = new ArrayList<>();
		int lineNo = 1;
		for (Line l : lines) {
			boolean split = l.parts.size() > 1 && !l.product().serial();
			if (split) {
				// A line taken from two batches is stored as two lines with the amounts shared in proportion.
				List<BigDecimal> qtys = l.parts.stream().map(Part::qty).toList();
				List<BigDecimal> nets = SaleMath.spread(qtys, l.net);
				List<BigDecimal> taxes = SaleMath.spread(qtys, l.tax);
				List<BigDecimal> discounts = SaleMath.spread(qtys, l.discount());
				List<BigDecimal> billShares = SaleMath.spread(qtys, l.share);
				for (int k = 0; k < l.parts.size(); k++) {
					Part part = l.parts.get(k);
					BigDecimal partQty = Amounts.qty(part.qty().divide(l.unit().factor(), 3, RoundingMode.HALF_UP));
					insertLine(saleId, lineNo++, l, part.batchId(), null, partQty, part.qty(), discounts.get(k),
							billShares.get(k), nets.get(k), taxes.get(k),
							SaleMath.lineTotal(taxMode, nets.get(k), taxes.get(k)), part.unitCost());
				}
			}
			else if (l.product().serial()) {
				List<BigDecimal> ones = l.parts.stream().map(x -> BigDecimal.ONE).toList();
				List<BigDecimal> nets = SaleMath.spread(ones, l.net);
				List<BigDecimal> taxes = SaleMath.spread(ones, l.tax);
				List<BigDecimal> discounts = SaleMath.spread(ones, l.discount());
				List<BigDecimal> billShares = SaleMath.spread(ones, l.share);
				for (int k = 0; k < l.parts.size(); k++) {
					Part part = l.parts.get(k);
					insertLine(saleId, lineNo++, l, null, part.serial().id(), BigDecimal.ONE, BigDecimal.ONE,
							discounts.get(k), billShares.get(k), nets.get(k), taxes.get(k),
							SaleMath.lineTotal(taxMode, nets.get(k), taxes.get(k)), part.unitCost());
					LocalDate until = warranty && l.product().warrantyDays() > 0
							? today.plusDays(l.product().warrantyDays()) : null;
					stock.markSerialSold(part.serial().id(), saleId, account == null ? null : account.id(), until);
				}
			}
			else {
				Part part = l.parts.getFirst();
				insertLine(saleId, lineNo++, l, part.batchId(), null, l.qty(), l.baseQty(), l.discount(), l.share,
						l.net, l.tax, l.total, l.costPerBase());
			}
			if ("BUNDLE".equals(l.product().kind()) || "RECIPE".equals(l.product().kind())) {
				for (Part item : l.bundle) {
					moves.add(new Move(item.productId(), branchId, null, null, item.qty().negate(), item.unitCost(),
							l.product().name()));
				}
			}
			else if (l.product().tracksStock()) {
				for (Part part : l.parts) {
					moves.add(new Move(part.productId(), branchId, part.batchId(),
							part.serial() == null ? null : part.serial().id(), part.qty().negate(), part.unitCost(), null));
				}
			}
		}
		stock.apply(MoveType.SALE, doc, moves);

		// ---- payments
		BigDecimal changeLeft = change;
		for (Tender tender : tenders) {
			Tender t = "POINTS".equals(tender.method())
					? new Tender(tender.method(), tender.amount(), pointsUsed + " points") : tender;
			BigDecimal amount = t.amount();
			BigDecimal changeHere = null;
			BigDecimal tenderedHere = null;
			if ("CASH".equals(t.method()) && changeLeft.signum() > 0) {
				BigDecimal take = changeLeft.min(amount);
				tenderedHere = amount;
				changeHere = take;
				amount = amount.subtract(take);
				changeLeft = changeLeft.subtract(take);
			}
			if ("CREDIT_NOTE".equals(t.method())) {
				useCreditNote(t.reference(), t.amount(), account == null ? null : account.id());
			}
			if (amount.signum() == 0) {
				continue;
			}
			insertPayment(saleId, "SALE", t.method(), amount, tenderedHere, changeHere, t.reference(), sessionId,
					branchId, "SALE", saleId, today);
		}

		// ---- customer account
		if (account != null) {
			ledger.post(account.id(), "SALE", saleId, number, "Invoice " + number, Amounts.money(total), null);
			if (applied.signum() > 0) {
				ledger.post(account.id(), "SALE_PAYMENT", saleId, number, "Paid with invoice " + number, null,
						Amounts.money(applied));
			}
		}
		if (in.quotationId() != null) {
			jdbc.update("update quotation set status = 'CONVERTED', converted_sale_id = ? where id = ?", saleId,
					in.quotationId());
		}

		// ---- offers used, loyalty points, prescription and register
		Map<Long, BigDecimal> offerAmounts = new LinkedHashMap<>();
		for (Line l : lines) {
			if (l.promotionId != null) {
				offerAmounts.merge(l.promotionId, l.promo, BigDecimal::add);
			}
		}
		if (billOffer.offerId() != null) {
			offerAmounts.merge(billOffer.offerId(), billOffer.amount(), BigDecimal::add);
		}
		promotions.recordUse(offerAmounts.keySet(), offerAmounts, saleId, account == null ? null : account.id(), today);
		if (pointsUsed > 0) {
			loyalty.redeem(account.id(), pointsUsed, saleId, number, today);
		}
		loyalty.earn(account == null ? null : account.id(), Amounts.money(total.subtract(pointsAmount)), saleId, number,
				today);
		if (in.prescription() != null) {
			long rx = prescriptions.create(in.prescription(), saleId, TillService.userId(), CurrentActor.name());
			jdbc.update("update sale set prescription_id = ? where id = ?", rx, saleId);
			prescriptions.registerSale(saleId, schedules.products("CONTROLLED"), rx, pharmacistId);
		}
		if (lines.stream().anyMatch(l -> "RECIPE".equals(l.product().kind()) || "BUNDLE".equals(l.product().kind()))) {
			kitchen.ticket(saleId, branchId, order, today);
		}
		audit.record("SALE_POSTED", "sale", saleId, null,
				Map.of("number", number, "total", total.toPlainString(), "due", due.toPlainString()));
		return saleId;
	}

	/**
	 * Runs the offers on the lines (adding each line's offer to its discount) and returns the bill offer. A coupon that
	 * is not valid, not running or not used by any line is refused.
	 */
	private PromotionEngine.Applied applyOffers(List<Line> lines, boolean billTyped, String coupon, LocalDate today) {
		boolean hasCoupon = coupon != null && !coupon.isBlank();
		if (hasCoupon && !features.isOn(ModuleKey.PROMOTIONS)) {
			throw ApiException.rule("module_disabled", "Coupons are not switched on for this shop");
		}
		List<PromotionEngine.Offer> offers = promotions.today();
		if (offers.isEmpty() && !hasCoupon) {
			return new PromotionEngine.Applied(null, null, BigDecimal.ZERO.setScale(2));
		}
		java.time.LocalTime now = java.time.LocalTime.now(ShopTime.ZONE);
		List<PromotionEngine.Offer> running = offers.stream().filter(o -> PromotionEngine.running(o, today, now)).toList();
		if (hasCoupon) {
			PromotionEngine.Offer c = promotions.coupon(coupon);
			if (running.stream().noneMatch(o -> o.id() == c.id())) {
				throw ApiException.rule("coupon_not_running", "Coupon " + coupon.trim().toUpperCase() + " cannot be used now");
			}
		}
		Map<Long, Long> categories = new java.util.HashMap<>();
		jdbc.query("select id, category_id from product where id = any(?)", rs -> {
			categories.put(rs.getLong("id"), (Long) rs.getObject("category_id"));
		}, (Object) lines.stream().map(l -> l.product().id()).distinct().toArray(Long[]::new));
		List<PromotionEngine.LineIn> ins = new ArrayList<>();
		for (Line l : lines) {
			boolean eligible = l.unit().factor().compareTo(BigDecimal.ONE) == 0 && l.price().compareTo(l.listPrice()) == 0
					&& l.discount().signum() == 0;
			ins.add(new PromotionEngine.LineIn(l.product().id(), categories.get(l.product().id()), l.qty(), l.price(),
					eligible));
		}
		PromotionEngine.Result result = PromotionEngine.apply(running, ins,
				lines.stream().map(Line::discount).toList(), billTyped, coupon);
		if (hasCoupon && !result.couponUsed()) {
			throw ApiException.rule("coupon_not_applicable",
					"Coupon " + coupon.trim().toUpperCase() + " does not apply to this bill");
		}
		for (int i = 0; i < lines.size(); i++) {
			PromotionEngine.Applied a = result.lines().get(i);
			if (a.offerId() != null) {
				Line l = lines.get(i);
				l.promotionId = a.offerId();
				l.promo = a.amount();
				l.discount = l.discount.add(a.amount());
			}
		}
		return result.bill();
	}

	/**
	 * The stock items a deal (BUNDLE) or menu item (RECIPE) is made of, for {@code qty} of it: a deal's menu items are
	 * made of their ingredients; services in a deal take no stock. Quantities are in each item's base unit.
	 */
	List<BundlePart> stockItems(ProductRef p, BigDecimal qty) {
		List<BundlePart> items = catalog.bundle(p.id());
		if (items.isEmpty()) {
			throw ApiException.rule("empty_bundle", "RECIPE".equals(p.kind()) ? p.name() + " has no recipe yet"
					: p.name() + " has no items in it");
		}
		Map<Long, ProductRef> refs = catalog.find(items.stream().map(BundlePart::productId).toList());
		Map<Long, BigDecimal> out = new LinkedHashMap<>();
		for (BundlePart item : items) {
			ProductRef ref = refs.get(item.productId());
			BigDecimal need = item.qty().multiply(qty);
			if (ref != null && "RECIPE".equals(ref.kind()) && "BUNDLE".equals(p.kind())) {
				for (BundlePart ingredient : stockItems(ref, need)) {
					out.merge(ingredient.productId(), ingredient.qty(), BigDecimal::add);
				}
			}
			else if (ref == null || ref.tracksStock()) {
				out.merge(item.productId(), need, BigDecimal::add);
			}
		}
		List<BundlePart> list = new ArrayList<>();
		out.forEach((id, q) -> list.add(new BundlePart(id, Amounts.qty(q))));
		return list;
	}

	/** Cost per base unit as known before stock is locked (serial cost, else the average cost). */
	private BigDecimal indicativeCost(Line l) {
		ProductRef p = l.product();
		if (p.serial() && !l.serials().isEmpty()) {
			List<BigDecimal> costs = jdbc.queryForList(
					"select cost from serial_unit where status = 'IN_STOCK' and upper(serial) = any(?)", BigDecimal.class,
					(Object) l.serials().stream().map(String::toUpperCase).toArray(String[]::new));
			return costs.isEmpty() ? p.costAvg()
					: costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
						.divide(BigDecimal.valueOf(costs.size()), 2, RoundingMode.HALF_UP);
		}
		if ("BUNDLE".equals(p.kind()) || "RECIPE".equals(p.kind())) {
			BigDecimal sum = BigDecimal.ZERO;
			for (BundlePart part : stockItems(p, BigDecimal.ONE)) {
				sum = sum.add(part.qty().multiply(stock.averageCost(part.productId())));
			}
			return Amounts.money(sum);
		}
		return "SERVICE".equals(p.kind()) ? BigDecimal.ZERO : p.costAvg();
	}

	/** Takes {@code amount} from a credit note (locked); it must have enough balance and not be expired. */
	@Transactional(propagation = Propagation.MANDATORY)
	void useCreditNote(String number, BigDecimal amount, Long customerId) {
		if (number == null || number.isBlank()) {
			throw ApiException.field("payments.reference", "required", "Enter the credit note number");
		}
		Map<String, Object> note = jdbc.queryForList("select * from credit_note where upper(number) = ? for update",
				number.trim().toUpperCase()).stream().findFirst()
			.orElseThrow(() -> ApiException.rule("credit_note_invalid", "Credit note " + number + " not found"));
		Object expires = note.get("expires_on");
		if (expires != null && ((Date) expires).toLocalDate().isBefore(time.today())) {
			throw ApiException.rule("credit_note_expired", "Credit note " + number + " expired on " + expires);
		}
		BigDecimal balance = (BigDecimal) note.get("balance");
		if (balance.compareTo(amount) < 0) {
			throw ApiException.rule("credit_note_balance",
					"Credit note " + number + " has only Rs " + balance.toPlainString() + " left");
		}
		Object owner = note.get("customer_id");
		if (owner != null && customerId != null && ((Number) owner).longValue() != customerId) {
			throw ApiException.rule("credit_note_customer", "This credit note belongs to another customer");
		}
		jdbc.update("update credit_note set balance = balance - ? where id = ?", amount, note.get("id"));
	}

	void insertPayment(long saleId, String kind, String method, BigDecimal amount, BigDecimal tendered,
			BigDecimal change, String reference, Long sessionId, long branchId, String docType, Long docId,
			LocalDate date) {
		jdbc.update("""
				insert into sale_payment (shop_id, sale_id, kind, method_key, amount, tendered, change_given, reference,
				                          till_session_id, branch_id, doc_type, doc_id, business_date)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(), saleId, kind, method,
				Amounts.money(amount), tendered, change, trim(reference, 100), sessionId, branchId, docType, docId,
				Date.valueOf(date));
	}

	private void insertLine(long saleId, int lineNo, Line l, Long batchId, Long serialId, BigDecimal qty,
			BigDecimal baseQty, BigDecimal discount, BigDecimal billShare, BigDecimal net, BigDecimal tax,
			BigDecimal total, BigDecimal unitCost) {
		BigDecimal cost = Amounts.nz(unitCost);
		jdbc.update("""
				insert into sale_line (shop_id, sale_id, line_no, product_id, product_name, unit_id, unit_name, unit_factor,
				                       batch_id, serial_id, qty, base_qty, price, list_price, discount, bill_share, net,
				                       tax_rate, tax, total, unit_cost, cost_total, promotion_id, promo_discount, note)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", TenantContext.requireShopId(),
				saleId, lineNo, l.product().id(), l.product().name(), l.unit().unitId(), l.unit().name(),
				l.unit().factor(), batchId, serialId, Amounts.qty(qty), Amounts.qty(baseQty), l.price(), l.listPrice(),
				Amounts.money(discount), Amounts.money(billShare), Amounts.money(net), l.rate(), Amounts.money(tax),
				Amounts.money(total), Amounts.money(cost), Amounts.money(cost.multiply(baseQty)), l.promotionId,
				l.baseQty().signum() == 0 ? BigDecimal.ZERO
						: Amounts.money(l.promo.multiply(baseQty).divide(l.baseQty(), 2, RoundingMode.HALF_UP)),
				l.note);
	}

	static String trim(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String t = value.trim();
		return t.length() > max ? t.substring(0, max) : t;
	}

	/** Stock taken for a line: one product (or bundle item), batch or serial with its cost per base unit. */
	record Part(long productId, Long batchId, SerialUnit serial, BigDecimal qty, BigDecimal unitCost) {
	}

	/** A sale line while it is being worked out. */
	static final class Line {

		private final int index;

		private final ProductRef product;

		private final UnitRef unit;

		private final BigDecimal qty;

		private final BigDecimal price;

		private final BigDecimal listPrice;

		private final BigDecimal gross;

		private BigDecimal discount;

		private final BigDecimal rate;

		/** Offer on this line (included in {@code discount}). */
		Long promotionId;

		BigDecimal promo = BigDecimal.ZERO;

		/** For the kitchen. */
		String note;

		private final List<String> serials;

		private final Long batchId;

		BigDecimal share = BigDecimal.ZERO;

		BigDecimal net = BigDecimal.ZERO;

		BigDecimal tax = BigDecimal.ZERO;

		BigDecimal total = BigDecimal.ZERO;

		final List<Part> parts = new ArrayList<>();

		final List<Part> bundle = new ArrayList<>();

		Line(int index, ProductRef product, UnitRef unit, BigDecimal qty, BigDecimal price, BigDecimal listPrice,
				BigDecimal gross, BigDecimal discount, BigDecimal rate, List<String> serials, Long batchId) {
			this.index = index;
			this.product = product;
			this.unit = unit;
			this.qty = qty;
			this.price = price;
			this.listPrice = listPrice;
			this.gross = gross;
			this.discount = discount;
			this.rate = rate;
			this.serials = serials;
			this.batchId = batchId;
		}

		int index() {
			return index;
		}

		ProductRef product() {
			return product;
		}

		UnitRef unit() {
			return unit;
		}

		BigDecimal qty() {
			return qty;
		}

		BigDecimal price() {
			return price;
		}

		BigDecimal listPrice() {
			return listPrice;
		}

		BigDecimal gross() {
			return gross;
		}

		BigDecimal discount() {
			return discount;
		}

		BigDecimal rate() {
			return rate;
		}

		List<String> serials() {
			return serials;
		}

		Long batchId() {
			return batchId;
		}

		BigDecimal baseQty() {
			return Amounts.qty(qty.multiply(unit.factor()));
		}

		/** Cost of the whole line (D10: stored at the time of sale). */
		BigDecimal cost() {
			if (!bundle.isEmpty()) {
				return Amounts.money(bundle.stream().map(p -> p.qty().multiply(Amounts.nz(p.unitCost())))
					.reduce(BigDecimal.ZERO, BigDecimal::add));
			}
			return Amounts.money(parts.stream().map(p -> p.qty().multiply(Amounts.nz(p.unitCost())))
				.reduce(BigDecimal.ZERO, BigDecimal::add));
		}

		BigDecimal costPerBase() {
			BigDecimal base = baseQty();
			return base.signum() == 0 ? BigDecimal.ZERO : cost().divide(base, 2, RoundingMode.HALF_UP);
		}
	}
}
