package com.selectcode.pos.sales.api;

import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.sales.app.CounterService;
import com.selectcode.pos.sales.app.CounterService.ConvertQuotation;
import com.selectcode.pos.sales.app.CounterService.CustomerPaymentInput;
import com.selectcode.pos.sales.app.CounterService.CustomerPaymentView;
import com.selectcode.pos.sales.app.CounterService.HeldSaleView;
import com.selectcode.pos.sales.app.CounterService.HoldInput;
import com.selectcode.pos.sales.app.CounterService.QuotationInput;
import com.selectcode.pos.sales.app.CounterService.QuotationView;
import com.selectcode.pos.sales.app.MobileShopService;
import com.selectcode.pos.sales.app.MobileShopService.BuybackInput;
import com.selectcode.pos.sales.app.MobileShopService.BuybackView;
import com.selectcode.pos.sales.app.MobileShopService.WarrantyClaimInput;
import com.selectcode.pos.sales.app.MobileShopService.WarrantyClaimView;
import com.selectcode.pos.sales.app.MobileShopService.WarrantyUpdate;
import com.selectcode.pos.sales.app.PosCatalog;
import com.selectcode.pos.sales.app.PosCatalog.PosCatalogView;
import com.selectcode.pos.sales.app.ReturnService;
import com.selectcode.pos.sales.app.ReturnService.CreditNoteView;
import com.selectcode.pos.sales.app.ReturnService.ExchangeInput;
import com.selectcode.pos.sales.app.ReturnService.ExchangeView;
import com.selectcode.pos.sales.app.ReturnService.ReturnInput;
import com.selectcode.pos.sales.app.ReturnService.ReturnView;
import com.selectcode.pos.sales.app.SaleQueries;
import com.selectcode.pos.sales.app.SaleQueries.ReceiptView;
import com.selectcode.pos.sales.app.SaleQueries.SaleList;
import com.selectcode.pos.sales.app.SaleQueries.SaleListItem;
import com.selectcode.pos.sales.app.SaleQueries.SaleView;
import com.selectcode.pos.sales.app.SalesService;
import com.selectcode.pos.sales.app.SalesService.SaleInput;
import com.selectcode.pos.sales.app.TillService;
import com.selectcode.pos.sales.app.TillService.CashMovementInput;
import com.selectcode.pos.sales.app.TillService.CashMovementView;
import com.selectcode.pos.sales.app.TillService.CloseTill;
import com.selectcode.pos.sales.app.TillService.OpenTill;
import com.selectcode.pos.sales.app.TillService.SessionView;
import com.selectcode.pos.sales.app.TillService.ZReport;
import com.selectcode.pos.shared.docs.PdfWriter;
import com.selectcode.pos.shared.docs.TableDoc;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.RequiresModule;
import com.selectcode.pos.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Till sessions, the POS catalogue, sales, returns, exchanges, held bills, quotations and customer payments. */
@RestController
@RequestMapping("/api/v1")
class SalesController {

	static final String SELL = "hasAuthority('pos.sell')";

	static final String TILL = "hasAuthority('till.manage')";

	static final String VIEW = "hasAuthority('sales.view')";

	record CancelInput(String reason, Long tillSessionId) {
	}

	private final TillService tills;

	private final PosCatalog catalog;

	private final SalesService sales;

	private final SaleQueries queries;

	private final ReturnService returns;

	private final CounterService counter;

	private final MobileShopService mobile;

	private final com.selectcode.pos.sales.app.ReceiptSender receipts;

	SalesController(TillService tills, PosCatalog catalog, SalesService sales, SaleQueries queries,
			ReturnService returns, CounterService counter, MobileShopService mobile,
			com.selectcode.pos.sales.app.ReceiptSender receipts) {
		this.receipts = receipts;
		this.tills = tills;
		this.catalog = catalog;
		this.sales = sales;
		this.queries = queries;
		this.returns = returns;
		this.counter = counter;
		this.mobile = mobile;
	}

	// ---- tills

	@PreAuthorize(TILL)
	@PostMapping("/till-sessions")
	@ResponseStatus(HttpStatus.CREATED)
	SessionView openTill(@RequestBody OpenTill in) {
		return tills.open(in);
	}

