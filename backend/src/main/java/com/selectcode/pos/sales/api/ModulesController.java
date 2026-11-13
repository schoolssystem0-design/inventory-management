package com.selectcode.pos.sales.api;

import com.selectcode.pos.iam.app.ApprovalService.ApprovalRequest;
import com.selectcode.pos.sales.app.DeliveryService;
import com.selectcode.pos.sales.app.DeliveryService.CancelChallan;
import com.selectcode.pos.sales.app.DeliveryService.ChallanInput;
import com.selectcode.pos.sales.app.DeliveryService.ChallanView;
import com.selectcode.pos.sales.app.DeliveryService.ConvertChallan;
import com.selectcode.pos.sales.app.DeliveryService.DeliveryInput;
import com.selectcode.pos.sales.app.DeliveryService.DeliveryUpdate;
import com.selectcode.pos.sales.app.DeliveryService.DeliveryView;
import com.selectcode.pos.sales.app.InstallmentService;
import com.selectcode.pos.sales.app.InstallmentService.InstallmentDueRow;
import com.selectcode.pos.sales.app.InstallmentService.InstallmentPlanInput;
import com.selectcode.pos.sales.app.InstallmentService.InstallmentPlanView;
import com.selectcode.pos.sales.app.InstallmentService.InstallmentPayment;
import com.selectcode.pos.sales.app.LoyaltyService;
import com.selectcode.pos.sales.app.LoyaltyService.AdjustInput;
import com.selectcode.pos.sales.app.LoyaltyService.LoyaltyAccount;
import com.selectcode.pos.sales.app.LoyaltyService.LoyaltyTxnView;
import com.selectcode.pos.sales.app.PrescriptionService;
import com.selectcode.pos.sales.app.PrescriptionService.PrescriptionView;
import com.selectcode.pos.sales.app.PrescriptionService.RegisterEntry;
import com.selectcode.pos.sales.app.PromotionEngine;
import com.selectcode.pos.sales.app.PromotionService;
import com.selectcode.pos.sales.app.PromotionService.PromotionInput;
import com.selectcode.pos.sales.app.PromotionService.PromotionView;
import com.selectcode.pos.sales.app.RepairService;
import com.selectcode.pos.sales.app.RepairService.DeliverInput;
import com.selectcode.pos.sales.app.RepairService.PartInput;
import com.selectcode.pos.sales.app.RepairService.RepairCounts;
import com.selectcode.pos.sales.app.RepairService.RepairInput;
import com.selectcode.pos.sales.app.RepairService.RepairUpdate;
import com.selectcode.pos.sales.app.RepairService.RepairView;
import com.selectcode.pos.shared.features.ModuleKey;
import com.selectcode.pos.shared.features.RequiresModule;
import com.selectcode.pos.shared.time.ShopTime;
import com.selectcode.pos.shared.web.ApiException;
import com.selectcode.pos.shared.web.PageResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 shop-type modules (Stage 4b): offers and coupons, loyalty points, repair jobs, installments, prescriptions
 * and the controlled-medicine register, deliveries and challans. Every endpoint needs its module switched on.
 */
@RestController
@RequestMapping("/api/v1")
class ModulesController {

	/** A coupon as the till needs it (the code itself is not listed in the catalogue). */
	record CouponView(long offerId, String name, String summary) {
	}

	private final PromotionService promotions;

	private final LoyaltyService loyalty;

	private final RepairService repairs;

	private final InstallmentService installments;

	private final PrescriptionService prescriptions;

	private final DeliveryService deliveries;

	private final ShopTime time;

	ModulesController(PromotionService promotions, LoyaltyService loyalty, RepairService repairs,
			InstallmentService installments, PrescriptionService prescriptions, DeliveryService deliveries,
			ShopTime time) {
		this.promotions = promotions;
		this.loyalty = loyalty;
		this.repairs = repairs;
		this.installments = installments;
		this.prescriptions = prescriptions;
		this.deliveries = deliveries;
		this.time = time;
	}

	// ---- offers and coupons

	@RequiresModule(ModuleKey.PROMOTIONS)
	@PreAuthorize("hasAnyAuthority('promotions.manage', 'pos.sell', 'reports.view')")
	@GetMapping("/promotions")
	List<PromotionView> promotions(@RequestParam(required = false) Boolean active) {
		return promotions.list(active);
	}

	@RequiresModule(ModuleKey.PROMOTIONS)
	@PreAuthorize("hasAnyAuthority('promotions.manage', 'reports.view')")
	@GetMapping("/promotions/{id}")
	PromotionView promotion(@PathVariable long id) {
		return promotions.get(id);
	}

	@RequiresModule(ModuleKey.PROMOTIONS)
	@PreAuthorize("hasAuthority('promotions.manage')")
	@PostMapping("/promotions")
	@ResponseStatus(HttpStatus.CREATED)
	PromotionView createPromotion(@RequestBody PromotionInput in) {
		return promotions.create(in);
	}

