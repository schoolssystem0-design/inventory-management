package com.selectcode.pos.stock.api;

import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.RequiresModule;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.stock.app.StockAdjustments;
import com.selectcode.pos.stock.app.StockAdjustments.AdjustmentInput;
import com.selectcode.pos.stock.app.StockAdjustments.AdjustmentListItem;
import com.selectcode.pos.stock.app.StockAdjustments.AdjustmentView;
import com.selectcode.pos.stock.app.StockAdjustments.OpeningStockInput;
import com.selectcode.pos.stock.app.StockAlerts;
import com.selectcode.pos.stock.app.StockAlerts.AlertSummary;
import com.selectcode.pos.stock.app.StockAlerts.AlertView;
import com.selectcode.pos.stock.app.StockAudit;
import com.selectcode.pos.stock.app.StockAudit.Difference;
import com.selectcode.pos.stock.app.StockCounts;
import com.selectcode.pos.stock.app.StockCounts.CountEntry;
import com.selectcode.pos.stock.app.StockCounts.CountListItem;
import com.selectcode.pos.stock.app.StockCounts.CountView;
import com.selectcode.pos.stock.app.StockCounts.StartCount;
import com.selectcode.pos.stock.app.StockQueries;
import com.selectcode.pos.stock.app.StockQueries.BatchStock;
import com.selectcode.pos.stock.app.StockQueries.MovementRow;
import com.selectcode.pos.stock.app.StockQueries.ProductStockView;
import com.selectcode.pos.stock.app.StockQueries.SerialListItem;
import com.selectcode.pos.stock.app.StockQueries.SerialView;
import com.selectcode.pos.stock.app.StockQueries.StockPage;
import com.selectcode.pos.stock.app.StockTransfers;
import com.selectcode.pos.stock.app.StockTransfers.ReceiveLineInput;
import com.selectcode.pos.stock.app.StockTransfers.TransferInput;
import com.selectcode.pos.stock.app.StockTransfers.TransferListItem;
import com.selectcode.pos.stock.app.StockTransfers.TransferView;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Stock on hand, history, adjustments, opening stock, counts, transfers, alerts, batches and IMEI (STK-01..06). */
@RestController
@RequestMapping("/api/v1")
class StockController {

	static final String VIEW = "hasAuthority('stock.view')";

	static final String ADJUST = "hasAuthority('stock.adjust')";

	static final String COUNT = "hasAuthority('stock.count')";

	static final String TRANSFER = "hasAuthority('stock.transfer')";

	record CountEntries(List<CountEntry> entries) {
	}

	record ReceiveTransfer(List<ReceiveLineInput> lines) {
	}

	private final StockQueries queries;

	private final StockAdjustments adjustments;

	private final StockCounts counts;

	private final StockTransfers transfers;

	private final StockAlerts alerts;

	private final StockAudit audit;

	StockController(StockQueries queries, StockAdjustments adjustments, StockCounts counts, StockTransfers transfers,
			StockAlerts alerts, StockAudit audit) {
		this.queries = queries;
		this.adjustments = adjustments;
		this.counts = counts;
		this.transfers = transfers;
		this.alerts = alerts;
		this.audit = audit;
	}

	// ---- stock on hand and history

