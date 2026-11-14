package com.selectcode.pos.platform.api;

import com.selectcode.pos.catalog.app.ProductImportService;
import com.selectcode.pos.catalog.app.ProductImportService.ImportResult;
import com.selectcode.pos.iam.app.SecurityLogQueries;
import com.selectcode.pos.iam.app.SecurityLogQueries.RoleView;
import com.selectcode.pos.iam.app.UserAdminService;
import com.selectcode.pos.iam.app.UserAdminService.OwnerSummary;
import com.selectcode.pos.iam.app.UserAdminService.PasswordReset;
import com.selectcode.pos.iam.app.UserAdminService.SetupLink;
import com.selectcode.pos.iam.app.UserAdminService.UserInput;
import com.selectcode.pos.iam.app.UserAdminService.UserView;
import com.selectcode.pos.platform.PlatformPrincipal;
import com.selectcode.pos.platform.app.PlatformAdminService;
import com.selectcode.pos.platform.app.PlatformAdminService.Overview;
import com.selectcode.pos.platform.app.PlatformAdminService.PlanView;
import com.selectcode.pos.platform.app.PlatformAdminService.PlatformAuditEntry;
import com.selectcode.pos.platform.app.PlatformAdminService.PlatformUserCreated;
import com.selectcode.pos.platform.app.PlatformAdminService.PlatformUserInput;
import com.selectcode.pos.platform.app.PlatformAdminService.PlatformUserView;
import com.selectcode.pos.platform.app.PlatformAdminService.ShopTypeView;
import com.selectcode.pos.platform.app.PlatformAuthService;
import com.selectcode.pos.platform.app.ShopAdminService;
import com.selectcode.pos.platform.app.ShopAdminService.CodeCheck;
import com.selectcode.pos.platform.app.ShopAdminService.NewShop;
import com.selectcode.pos.platform.app.ShopAdminService.ShopCreated;
import com.selectcode.pos.platform.app.ShopAdminService.ShopListItem;
import com.selectcode.pos.platform.app.ShopAdminService.ShopProfile;
import com.selectcode.pos.platform.app.ShopAdminService.ShopQuery;
import com.selectcode.pos.platform.app.ShopAdminService.ShopUpdate;
import com.selectcode.pos.platform.app.ShopAdminService.SubscriptionUpdate;
import com.selectcode.pos.platform.app.SupportSessionService;
import com.selectcode.pos.platform.app.SupportSessionService.SupportSessionView;
import com.selectcode.pos.shared.security.CurrentActor;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.security.PlatformAuthorities;
import com.selectcode.pos.shared.security.PublicEndpoint;
import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The super admin portal API (PLT-01..27, BIL-01..04). */
@RestController
@RequestMapping("/api/v1/platform")
class PlatformController {

