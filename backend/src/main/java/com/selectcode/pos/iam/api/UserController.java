package com.selectcode.pos.iam.api;

import com.selectcode.pos.iam.app.SecurityLogQueries;
import com.selectcode.pos.iam.app.SecurityLogQueries.AuditEntry;
import com.selectcode.pos.iam.app.SecurityLogQueries.LoginEventView;
import com.selectcode.pos.iam.app.SecurityLogQueries.PermissionGroup;
import com.selectcode.pos.iam.app.SecurityLogQueries.RoleView;
import com.selectcode.pos.iam.app.UserAdminService;
import com.selectcode.pos.iam.app.UserAdminService.PasswordReset;
import com.selectcode.pos.iam.app.UserAdminService.UserCreated;
import com.selectcode.pos.iam.app.UserAdminService.UserInput;
import com.selectcode.pos.iam.app.UserAdminService.UserView;
import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.security.Permissions;
import com.selectcode.pos.shared.web.PageResponse;
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

/** Users, roles, login history and activity history (ACC-01..08). */
@RestController
@RequestMapping("/api/v1")
class UserController {

	record ResetPasswordRequest(Boolean byLink) {
	}

	private final UserAdminService users;

	private final SecurityLogQueries logs;

	UserController(UserAdminService users, SecurityLogQueries logs) {
		this.users = users;
		this.logs = logs;
	}

	@PreAuthorize(Permissions.HAS_USERS_VIEW)
	@GetMapping("/users")
	PageResponse<UserView> users(@RequestParam(required = false) String q,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return users.list(q, status, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(Permissions.HAS_USERS_VIEW)
	@GetMapping("/users/{id}")
	UserView user(@PathVariable long id) {
		return users.get(id);
	}

	@PreAuthorize(Permissions.HAS_USERS_MANAGE)
	@PostMapping("/users")
	@ResponseStatus(HttpStatus.CREATED)
	UserCreated createUser(@RequestBody UserInput body) {
		return users.create(body);
	}

	@PreAuthorize(Permissions.HAS_USERS_MANAGE)
	@PatchMapping("/users/{id}")
	UserView updateUser(@PathVariable long id, @RequestBody UserInput body) {
		return users.update(id, body);
	}

	@PreAuthorize(Permissions.HAS_USERS_MANAGE)
	@PostMapping("/users/{id}/activate")
	UserView activateUser(@PathVariable long id) {
		return users.setActive(id, true);
	}

	@PreAuthorize(Permissions.HAS_USERS_MANAGE)
	@PostMapping("/users/{id}/deactivate")
	UserView deactivateUser(@PathVariable long id) {
		return users.setActive(id, false);
	}

	/** Temporary password shown once (default) or a reset link by email ({@code byLink: true}). */
	@PreAuthorize(Permissions.HAS_USERS_MANAGE)
	@PostMapping("/users/{id}/reset-password")
	PasswordReset resetPassword(@PathVariable long id, @RequestBody(required = false) ResetPasswordRequest body) {
		return users.resetPassword(id, body != null && Boolean.TRUE.equals(body.byLink()));
	}

	@PreAuthorize(Permissions.HAS_USERS_VIEW)
	@GetMapping("/roles")
	List<RoleView> roles() {
		return logs.roles();
	}

	@AnyAuthenticated
	@GetMapping("/permissions")
	List<PermissionGroup> permissions() {
		return logs.permissionCatalogue();
	}

	@PreAuthorize(Permissions.HAS_LOGIN_HISTORY_VIEW)
	@GetMapping("/login-events")
	PageResponse<LoginEventView> loginEvents(@RequestParam(required = false) Long userId,
			@RequestParam(required = false) String result, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return logs.logins(userId, result, PageResponse.page(page), PageResponse.size(size));
	}

	@PreAuthorize(Permissions.HAS_AUDIT_VIEW)
	@GetMapping("/audit")
	PageResponse<AuditEntry> audit(@RequestParam(required = false) String entityType,
			@RequestParam(required = false) Long entityId, @RequestParam(required = false) String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return logs.audit(entityType, entityId, q, PageResponse.page(page), PageResponse.size(size));
	}
}