	@PreAuthorize(VIEW)
	@GetMapping("/stock")
	StockPage stock(@RequestParam(required = false) Long branchId, @RequestParam(required = false) String q,
			@RequestParam(required = false) Long categoryId, @RequestParam(required = false) Long brandId,
			@RequestParam(required = false) Long supplierId, @RequestParam(required = false) String filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return queries.stock(branchId, q, categoryId, brandId, supplierId, filter, PageResponse.page(page),
				PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('stock.view', 'pos.sell', 'catalog.manage')")
	@GetMapping("/stock/products/{id}")
	ProductStockView productStock(@PathVariable long id) {
		return queries.product(id);
	}

	@PreAuthorize(VIEW)
	@GetMapping("/stock/movements")
	PageResponse<MovementRow> movements(@RequestParam(required = false) Long productId,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) String type,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return queries.movements(productId, branchId, type, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	/** Stock vs. history check (NFR-05); empty lists mean everything matches. */
	@PreAuthorize(ADJUST)
	@GetMapping("/stock/audit")
	List<Difference> stockAudit() {
		List<Difference> all = new java.util.ArrayList<>(audit.verify());
		all.addAll(audit.verifySerials());
		return all;
	}

	@PreAuthorize("hasAnyAuthority('stock.view', 'pos.sell')")
	@GetMapping("/batches")
	List<BatchStock> batches(@RequestParam(required = false) Long productId,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) Boolean all) {
		return queries.batches(productId, branchId, !Boolean.TRUE.equals(all));
	}

	// ---- IMEI / serial numbers

	@RequiresModule(ModuleKey.SERIALS)
	@PreAuthorize("hasAnyAuthority('stock.view', 'serials.manage', 'pos.sell')")
	@GetMapping("/serials/{serial}")
	SerialView serial(@PathVariable String serial) {
		return queries.serial(serial);
	}

	@RequiresModule(ModuleKey.SERIALS)
	@PreAuthorize("hasAnyAuthority('stock.view', 'serials.manage', 'pos.sell')")
	@GetMapping("/serials")
	PageResponse<SerialListItem> serials(@RequestParam(required = false) Long productId,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return queries.serials(productId, branchId, status, q, PageResponse.page(page), PageResponse.size(size));
	}

	// ---- adjustments and opening stock

	@PreAuthorize(VIEW)
	@GetMapping("/adjustments")
	PageResponse<AdjustmentListItem> adjustments(@RequestParam(required = false) Long branchId,
			@RequestParam(required = false) String reason,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return adjustments.list(branchId, reason, from, to, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/adjustments/{id}")
	AdjustmentView adjustment(@PathVariable long id) {
		return adjustments.get(id);
	}

	@PreAuthorize(ADJUST)
	@PostMapping("/adjustments")
	@ResponseStatus(HttpStatus.CREATED)
	AdjustmentView adjust(@RequestBody AdjustmentInput in,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return adjustments.create(in, ApprovalRequest.of(approver, pin));
	}

	/** Opening stock per branch (ONB-03): posted as an opening balance, not a purchase. */
	@PreAuthorize(ADJUST)
	@PostMapping("/opening-stock")
	@ResponseStatus(HttpStatus.CREATED)
	AdjustmentView openingStock(@RequestBody OpeningStockInput in) {
		return adjustments.opening(in);
	}

	// ---- stock counts

	@PreAuthorize(COUNT)
	@GetMapping("/stock-counts")
	PageResponse<CountListItem> counts(@RequestParam(required = false) Long branchId,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return counts.list(branchId, status, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(COUNT)
	@GetMapping("/stock-counts/{id}")
	CountView count(@PathVariable long id) {
		return counts.get(id);
	}

	@PreAuthorize(COUNT)
	@PostMapping("/stock-counts")
	@ResponseStatus(HttpStatus.CREATED)
	CountView startCount(@RequestBody StartCount in) {
		return counts.start(in);
	}

	@PreAuthorize(COUNT)
	@PutMapping("/stock-counts/{id}/lines")
	CountView enterCounts(@PathVariable long id, @RequestBody CountEntries in) {
		return counts.enter(id, in.entries());
	}

	@PreAuthorize(COUNT)
	@PostMapping("/stock-counts/{id}/post")
	CountView postCount(@PathVariable long id,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return counts.post(id, ApprovalRequest.of(approver, pin));
	}

	@PreAuthorize(COUNT)
	@PostMapping("/stock-counts/{id}/cancel")
	CountView cancelCount(@PathVariable long id) {
		return counts.cancel(id);
	}

	// ---- transfers

	@PreAuthorize("hasAnyAuthority('stock.view', 'stock.transfer')")
	@GetMapping("/transfers")
	PageResponse<TransferListItem> transfers(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return transfers.list(status, branchId, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize("hasAnyAuthority('stock.view', 'stock.transfer')")
	@GetMapping("/transfers/{id}")
	TransferView transfer(@PathVariable long id) {
		return transfers.get(id);
	}

	@PreAuthorize(TRANSFER)
	@PostMapping("/transfers")
	@ResponseStatus(HttpStatus.CREATED)
	TransferView dispatch(@RequestBody TransferInput in) {
		return transfers.dispatch(in);
	}

	@PreAuthorize(TRANSFER)
	@PostMapping("/transfers/{id}/receive")
	TransferView receive(@PathVariable long id, @RequestBody(required = false) ReceiveTransfer in) {
		return transfers.receive(id, in == null ? null : in.lines());
	}

	// ---- alerts

	@PreAuthorize(VIEW)
	@GetMapping("/alerts")
	PageResponse<AlertView> alerts(@RequestParam(required = false) String kind,
			@RequestParam(required = false) Long branchId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return alerts.list(kind, branchId, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(VIEW)
	@GetMapping("/alerts/summary")
	AlertSummary alertSummary() {
		return alerts.summary();
	}

	@PreAuthorize(VIEW)
	@PostMapping("/alerts/refresh")
	AlertSummary refreshAlerts() {
		return alerts.refresh();
	}

	@PreAuthorize(VIEW)
	@PostMapping("/alerts/{id}/seen")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void seen(@PathVariable long id) {
		alerts.seen(id);
	}
}
