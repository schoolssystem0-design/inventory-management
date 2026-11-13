package com.selectcode.pos.iam.api;

import com.selectcode.pos.iam.ShopUserPrincipal;
import com.selectcode.pos.iam.app.AccountService;
import com.selectcode.pos.iam.app.AccountService.TotpEnrolment;
import com.selectcode.pos.iam.app.SignInService;
import com.selectcode.pos.iam.app.SignInService.SignInAttempt;
import com.selectcode.pos.shared.security.AnyAuthenticated;
import com.selectcode.pos.shared.security.PublicEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop sign-in and own-account endpoints (ACC-06..10). Sign-out is {@code POST /api/v1/auth/logout}. Who is signed in
 * and what they may do: {@code GET /api/v1/shop/context}.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	record LoginRequest(@NotBlank @Size(max = 30) String shopCode, @NotBlank @Size(max = 60) String username,
			@NotBlank @Size(max = 200) String password, @Size(max = 10) String totpCode) {
	}

	record LoginResponse(long userId, String username, String displayName, long shopId, String shopCode,
			String shopName, int idleMinutes) {
	}

	record CsrfResponse(String headerName, String token) {
	}

	record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
			@NotBlank @Size(max = 200) String newPassword) {
	}

	record TokenPasswordRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String newPassword) {
	}

	record ResetRequest(@Size(max = 30) String shopCode, @Size(max = 60) String username) {
	}

	record PinRequest(@Size(max = 200) String password, @Size(max = 6) String pin) {
	}

	record CodeRequest(@NotBlank @Size(max = 10) String code) {
	}

	record PasswordConfirm(@Size(max = 200) String password) {
	}

	private final SignInService signIn;

	private final AccountService accounts;

	private final SecurityContextRepository securityContextRepository;

	AuthController(SignInService signIn, AccountService accounts, SecurityContextRepository securityContextRepository) {
		this.signIn = signIn;
		this.accounts = accounts;
		this.securityContextRepository = securityContextRepository;
	}

	/** Issues the CSRF cookie; call once before the first state-changing request (including login). */
	@PublicEndpoint
	@GetMapping("/csrf")
	CsrfResponse csrf(CsrfToken token) {
		return new CsrfResponse(token.getHeaderName(), token.getToken());
	}

	@PublicEndpoint
	@PostMapping("/login")
	LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
		SignInService.SignedIn result = signIn.signIn(new SignInAttempt(body.shopCode(), body.username(),
				body.password(), body.totpCode(), request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
		ShopUserPrincipal principal = result.principal();
		// Session fixation protection: never reuse a pre-login session ID.
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		request.getSession().setMaxInactiveInterval(result.idleMinutes() * 60);
		return new LoginResponse(principal.userId(), principal.username(), principal.displayName(), principal.shopId(),
				principal.shopCode(), principal.shopName(), result.idleMinutes());
	}

	@AnyAuthenticated
	@PostMapping("/change-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@Valid @RequestBody ChangePasswordRequest body, HttpSession session) {
		accounts.changePassword(body.currentPassword(), body.newPassword(), session.getId());
	}

	/** Completes an account setup link (new user or shop owner) and sets the first password. */
	@PublicEndpoint
	@PostMapping("/setup")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void setup(@Valid @RequestBody TokenPasswordRequest body) {
		accounts.completeWithToken(body.token(), body.newPassword());
	}

	/** Always 202, whether or not the account exists (no account enumeration). */
	@PublicEndpoint
	@PostMapping("/password-reset/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void requestReset(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
		accounts.requestReset(body.shopCode(), body.username(), request.getRemoteAddr());
	}

	@PublicEndpoint
	@PostMapping("/password-reset/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void confirmReset(@Valid @RequestBody TokenPasswordRequest body) {
		accounts.completeWithToken(body.token(), body.newPassword());
	}

	/** Sets the user's approval PIN (password required). */
	@AnyAuthenticated
	@PostMapping("/pin")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void setPin(@Valid @RequestBody PinRequest body) {
		accounts.setPin(body.password(), body.pin());
	}

	record UnlockRequest(@Size(max = 6) String pin) {
	}

	/** Unlocks the POS lock screen with the user's own PIN (ACC-09). */
	@AnyAuthenticated
	@PostMapping("/unlock")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void unlock(@Valid @RequestBody UnlockRequest body) {
		accounts.unlock(body.pin());
	}

	@AnyAuthenticated
	@PostMapping("/totp/start")
	TotpEnrolment startTotp(HttpSession session) {
		return accounts.startTotp(session);
	}

	@AnyAuthenticated
	@PostMapping("/totp/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void confirmTotp(@Valid @RequestBody CodeRequest body, HttpSession session) {
		accounts.confirmTotp(body.code(), session);
	}

	@AnyAuthenticated
	@PostMapping("/totp/disable")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void disableTotp(@Valid @RequestBody PasswordConfirm body) {
		accounts.disableTotp(body.password());
	}
}
