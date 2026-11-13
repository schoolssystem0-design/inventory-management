package com.selectcode.pos.platform.api;

import com.selectcode.pos.platform.app.BillingService;
import com.selectcode.pos.platform.app.BillingService.InvoiceInput;
import com.selectcode.pos.platform.app.BillingService.InvoiceView;
import com.selectcode.pos.platform.app.BillingService.JobResult;
import com.selectcode.pos.platform.app.BillingService.SubscriptionPaymentInput;
import com.selectcode.pos.platform.app.BillingService.PlanChange;
import com.selectcode.pos.platform.app.BillingService.PlanChangeResult;
import com.selectcode.pos.platform.app.BillingService.StatusOverride;
import com.selectcode.pos.platform.app.DemoShops;
import com.selectcode.pos.platform.app.OnboardingService;
import com.selectcode.pos.platform.app.OnboardingService.Checklist;
import com.selectcode.pos.platform.app.OnboardingService.Terms;
import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.security.PlatformAuthorities;
import com.selectcode.pos.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription billing for SelectCode staff (BIL-05..09), the shop's own view of its invoices, the onboarding
 * checklist (ONB-02, OPS-05), terms acceptance (OPS-16) and demo shop resets (OPS-01).
 */
@RestController
@RequestMapping("/api/v1")
class BillingController {

	static final String BILLING = "hasAuthority('" + PlatformAuthorities.BILLING + "') or hasAuthority('"
			+ PlatformAuthorities.SHOPS_MANAGE + "')";

	record AcceptTerms(String version) {
	}

	private final BillingService billing;

	private final OnboardingService onboarding;

	private final DemoShops demos;

	BillingController(BillingService billing, OnboardingService onboarding, DemoShops demos) {
		this.billing = billing;
		this.onboarding = onboarding;
		this.demos = demos;
	}

	// ---- platform side

	@PreAuthorize("hasAuthority('" + PlatformAuthorities.BILLING + "') or hasAuthority('" + PlatformAuthorities.SHOPS_VIEW
			+ "')")
	@GetMapping("/platform/subscription-invoices")
	List<InvoiceView> invoices(@RequestParam(required = false) Long shopId,
			@RequestParam(required = false) String status) {
		return billing.invoices(shopId, status);
	}

	@PreAuthorize("hasAuthority('" + PlatformAuthorities.BILLING + "') or hasAuthority('" + PlatformAuthorities.SHOPS_VIEW
			+ "')")
	@GetMapping("/platform/subscription-invoices/{id}")
	InvoiceView invoice(@PathVariable long id) {
		return billing.invoice(id);
	}

	@PreAuthorize(BILLING)
	@PostMapping("/platform/shops/{id}/invoices")
	@ResponseStatus(HttpStatus.CREATED)
	InvoiceView createInvoice(@PathVariable long id, @RequestBody InvoiceInput in) {
		return billing.createManual(id, in);
	}

	@PreAuthorize(BILLING)
	@PostMapping("/platform/subscription-invoices/{id}/payments")
	@ResponseStatus(HttpStatus.CREATED)
	InvoiceView pay(@PathVariable long id, @RequestBody SubscriptionPaymentInput in) {
		return billing.recordPayment(id, in);
	}

	@PreAuthorize(BILLING)
	@PostMapping("/platform/shops/{id}/plan-change")
	PlanChangeResult changePlan(@PathVariable long id, @RequestBody PlanChange in) {
		return billing.changePlan(id, in);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_MANAGE)
	@PostMapping("/platform/shops/{id}/status")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void overrideStatus(@PathVariable long id, @RequestBody StatusOverride in) {
		billing.overrideStatus(id, in);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/platform/shops/{id}/onboarding")
	Checklist shopOnboarding(@PathVariable long id) {
		return onboarding.checklist(id);
	}

	/** Runs the nightly billing job now (invoices, reminders, status moves). */
	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@PostMapping("/platform/jobs/billing")
	JobResult runBilling() {
		return billing.runDaily();
	}

	/** Rebuilds a demo shop of a type with fresh sample data (OPS-01). */
	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@PostMapping("/platform/demo-shops/{type}/reset")
	Map<String, Object> resetDemo(@PathVariable String type) {
		return demos.reset(type, DemoShops.Size.FULL);
	}

	// ---- shop side

	@PreAuthorize("hasAuthority('subscription.view')")
	@GetMapping("/subscription/invoices")
	List<InvoiceView> myInvoices() {
		return billing.invoices(TenantContext.requireShopId(), null);
	}

	@PreAuthorize("hasAuthority('settings.view')")
	@GetMapping("/onboarding")
	Checklist checklist() {
		return onboarding.checklist();
	}

	@PreAuthorize("hasAuthority('settings.edit')")
	@PostMapping("/onboarding/printer-tested")
	Checklist printerTested() {
		return onboarding.printerTested();
	}

	@AnyAuthenticated
	@GetMapping("/terms")
	Terms terms() {
		return onboarding.terms();
	}

	@PreAuthorize("hasAuthority('settings.edit')")
	@PostMapping("/terms/accept")
	Terms acceptTerms(@RequestBody AcceptTerms in) {
		return onboarding.accept(in.version());
	}
}
