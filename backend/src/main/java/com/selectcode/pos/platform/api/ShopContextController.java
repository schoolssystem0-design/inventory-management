package com.selectcode.pos.platform.api;

import com.selectcode.pos.platform.app.ShopContextService;
import com.selectcode.pos.platform.app.ShopContextService.ShopContext;
import com.selectcode.pos.platform.app.ShopModuleService.ModuleState;
import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.security.PublicEndpoint;
import com.selectcode.pos.shared.tenant.ShopLookup;
import com.selectcode.pos.shared.web.ApiException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The app's start-up call, the owner's module switches and the public shop branding for the login page. */
@RestController
class ShopContextController {

	record ModuleToggle(Boolean enabled) {
	}

	/** What the login page shows before sign-in: name, logo and whether the shop is open. */
	record PublicShop(String code, String name, Long logoFileId, String status, String message) {
	}

	private final ShopContextService context;

	private final ShopLookup shops;

	private final JdbcTemplate jdbc;

	ShopContextController(ShopContextService context, ShopLookup shops, JdbcTemplate jdbc) {
		this.context = context;
		this.shops = shops;
		this.jdbc = jdbc;
	}

	@AnyAuthenticated
	@GetMapping("/api/v1/shop/context")
	ShopContext context() {
		return context.context();
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_VIEW)
	@GetMapping("/api/v1/modules")
	List<ModuleState> modules() {
		return context.ownerModules();
	}

	@PreAuthorize(Permissions.HAS_SETTINGS_EDIT)
	@PutMapping("/api/v1/modules/{key}")
	List<ModuleState> toggle(@PathVariable String key, @RequestBody ModuleToggle body) {
		if (body.enabled() == null) {
			throw ApiException.field("enabled", "required", "Say whether the module is on or off");
		}
		return context.ownerToggle(key, body.enabled());
	}

	@PublicEndpoint
	@GetMapping("/api/v1/public/shops/{code}")
	PublicShop publicShop(@PathVariable String code) {
		var shop = shops.findByCode(code).orElseThrow(() -> ApiException.notFound("Shop"));
		List<Long> logos = com.selectcode.pos.shared.tenant.TenantContext.callAsShop(shop.id(),
				() -> jdbc.queryForList("select logo_file_id from shop_settings where shop_id = ?", Long.class, shop.id()));
		Long logo = logos.isEmpty() ? null : logos.getFirst();
		String message = switch (shop.status()) {
			case "SUSPENDED", "CLOSED" -> "This shop's account is not active. Please contact SelectCode support.";
			case "READ_ONLY" -> "The subscription is overdue: you can sign in to view and export.";
			default -> null;
		};
		return new PublicShop(shop.code(), shop.name(), shop.canSignIn() ? logo : null, shop.status(), message);
	}
}
