package com.selectcode.pos.purchasing.api;

import com.selectcode.pos.purchasing.app.GoodsReceipts;
import com.selectcode.pos.purchasing.app.GoodsReceipts.GrnInput;
import com.selectcode.pos.purchasing.app.GoodsReceipts.GrnListItem;
import com.selectcode.pos.purchasing.app.GoodsReceipts.GrnView;
import com.selectcode.pos.purchasing.app.PurchaseOrders;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoInput;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoListItem;
import com.selectcode.pos.purchasing.app.PurchaseOrders.PoView;
import com.selectcode.pos.purchasing.app.PurchaseReturns;
import com.selectcode.pos.purchasing.app.PurchaseReturns.PurchaseReturnInput;
import com.selectcode.pos.purchasing.app.PurchaseReturns.PurchaseReturnView;
import com.selectcode.pos.purchasing.app.Reorder;
import com.selectcode.pos.purchasing.app.Reorder.ReorderRequest;
import com.selectcode.pos.purchasing.app.Reorder.Suggestion;
import com.selectcode.pos.purchasing.app.SupplierPayments;
import com.selectcode.pos.purchasing.app.SupplierPayments.SupplierPaymentInput;
import com.selectcode.pos.purchasing.app.SupplierPayments.SupplierPaymentView;
import com.selectcode.pos.purchasing.app.SupplierService;
import com.selectcode.pos.purchasing.app.SupplierService.SupplierInput;
import com.selectcode.pos.purchasing.app.SupplierService.SupplierLedgerLine;
import com.selectcode.pos.purchasing.app.SupplierService.SupplierListItem;
import com.selectcode.pos.purchasing.app.SupplierService.SupplierStatement;
import com.selectcode.pos.purchasing.app.SupplierService.SupplierView;
import com.selectcode.pos.shared.docs.PdfWriter;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.SettingsService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

/** Suppliers, purchase orders, goods received, purchase returns, supplier payments and reorder (PUR-01..07). */
@RestController
@RequestMapping("/api/v1")
class PurchasingController {

	static final String VIEW = "hasAuthority('purchasing.view')";

	static final String MANAGE = "hasAuthority('purchasing.manage')";

	static final String PAY = "hasAuthority('suppliers.payments')";

	record Reason(String reason) {
	}

	private final SupplierService suppliers;

	private final PurchaseOrders orders;

	private final GoodsReceipts receipts;

	private final PurchaseReturns returns;

	private final SupplierPayments payments;

	private final Reorder reorder;

	private final SettingsService settings;

	PurchasingController(SupplierService suppliers, PurchaseOrders orders, GoodsReceipts receipts,
			PurchaseReturns returns, SupplierPayments payments, Reorder reorder, SettingsService settings) {
		this.suppliers = suppliers;
		this.orders = orders;
		this.receipts = receipts;
		this.returns = returns;
		this.payments = payments;
		this.reorder = reorder;
		this.settings = settings;
	}

	// ---- suppliers