	@PreAuthorize("hasAnyAuthority('till.manage', 'pos.sell')")
	@GetMapping("/till-sessions/current")
	List<SessionView> myTills() {
		return tills.mine();
	}

	@PreAuthorize("hasAnyAuthority('till.manage', 'cash.view')")
	@GetMapping("/till-sessions")
	PageResponse<SessionView> tillSessions(@RequestParam(required = false) Long branchId,
			@RequestParam(required = false) Boolean open,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return tills.list(branchId, open, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('till.manage', 'cash.view')")
	@GetMapping("/till-sessions/{id}")
	SessionView tillSession(@PathVariable long id) {
		return tills.get(id);
	}

	@PreAuthorize(TILL)
	@PostMapping("/till-sessions/{id}/cash-movements")
	@ResponseStatus(HttpStatus.CREATED)
	CashMovementView cashMovement(@PathVariable long id, @RequestBody CashMovementInput in) {
		return tills.cashMovement(id, in);
	}

	@PreAuthorize("hasAnyAuthority('till.manage', 'cash.view')")
	@GetMapping("/till-sessions/{id}/cash-movements")
	List<CashMovementView> cashMovements(@PathVariable long id) {
		return tills.cashMovements(id);
	}

	@PreAuthorize(TILL)
	@PostMapping("/till-sessions/{id}/close")
	ZReport closeTill(@PathVariable long id, @RequestBody CloseTill in) {
		return tills.close(id, in);
	}

	@PreAuthorize("hasAnyAuthority('till.manage', 'cash.view')")
	@GetMapping("/till-sessions/{id}/z-report")
	ZReport zReport(@PathVariable long id) {
		return tills.zReport(id);
	}

	// ---- POS catalogue

	/** The till's product list (ETag: 304 when nothing changed since the version the till has). */
	@PreAuthorize(SELL)
	@GetMapping("/pos/catalog")
	ResponseEntity<PosCatalogView> posCatalog(@RequestParam(required = false) Long branchId,
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		String etag = "\"" + catalog.version(branchId) + "\"";
		if (etag.equals(ifNoneMatch)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
		}
		return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(catalog.catalog(branchId));
	}

	// ---- sales

	@PreAuthorize(SELL)
	@PostMapping("/sales")
	@ResponseStatus(HttpStatus.CREATED)
	SaleView sell(@RequestBody SaleInput in, @RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return sales.post(in, key, ApprovalRequest.of(approver, pin));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/sales")
	SaleList salesList(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) Long userId,
			@RequestParam(required = false) Long customerId, @RequestParam(required = false) String method,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) Boolean withDue, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return queries.list(from, to, branchId, userId, customerId, method, status, q, withDue, PageResponse.page(page),
				PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('sales.view', 'pos.sell')")
	@GetMapping("/sales/{id}")
	SaleView sale(@PathVariable long id) {
		return queries.get(id);
	}

	/** Print data for 80 mm / 58 mm / A4 receipts, reprints and the warranty card (POS-06, MOB-06). */
	@PreAuthorize("hasAnyAuthority('sales.view', 'pos.sell')")
	@GetMapping("/sales/{id}/receipt")
	ReceiptView receipt(@PathVariable long id) {
		return queries.receipt(id);
	}

	@PreAuthorize("hasAnyAuthority('sales.view', 'pos.sell')")
	@GetMapping("/sales/{id}/pdf")
	ResponseEntity<byte[]> salePdf(@PathVariable long id) {
		ReceiptView r = queries.receipt(id);
		SaleView s = r.sale();
		TableDoc.Builder doc = TableDoc.builder("Invoice " + s.number())
			.heading(r.businessName())
			.heading(r.address())
			.heading(r.ntn() == null ? null : "NTN " + r.ntn())
			.heading("Date: " + s.date() + (s.customerName() == null ? "" : " · Customer: " + s.customerName()))
			.column("item", "Item", "TEXT")
			.column("qty", "Qty", "QTY")
			.column("price", "Rate", "MONEY")
			.column("discount", "Discount", "MONEY")
			.column("tax", "Tax", "MONEY")
			.column("total", "Amount", "MONEY");
		for (SaleQueries.SaleLineView l : s.lines()) {
			String name = l.productName() + (l.serial() == null ? "" : " · IMEI " + l.serial())
					+ (l.batchNo() == null ? "" : " · Batch " + l.batchNo());
			doc.row(Arrays.asList(name, l.qty(), l.price(), l.discount(), l.tax(), l.total()));
		}
		doc.totals(Arrays.asList("Total", null, null, s.billDiscount(), s.tax(), s.total()));
		doc.footer("Paid: Rs " + s.paid() + " · Due: Rs " + s.due());
		if (r.footer() != null) {
			doc.footer(r.footer());
		}
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(s.number() + ".pdf").build().toString())
			.body(PdfWriter.write(doc.build()));
	}

	/** Email (or SMS) the receipt to the customer (POS-06, NTF-05). */
	@PreAuthorize("hasAnyAuthority('sales.view', 'pos.sell')")
	@PostMapping("/sales/{id}/send")
	com.selectcode.pos.sales.app.ReceiptSender.Sent sendReceipt(@PathVariable long id,
			@RequestBody(required = false) com.selectcode.pos.sales.app.ReceiptSender.SendReceipt in) {
		return receipts.send(id, in);
	}

	@PreAuthorize("hasAuthority('sales.cancel')")
	@PostMapping("/sales/{id}/cancel")
	SaleView cancel(@PathVariable long id, @RequestBody(required = false) CancelInput in) {
		return returns.cancel(id, in == null ? null : in.reason(), in == null ? null : in.tillSessionId());
	}

	@PreAuthorize("hasAuthority('sales.return')")
	@PostMapping("/sales/{id}/returns")
	@ResponseStatus(HttpStatus.CREATED)
	ReturnView returnItems(@PathVariable long id, @RequestBody ReturnInput in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return returns.returnItems(id, in, key, ApprovalRequest.of(approver, pin));
	}

	@PreAuthorize("hasAnyAuthority('sales.view', 'sales.return')")
	@GetMapping("/sale-returns")
	PageResponse<ReturnView> saleReturns(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return returns.list(from, to, branchId, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('sales.view', 'sales.return')")
	@GetMapping("/sale-returns/{id}")
	ReturnView saleReturn(@PathVariable long id) {
		return returns.get(id);
	}

	@PreAuthorize("hasAuthority('sales.return') and hasAuthority('pos.sell')")
	@PostMapping("/exchanges")
	@ResponseStatus(HttpStatus.CREATED)
	ExchangeView exchange(@RequestBody ExchangeInput in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return returns.exchange(in, key, ApprovalRequest.of(approver, pin));
	}

	@PreAuthorize("hasAnyAuthority('pos.sell', 'sales.view')")
	@GetMapping("/credit-notes")
	List<CreditNoteView> creditNotes(@RequestParam(required = false) String number,
			@RequestParam(required = false) Long customerId) {
		return returns.creditNotes(number, customerId);
	}

	// ---- held bills

	@PreAuthorize(SELL)
	@GetMapping("/held-sales")
	List<HeldSaleView> heldSales(@RequestParam(required = false) Long branchId) {
		return counter.heldSales(branchId);
	}

	@PreAuthorize(SELL)
	@GetMapping("/held-sales/{id}")
	HeldSaleView heldSale(@PathVariable long id) {
		return counter.held(id);
	}

	@PreAuthorize(SELL)
	@PostMapping("/held-sales")
	@ResponseStatus(HttpStatus.CREATED)
	HeldSaleView hold(@RequestBody HoldInput in) {
		return counter.hold(in);
	}

	@PreAuthorize(SELL)
	@DeleteMapping("/held-sales/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void releaseHeld(@PathVariable long id) {
		counter.release(id);
	}

	// ---- quotations

	@RequiresModule(ModuleKey.QUOTATIONS)
	@PreAuthorize("hasAnyAuthority('quotations.manage', 'sales.view')")
	@GetMapping("/quotations")
	PageResponse<QuotationView> quotations(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long customerId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return counter.quotations(status, customerId, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.QUOTATIONS)
	@PreAuthorize("hasAnyAuthority('quotations.manage', 'sales.view')")
	@GetMapping("/quotations/{id}")
	QuotationView quotation(@PathVariable long id) {
		return counter.quotation(id);
	}

	@RequiresModule(ModuleKey.QUOTATIONS)
	@PreAuthorize("hasAuthority('quotations.manage')")
	@PostMapping("/quotations")
	@ResponseStatus(HttpStatus.CREATED)
	QuotationView quote(@RequestBody QuotationInput in) {
		return counter.quote(in);
	}

	@RequiresModule(ModuleKey.QUOTATIONS)
	@PreAuthorize(SELL)
	@PostMapping("/quotations/{id}/convert")
	@ResponseStatus(HttpStatus.CREATED)
	SaleView convert(@PathVariable long id, @RequestBody(required = false) ConvertQuotation in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return counter.convert(id, in, key, ApprovalRequest.of(approver, pin));
	}

	@RequiresModule(ModuleKey.QUOTATIONS)
	@PreAuthorize("hasAuthority('quotations.manage')")
	@PostMapping("/quotations/{id}/cancel")
	QuotationView cancelQuotation(@PathVariable long id) {
		return counter.cancelQuotation(id);
	}

	// ---- customer payments

	@PreAuthorize("hasAuthority('customers.payments')")
	@PostMapping("/customer-payments")
	@ResponseStatus(HttpStatus.CREATED)
	CustomerPaymentView receivePayment(@RequestBody CustomerPaymentInput in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key) {
		return counter.receive(in, key);
	}

	@PreAuthorize("hasAnyAuthority('customers.payments', 'customers.view')")
	@GetMapping("/customer-payments")
	PageResponse<CustomerPaymentView> customerPayments(@RequestParam(required = false) Long customerId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return counter.payments(customerId, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('customers.payments', 'customers.view')")
	@GetMapping("/customer-payments/{id}")
	CustomerPaymentView customerPayment(@PathVariable long id) {
		return counter.payment(id);
	}

	@PreAuthorize("hasAuthority('customers.view')")
	@GetMapping("/customers/{id}/open-invoices")
	PageResponse<SaleListItem> openInvoices(@PathVariable long id, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return queries.openInvoices(id, PageResponse.page(page), PageResponse.size(size));
	}

	// ---- mobile shop: used phones and warranty

	@RequiresModule(ModuleKey.USED_BUYBACK)
	@PreAuthorize("hasAuthority('buyback.manage')")
	@PostMapping("/buybacks")
	@ResponseStatus(HttpStatus.CREATED)
	BuybackView buy(@RequestBody BuybackInput in) {
		return mobile.buy(in);
	}

	@RequiresModule(ModuleKey.USED_BUYBACK)
	@PreAuthorize("hasAnyAuthority('buyback.manage', 'stock.view')")
	@GetMapping("/buybacks")
	PageResponse<BuybackView> buybacks(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return mobile.buybacks(from, to, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.USED_BUYBACK)
	@PreAuthorize("hasAnyAuthority('buyback.manage', 'stock.view')")
	@GetMapping("/buybacks/{id}")
	BuybackView buyback(@PathVariable long id) {
		return mobile.buyback(id);
	}

	@RequiresModule(ModuleKey.WARRANTY)
	@PreAuthorize("hasAnyAuthority('warranty.manage', 'pos.sell')")
	@PostMapping("/warranty-claims")
	@ResponseStatus(HttpStatus.CREATED)
	WarrantyClaimView claim(@RequestBody WarrantyClaimInput in) {
		return mobile.claim(in);
	}

	@RequiresModule(ModuleKey.WARRANTY)
	@PreAuthorize("hasAnyAuthority('warranty.manage', 'pos.sell')")
	@GetMapping("/warranty-claims")
	PageResponse<WarrantyClaimView> warrantyClaims(@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return mobile.warrantyClaims(status, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.WARRANTY)
	@PreAuthorize("hasAnyAuthority('warranty.manage', 'pos.sell')")
	@GetMapping("/warranty-claims/{id}")
	WarrantyClaimView warrantyClaim(@PathVariable long id) {
		return mobile.warranty(id);
	}

	@RequiresModule(ModuleKey.WARRANTY)
	@PreAuthorize("hasAuthority('warranty.manage')")
	@PatchMapping("/warranty-claims/{id}")
	WarrantyClaimView updateClaim(@PathVariable long id, @RequestBody WarrantyUpdate in) {
		return mobile.updateClaim(id, in);
	}
}
