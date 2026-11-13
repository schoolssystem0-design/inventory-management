package com.selectcode.pos.shop.api;

import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.PageResponse;
import com.selectcode.pos.shop.app.BranchService;
import com.selectcode.pos.shop.app.BranchService.BranchInput;
import com.selectcode.pos.shop.app.BranchService.BranchView;
import com.selectcode.pos.shop.app.BranchService.TillView;
import com.selectcode.pos.shop.app.SettingsService;
import com.selectcode.pos.shop.app.SettingsService.SettingsChangeView;
import com.selectcode.pos.shop.app.SettingsService.SettingsUpdate;
import com.selectcode.pos.shop.app.SettingsService.SettingsView;
import com.selectcode.pos.shop.app.ShopConfigService;
import com.selectcode.pos.shop.app.ShopConfigService.NumberingUpdate;
import com.selectcode.pos.shop.app.ShopConfigService.NumberingView;
import com.selectcode.pos.shop.app.ShopConfigService.PaymentMethodUpdate;
import com.selectcode.pos.shop.app.ShopConfigService.PaymentMethodView;
import com.selectcode.pos.shop.app.ShopConfigService.SupportCode;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Shop settings, branches, tills, numbering and payment methods (SET-01..08). */
@RestController
@RequestMapping("/api/v1")
class ShopSetupController {

	record TillInput(Long branchId, @Size(max = 60) String name, Boolean active) {
	}

	private final SettingsService settings;

	private final BranchService branches;

	private final ShopConfigService config;

	ShopSetupController(SettingsService settings, BranchService branches, ShopConfigService config) {
		this.settings = settings;
		this.branches = branches;
		this.config = config;
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_VIEW)
	@GetMapping("/settings")
	SettingsView getSettings() {
		return settings.get();
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_EDIT)
	@PatchMapping("/settings")
	SettingsView updateSettings(@RequestBody SettingsUpdate body) {
		return settings.update(body);
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_VIEW)
	@GetMapping("/settings/history")
	PageResponse<SettingsChangeView> settingsHistory(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return settings.history(PageResponse.page(page), PageResponse.size(size));
	}

	/** The owner lets SelectCode support make changes for one hour (PLT-17). */
	@PreAuthorize(Permissions.HAS_SUPPORT_APPROVE)
	@PostMapping("/settings/support-code")
	SupportCode supportCode() {
		return config.newSupportCode();
	}

	/** Any signed-in user: the till and stock screens need the branch list. */
	@AnyAuthenticated
	@GetMapping("/branches")
	List<BranchView> branches() {
		return branches.list();
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PostMapping("/branches")
	@ResponseStatus(HttpStatus.CREATED)
	BranchView createBranch(@RequestBody BranchInput body) {
		return branches.create(body);
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PatchMapping("/branches/{id}")
	BranchView updateBranch(@PathVariable long id, @RequestBody BranchInput body) {
		return branches.update(id, body);
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PostMapping("/branches/{id}/activate")
	BranchView activateBranch(@PathVariable long id) {
		return branches.setActive(id, true);
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PostMapping("/branches/{id}/deactivate")
	BranchView deactivateBranch(@PathVariable long id) {
		return branches.setActive(id, false);
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PostMapping("/branches/{id}/make-default")
	BranchView makeDefaultBranch(@PathVariable long id) {
		return branches.makeDefault(id);
	}

	@AnyAuthenticated
	@GetMapping("/tills")
	List<TillView> tills() {
		return branches.tills();
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PostMapping("/tills")
	@ResponseStatus(HttpStatus.CREATED)
	TillView createTill(@RequestBody TillInput body) {
		if (body.branchId() == null) {
			throw com.selectcode.pos.shared.web.ApiException.field("branchId", "required", "Choose a branch");
		}
		return branches.createTill(body.branchId(), body.name());
	}

	@PreAuthorize(Permissions.HAS_BRANCHES_MANAGE)
	@PatchMapping("/tills/{id}")
	TillView updateTill(@PathVariable long id, @RequestBody TillInput body) {
		return branches.updateTill(id, body.name(), body.active());
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_VIEW)
	@GetMapping("/numbering")
	List<NumberingView> numbering() {
		return config.numbering();
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_EDIT)
	@PatchMapping("/numbering/{id}")
	NumberingView updateNumbering(@PathVariable long id, @RequestBody NumberingUpdate body) {
		return config.updateNumbering(id, body);
	}

	@AnyAuthenticated
	@GetMapping("/payment-methods")
	List<PaymentMethodView> paymentMethods() {
		return config.paymentMethods();
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_EDIT)
	@PatchMapping("/payment-methods/{id}")
	PaymentMethodView updatePaymentMethod(@PathVariable long id, @RequestBody PaymentMethodUpdate body) {
		return config.updatePaymentMethod(id, body);
	}
}