	@PreAuthorize(VIEW)
	@GetMapping("/suppliers")
	PageResponse<SupplierListItem> suppliers(@RequestParam(required = false) String q,
			@RequestParam(required = false) Boolean withBalance, @RequestParam(required = false) Boolean active,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return suppliers.list(q, withBalance, active, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/suppliers/{id}")
	SupplierView supplier(@PathVariable long id) {
		return suppliers.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/suppliers")
	@ResponseStatus(HttpStatus.CREATED)
	SupplierView createSupplier(@RequestBody SupplierInput in) {
		return suppliers.create(in);
	}

	@PreAuthorize(MANAGE)
	@PatchMapping("/suppliers/{id}")
	SupplierView updateSupplier(@PathVariable long id, @RequestBody SupplierInput in) {
		return suppliers.update(id, in);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/suppliers/{id}/activate")
	SupplierView activateSupplier(@PathVariable long id) {
		return suppliers.setActive(id, true);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/suppliers/{id}/deactivate")
	SupplierView deactivateSupplier(@PathVariable long id) {
		return suppliers.setActive(id, false);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/suppliers/{id}/ledger")
	PageResponse<SupplierLedgerLine> supplierLedger(@PathVariable long id, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return suppliers.ledger(id, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/suppliers/{id}/statement")
	SupplierStatement supplierStatement(@PathVariable long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return suppliers.statement(id, from, to);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/suppliers/{id}/statement.pdf")
	ResponseEntity<byte[]> supplierStatementPdf(@PathVariable long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		SupplierStatement s = suppliers.statement(id, from, to);
		return pdf(PdfWriter.write(suppliers.statementDoc(s, settings.get().businessName())), "supplier-statement-" + id);
	}

	// ---- purchase orders

	@PreAuthorize(VIEW)
	@GetMapping("/purchase-orders")
	PageResponse<PoListItem> purchaseOrders(@RequestParam(required = false) Long supplierId,
			@RequestParam(required = false) String status, @RequestParam(required = false) Long branchId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return orders.list(supplierId, status, branchId, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/purchase-orders/{id}")
	PoView purchaseOrder(@PathVariable long id) {
		return orders.get(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/purchase-orders/{id}/pdf")
	ResponseEntity<byte[]> purchaseOrderPdf(@PathVariable long id) {
		PoView po = orders.get(id);
		return pdf(PdfWriter.write(orders.document(po, settings.get().businessName())), po.number());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/purchase-orders")
	@ResponseStatus(HttpStatus.CREATED)
	PoView createPurchaseOrder(@RequestBody PoInput in) {
		return orders.create(in);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/purchase-orders/{id}/close")
	PoView closePurchaseOrder(@PathVariable long id, @RequestBody(required = false) Reason in) {
		return orders.close(id, in == null ? null : in.reason());
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/purchase-orders/{id}/cancel")
	PoView cancelPurchaseOrder(@PathVariable long id, @RequestBody(required = false) Reason in) {
		return orders.cancel(id, in == null ? null : in.reason());
	}

	// ---- goods received

	@PreAuthorize(VIEW)
	@GetMapping("/grns")
	PageResponse<GrnListItem> grns(@RequestParam(required = false) Long supplierId,
			@RequestParam(required = false) Long branchId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Boolean unpaid, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return receipts.list(supplierId, branchId, from, to, unpaid, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/grns/{id}")
	GrnView grn(@PathVariable long id) {
		return receipts.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/grns")
	@ResponseStatus(HttpStatus.CREATED)
	GrnView receive(@RequestBody GrnInput in, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
		return receipts.receive(in, key);
	}

	// ---- purchase returns

	@PreAuthorize(VIEW)
	@GetMapping("/purchase-returns")
	PageResponse<PurchaseReturnView> purchaseReturns(@RequestParam(required = false) Long supplierId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return returns.list(supplierId, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/purchase-returns/{id}")
	PurchaseReturnView purchaseReturn(@PathVariable long id) {
		return returns.get(id);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/purchase-returns")
	@ResponseStatus(HttpStatus.CREATED)
	PurchaseReturnView createPurchaseReturn(@RequestBody PurchaseReturnInput in) {
		return returns.create(in);
	}

	// ---- supplier payments

	@PreAuthorize("hasAnyAuthority('suppliers.payments', 'purchasing.view')")
	@GetMapping("/supplier-payments")
	PageResponse<SupplierPaymentView> supplierPayments(@RequestParam(required = false) Long supplierId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return payments.list(supplierId, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('suppliers.payments', 'purchasing.view')")
	@GetMapping("/supplier-payments/{id}")
	SupplierPaymentView supplierPayment(@PathVariable long id) {
		return payments.get(id);
	}

	@PreAuthorize(PAY)
	@PostMapping("/supplier-payments")
	@ResponseStatus(HttpStatus.CREATED)
	SupplierPaymentView pay(@RequestBody SupplierPaymentInput in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key) {
		return payments.pay(in, key);
	}

	// ---- reorder

	@PreAuthorize(VIEW)
	@GetMapping("/reorder")
	List<Suggestion> reorder(@RequestParam(required = false) Long branchId) {
		return reorder.suggestions(branchId);
	}

	@PreAuthorize(MANAGE)
	@PostMapping("/reorder/purchase-orders")
	@ResponseStatus(HttpStatus.CREATED)
	List<PoView> reorderToPurchaseOrders(@RequestBody ReorderRequest in) {
		return reorder.createOrders(in);
	}

	private static ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name + ".pdf").build().toString())
			.body(bytes);
	}
}