	record PlatformLoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password,
			@Size(max = 10) String totpCode) {
	}

	/** {@code enrolmentRequired}: first sign-in; scan {@code otpauthUri} and send the first code to /totp/enroll. */
	record PlatformLoginResponse(boolean enrolmentRequired, String secret, String otpauthUri, PlatformMe me) {
	}

	record EnrollRequest(@NotBlank @Size(max = 10) String code) {
	}

	record PlatformMe(long id, String email, String name, String role, List<String> capabilities,
			SupportSessionView supportSession) {
	}

	record ReasonRequest(@Size(max = 500) String reason) {
	}

	record CloseRequest(@Size(max = 30) String confirmCode, @Size(max = 500) String reason) {
	}

	record ChangeOwnerRequest(Long userId, @Size(max = 500) String reason) {
	}

	record ResetShopPasswordRequest(Boolean byLink) {
	}

	record SupportStart(Long shopId, @Size(max = 500) String reason) {
	}

	record ApprovalCodeRequest(@Size(max = 10) String code) {
	}

	private final PlatformAuthService auth;

	private final ShopAdminService shops;

	private final PlatformAdminService admin;

	private final SupportSessionService support;

	private final ProductImportService imports;

	private final UserAdminService shopUsers;

	private final SecurityLogQueries shopSecurityLogs;

	private final SecurityContextRepository securityContextRepository;

	private final TransactionTemplate tx;

	PlatformController(PlatformAuthService auth, ShopAdminService shops, PlatformAdminService admin,
			SupportSessionService support, ProductImportService imports, UserAdminService shopUsers,
			SecurityLogQueries shopSecurityLogs, SecurityContextRepository securityContextRepository,
			PlatformTransactionManager transactions) {
		this.auth = auth;
		this.shops = shops;
		this.admin = admin;
		this.support = support;
		this.imports = imports;
		this.shopUsers = shopUsers;
		this.shopSecurityLogs = shopSecurityLogs;
		this.securityContextRepository = securityContextRepository;
		this.tx = new TransactionTemplate(transactions);
	}

	// ------------------------------------------------------------------ sign-in (PLT-01, PLT-03)

	@PublicEndpoint
	@PostMapping("/auth/login")
	PlatformLoginResponse login(@Valid @RequestBody PlatformLoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		var outcome = auth.signIn(body.email(), body.password(), body.totpCode(), request.getRemoteAddr(),
				request.getHeader(HttpHeaders.USER_AGENT), request.getSession());
		return switch (outcome) {
			case PlatformAuthService.Outcome.EnrolmentRequired e -> new PlatformLoginResponse(true, e.secret(), e.otpauthUri(), null);
			case PlatformAuthService.Outcome.SignedIn s -> {
				signIn(s.principal(), request, response);
				yield new PlatformLoginResponse(false, null, null, me(s.principal(), null));
			}
		};
	}

	@PublicEndpoint
	@PostMapping("/auth/totp/enroll")
	PlatformLoginResponse enroll(@Valid @RequestBody EnrollRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		PlatformPrincipal principal = auth.completeEnrolment(body.code(), request.getRemoteAddr(),
				request.getHeader(HttpHeaders.USER_AGENT), request.getSession());
		signIn(principal, request, response);
		return new PlatformLoginResponse(false, null, null, me(principal, null));
	}

	@PreAuthorize(PlatformAuthorities.IS_PLATFORM)
	@GetMapping("/auth/me")
	PlatformMe me() {
		return me((PlatformPrincipal) CurrentActor.require(), support.current());
	}

	// ------------------------------------------------------------------ overview, types, plans

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/overview")
	Overview overview() {
		return admin.overview();
	}

	@PreAuthorize(PlatformAuthorities.IS_PLATFORM)
	@GetMapping("/shop-types")
	List<ShopTypeView> shopTypes() {
		return admin.shopTypes();
	}

	@PreAuthorize(PlatformAuthorities.IS_PLATFORM)
	@GetMapping("/plans")
	List<PlanView> plans() {
		return admin.plans();
	}

	@PreAuthorize(PlatformAuthorities.IS_PLATFORM)
	@GetMapping("/modules")
	List<Map<String, Object>> moduleCatalogue() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (var m : com.selectcode.pos.shared.features.ModuleKey.values()) {
			list.add(Map.of("key", m.key(), "label", m.label(), "phase", m.phase(), "ownerCanToggle", m.ownerCanToggle()));
		}
		return list;
	}

	// ------------------------------------------------------------------ shops

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/shops")
	PageResponse<ShopListItem> shops(@RequestParam(required = false) String q,
			@RequestParam(required = false) String type, @RequestParam(required = false) String plan,
			@RequestParam(required = false) String status, @RequestParam(required = false) String city,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return shops.list(new ShopQuery(q, type, plan, status, city, PageResponse.page(page), PageResponse.size(size)));
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_CREATE)
	@GetMapping("/shops/code-check")
	CodeCheck codeCheck(@RequestParam(required = false) String code, @RequestParam(required = false) String name) {
		return shops.checkCode(code, name);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_CREATE)
	@PostMapping("/shops")
	@ResponseStatus(HttpStatus.CREATED)
	ShopCreated createShop(@RequestBody NewShop body) {
		return shops.create(body);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/shops/{id}")
	ShopProfile shop(@PathVariable long id) {
		return shops.profile(id);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_MANAGE)
	@PatchMapping("/shops/{id}")
	ShopProfile updateShop(@PathVariable long id, @RequestBody ShopUpdate body) {
		return shops.update(id, body);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_MANAGE)
	@PutMapping("/shops/{id}/modules")
	ShopProfile setModules(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
		return shops.setModules(id, body);
	}

	@PreAuthorize("hasAuthority('" + PlatformAuthorities.SHOPS_MANAGE + "') or hasAuthority('"
			+ PlatformAuthorities.BILLING + "')")
	@PatchMapping("/shops/{id}/subscription")
	ShopProfile changeSubscription(@PathVariable long id, @RequestBody SubscriptionUpdate body) {
		return shops.changeSubscription(id, body);
	}

	@PreAuthorize("hasAuthority('" + PlatformAuthorities.SHOPS_MANAGE + "') or hasAuthority('"
			+ PlatformAuthorities.BILLING + "')")
	@PostMapping("/shops/{id}/suspend")
	ShopProfile suspend(@PathVariable long id, @RequestBody ReasonRequest body) {
		return shops.suspend(id, body.reason());
	}

	@PreAuthorize("hasAuthority('" + PlatformAuthorities.SHOPS_MANAGE + "') or hasAuthority('"
			+ PlatformAuthorities.BILLING + "')")
	@PostMapping("/shops/{id}/reactivate")
	ShopProfile reactivate(@PathVariable long id) {
		return shops.reactivate(id);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_DELETE)
	@PostMapping("/shops/{id}/close")
	ShopProfile close(@PathVariable long id, @RequestBody CloseRequest body) {
		return shops.close(id, body.confirmCode(), body.reason());
	}

	@PreAuthorize("hasAuthority('" + PlatformAuthorities.SHOPS_MANAGE + "') or hasAuthority('"
			+ PlatformAuthorities.SUPPORT + "')")
	@PostMapping("/shops/{id}/owner/setup-link")
	SetupLink ownerSetupLink(@PathVariable long id) {
		return shops.resendOwnerSetup(id);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_MANAGE)
	@PostMapping("/shops/{id}/owner/change")
	OwnerSummary changeOwner(@PathVariable long id, @RequestBody ChangeOwnerRequest body) {
		if (body.userId() == null) {
			throw com.selectcode.pos.shared.web.ApiException.field("userId", "required", "Choose the new owner");
		}
		return shops.changeOwner(id, body.userId(), body.reason());
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/shops/{id}/users")
	PageResponse<UserView> listShopUsers(@PathVariable long id, @RequestParam(required = false) String q,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		shops.profile(id);
		return asShopOwner(id,
				() -> shopUsers.list(q, status, PageResponse.page(page), PageResponse.size(size)));
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/shops/{id}/roles")
	List<RoleView> listShopRoles(@PathVariable long id) {
		shops.profile(id);
		return asShopOwner(id, shopSecurityLogs::roles);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_MANAGE)
	@PatchMapping("/shops/{shopId}/users/{userId}")
	UserView updateShopUser(@PathVariable long shopId, @PathVariable long userId, @RequestBody UserInput body) {
		shops.profile(shopId);
		return asShopOwner(shopId, () -> shopUsers.update(userId, body));
	}

	/** Temporary password (default) or reset link by email ({@code byLink: true}). Works for the owner and staff. */
	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_MANAGE)
	@PostMapping("/shops/{shopId}/users/{userId}/reset-password")
	PasswordReset resetShopUserPassword(@PathVariable long shopId, @PathVariable long userId,
			@RequestBody(required = false) ResetShopPasswordRequest body) {
		shops.profile(shopId);
		boolean byLink = body != null && Boolean.TRUE.equals(body.byLink());
		return asShopOwner(shopId, () -> shopUsers.resetPassword(userId, byLink));
	}

	/** Imports a client's product Excel during onboarding (PLT-18); same rules as the shop's own import. */
	@PreAuthorize(PlatformAuthorities.HAS_IMPORT)
	@PostMapping(path = "/shops/{id}/import/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ImportResult importProducts(@PathVariable long id, @RequestParam MultipartFile file,
			@RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
		shops.profile(id);
		byte[] content = file.getBytes();
		return asShopOwner(id, () -> imports.importFile(content, !dryRun));
	}

	// ------------------------------------------------------------------ support mode (PLT-17)

	@PreAuthorize(PlatformAuthorities.HAS_SUPPORT)
	@PostMapping("/support-session")
	SupportSessionView startSupport(@RequestBody SupportStart body, HttpServletRequest request,
			HttpServletResponse response) {
		if (body.shopId() == null) {
			throw com.selectcode.pos.shared.web.ApiException.field("shopId", "required", "Choose a shop");
		}
		return support.start(body.shopId(), body.reason(), request, response);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SUPPORT)
	@PostMapping("/support-session/approve")
	SupportSessionView approveSupportWrites(@RequestBody ApprovalCodeRequest body) {
		return support.approveWrites(body.code());
	}

	@PreAuthorize(PlatformAuthorities.IS_PLATFORM)
	@DeleteMapping("/support-session")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void endSupport(HttpServletRequest request, HttpServletResponse response) {
		support.end(request, response);
	}

	// ------------------------------------------------------------------ platform users, settings, audit

	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@GetMapping("/users")
	List<PlatformUserView> platformUsers() {
		return admin.platformUsers();
	}

	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@PostMapping("/users")
	@ResponseStatus(HttpStatus.CREATED)
	PlatformUserCreated createPlatformUser(@RequestBody PlatformUserInput body) {
		return admin.createPlatformUser(body);
	}

	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@PatchMapping("/users/{id}")
	PlatformUserView updatePlatformUser(@PathVariable long id, @RequestBody PlatformUserInput body) {
		return admin.updatePlatformUser(id, body);
	}

	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@PostMapping("/users/{id}/reset")
	PlatformUserCreated resetPlatformUser(@PathVariable long id) {
		return admin.resetPlatformUser(id);
	}

	@PreAuthorize(PlatformAuthorities.IS_PLATFORM)
	@GetMapping("/settings")
	Map<String, String> settings() {
		return admin.settings();
	}

	@PreAuthorize(PlatformAuthorities.HAS_ADMIN)
	@PatchMapping("/settings")
	Map<String, String> updateSettings(@RequestBody Map<String, String> body) {
		return admin.updateSettings(body);
	}

	@PreAuthorize(PlatformAuthorities.HAS_SHOPS_VIEW)
	@GetMapping("/audit")
	PageResponse<PlatformAuditEntry> audit(@RequestParam(required = false) Long shopId,
			@RequestParam(required = false) String action, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return admin.audit(shopId, action, PageResponse.page(page), PageResponse.size(size));
	}

	// ------------------------------------------------------------------ helpers

	private void signIn(PlatformPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		request.changeSessionId();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		request.getSession().setMaxInactiveInterval(30 * 60);
	}

	private static PlatformMe me(PlatformPrincipal p, SupportSessionView session) {
		return new PlatformMe(p.platformUserId(), p.email(), p.name(), p.role(),
				PlatformAuthorities.BY_ROLE.getOrDefault(p.role(), List.of()), session);
	}

	/** Runs shop code as the shop's owner would (all shop permissions), in the given shop. */
	private <T> T asShopOwner(long shopId, java.util.function.Supplier<T> work) {
		var original = SecurityContextHolder.getContext();
		List<GrantedAuthority> authorities = new ArrayList<>(original.getAuthentication().getAuthorities());
		Permissions.all().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
		SecurityContext elevated = SecurityContextHolder.createEmptyContext();
		elevated.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				original.getAuthentication().getPrincipal(), null, authorities));
		SecurityContextHolder.setContext(elevated);
		try {
			return TenantContext.callAsShop(shopId, () -> tx.execute(t -> work.get()));
		}
		finally {
			SecurityContextHolder.setContext(original);
		}
	}
}