	@RequiresModule(ModuleKey.PROMOTIONS)
	@PreAuthorize("hasAuthority('promotions.manage')")
	@PutMapping("/promotions/{id}")
	PromotionView updatePromotion(@PathVariable long id, @RequestBody PromotionInput in) {
		return promotions.update(id, in);
	}

	/** Checks a coupon typed at the till: valid and running now. */
	@RequiresModule(ModuleKey.PROMOTIONS)
	@PreAuthorize("hasAuthority('pos.sell')")
	@GetMapping("/pos/coupons/{code}")
	CouponView coupon(@PathVariable String code) {
		PromotionEngine.Offer o = promotions.coupon(code);
		if (!PromotionEngine.running(o, time.today(), LocalTime.now(ShopTime.ZONE))) {
			throw ApiException.rule("coupon_not_running", "Coupon " + code.trim().toUpperCase() + " cannot be used now");
		}
		return new CouponView(o.id(), o.name(), promotions.get(o.id()).summary());
	}

	// ---- loyalty

	@RequiresModule(ModuleKey.LOYALTY)
	@PreAuthorize("hasAnyAuthority('customers.view', 'pos.sell')")
	@GetMapping("/customers/{id}/loyalty")
	LoyaltyAccount loyalty(@PathVariable long id) {
		return loyalty.account(id);
	}

	@RequiresModule(ModuleKey.LOYALTY)
	@PreAuthorize("hasAuthority('customers.view')")
	@GetMapping("/customers/{id}/loyalty/history")
	PageResponse<LoyaltyTxnView> loyaltyHistory(@PathVariable long id, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return loyalty.history(id, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.LOYALTY)
	@PreAuthorize("hasAuthority('loyalty.manage')")
	@PostMapping("/customers/{id}/loyalty/adjust")
	LoyaltyAccount adjustLoyalty(@PathVariable long id, @RequestBody AdjustInput in) {
		return loyalty.adjust(id, in);
	}

	// ---- repairs

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAnyAuthority('repairs.manage', 'pos.sell')")
	@GetMapping("/repairs")
	PageResponse<RepairView> repairs(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long technicianId, @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return repairs.list(status, technicianId, q, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAnyAuthority('repairs.manage', 'pos.sell')")
	@GetMapping("/repairs/counts")
	RepairCounts repairCounts() {
		return repairs.counts();
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAnyAuthority('repairs.manage', 'pos.sell')")
	@GetMapping("/repairs/{id}")
	RepairView repair(@PathVariable long id) {
		return repairs.get(id);
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAnyAuthority('repairs.manage', 'pos.sell')")
	@PostMapping("/repairs")
	@ResponseStatus(HttpStatus.CREATED)
	RepairView bookRepair(@RequestBody RepairInput in) {
		return repairs.create(in);
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAuthority('repairs.manage')")
	@PatchMapping("/repairs/{id}")
	RepairView updateRepair(@PathVariable long id, @RequestBody RepairUpdate in) {
		return repairs.update(id, in);
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAuthority('repairs.manage')")
	@PostMapping("/repairs/{id}/parts")
	RepairView addRepairPart(@PathVariable long id, @RequestBody PartInput in) {
		return repairs.addPart(id, in);
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAuthority('repairs.manage')")
	@DeleteMapping("/repairs/{id}/parts/{partId}")
	RepairView removeRepairPart(@PathVariable long id, @PathVariable long partId) {
		return repairs.removePart(id, partId);
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAuthority('pos.sell')")
	@PostMapping("/repairs/{id}/deliver")
	RepairView deliverRepair(@PathVariable long id, @RequestBody(required = false) DeliverInput in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return repairs.deliver(id, in, key, ApprovalRequest.of(approver, pin));
	}

	@RequiresModule(ModuleKey.REPAIRS)
	@PreAuthorize("hasAuthority('repairs.manage')")
	@PostMapping("/repairs/{id}/cancel")
	RepairView cancelRepair(@PathVariable long id, @RequestBody RepairService.CancelRepair in) {
		return repairs.cancel(id, in);
	}

	// ---- installments

	@RequiresModule(ModuleKey.INSTALLMENTS)
	@PreAuthorize("hasAnyAuthority('installments.manage', 'customers.payments', 'customers.view')")
	@GetMapping("/installment-plans")
	PageResponse<InstallmentPlanView> plans(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long customerId, @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return installments.list(status, customerId, q, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.INSTALLMENTS)
	@PreAuthorize("hasAnyAuthority('installments.manage', 'customers.payments', 'customers.view')")
	@GetMapping("/installment-plans/{id}")
	InstallmentPlanView plan(@PathVariable long id) {
		return installments.get(id);
	}

	@RequiresModule(ModuleKey.INSTALLMENTS)
	@PreAuthorize("hasAuthority('installments.manage')")
	@PostMapping("/installment-plans")
	@ResponseStatus(HttpStatus.CREATED)
	InstallmentPlanView createPlan(@RequestBody InstallmentPlanInput in) {
		return installments.create(in);
	}

	@RequiresModule(ModuleKey.INSTALLMENTS)
	@PreAuthorize("hasAuthority('customers.payments')")
	@PostMapping("/installment-plans/{id}/receive")
	InstallmentPlanView receiveInstallment(@PathVariable long id, @RequestBody InstallmentPayment in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key) {
		return installments.receive(id, in, key);
	}

	@RequiresModule(ModuleKey.INSTALLMENTS)
	@PreAuthorize("hasAuthority('installments.manage')")
	@PostMapping("/installment-plans/{id}/cancel")
	InstallmentPlanView cancelPlan(@PathVariable long id, @RequestBody InstallmentService.CancelPlan in) {
		return installments.cancel(id, in);
	}

	@RequiresModule(ModuleKey.INSTALLMENTS)
	@PreAuthorize("hasAnyAuthority('installments.manage', 'customers.payments')")
	@GetMapping("/installments/due")
	List<InstallmentDueRow> dueInstallments(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Boolean overdue) {
		return installments.dueList(to, Boolean.TRUE.equals(overdue));
	}

	// ---- prescriptions and the controlled-medicine register

	@RequiresModule(ModuleKey.PRESCRIPTIONS)
	@PreAuthorize("hasAnyAuthority('prescriptions.approve', 'sales.view')")
	@GetMapping("/prescriptions")
	PageResponse<PrescriptionView> prescriptionList(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return prescriptions.list(from, to, q, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.PRESCRIPTIONS)
	@PreAuthorize("hasAnyAuthority('prescriptions.approve', 'sales.view')")
	@GetMapping("/prescriptions/{id}")
	PrescriptionView prescription(@PathVariable long id) {
		return prescriptions.get(id);
	}

	@RequiresModule(ModuleKey.PRESCRIPTIONS)
	@PreAuthorize("hasAnyAuthority('prescriptions.approve', 'reports.view')")
	@GetMapping("/controlled-register")
	List<RegisterEntry> controlledRegister(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long productId, @RequestParam(required = false) Long branchId) {
		LocalDate end = to == null ? time.today() : to;
		LocalDate start = from == null ? end.withDayOfMonth(1) : from;
		if (start.isAfter(end) || start.plusDays(400).isBefore(end)) {
			throw ApiException.field("from", "invalid", "Choose dates up to a year apart");
		}
		return prescriptions.register(start, end, productId, branchId);
	}

	// ---- deliveries and challans

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAnyAuthority('delivery.manage', 'sales.view')")
	@GetMapping("/deliveries")
	PageResponse<DeliveryView> deliveries(@RequestParam(required = false) String status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Long saleId, @RequestParam(required = false) Long challanId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return deliveries.deliveries(status, from, to, saleId, challanId, PageResponse.page(page),
				PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAnyAuthority('delivery.manage', 'sales.view')")
	@GetMapping("/deliveries/{id}")
	DeliveryView delivery(@PathVariable long id) {
		return deliveries.delivery(id);
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAnyAuthority('delivery.manage', 'pos.sell')")
	@PostMapping("/deliveries")
	@ResponseStatus(HttpStatus.CREATED)
	DeliveryView createDelivery(@RequestBody DeliveryInput in) {
		return deliveries.create(in);
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAuthority('delivery.manage')")
	@PatchMapping("/deliveries/{id}")
	DeliveryView updateDelivery(@PathVariable long id, @RequestBody DeliveryUpdate in) {
		return deliveries.update(id, in);
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAnyAuthority('delivery.manage', 'sales.view')")
	@GetMapping("/challans")
	PageResponse<ChallanView> challans(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long customerId, @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return deliveries.challans(status, customerId, q, PageResponse.page(page), PageResponse.size(size));
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAnyAuthority('delivery.manage', 'sales.view')")
	@GetMapping("/challans/{id}")
	ChallanView challan(@PathVariable long id) {
		return deliveries.challan(id);
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAuthority('delivery.manage')")
	@PostMapping("/challans")
	@ResponseStatus(HttpStatus.CREATED)
	ChallanView createChallan(@RequestBody ChallanInput in) {
		return deliveries.createChallan(in);
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAuthority('pos.sell')")
	@PostMapping("/challans/{id}/convert")
	ChallanView convertChallan(@PathVariable long id, @RequestBody(required = false) ConvertChallan in,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestHeader(value = "X-Approval-User", required = false) String approver,
			@RequestHeader(value = "X-Approval-Pin", required = false) String pin) {
		return deliveries.convert(id, in, key, ApprovalRequest.of(approver, pin));
	}

	@RequiresModule(ModuleKey.DELIVERY)
	@PreAuthorize("hasAuthority('delivery.manage')")
	@PostMapping("/challans/{id}/cancel")
	ChallanView cancelChallan(@PathVariable long id, @RequestBody CancelChallan in) {
		return deliveries.cancelChallan(id, in);
	}
}
