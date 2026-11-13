package com.selectcode.pos.shared.security;

import com.selectcode.pos.shared.tenant.TenantContext;
import com.selectcode.pos.shared.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * For every authenticated request: sets the shop (tenant) from the session principal, re-validates the principal and
 * loads its current permissions, and applies the request-wide rules:
 * <ul>
 * <li>password change required → only {@code /api/v1/auth/**}</li>
 * <li>shop API without a shop (platform user outside support mode) → 403 {@code no_shop}</li>
 * <li>write block (read-only shop, read-only support session) → state-changing calls refused</li>
 * </ul>
 */
public class RequestPrincipalFilter extends OncePerRequestFilter {

	public static final String MUST_CHANGE_PASSWORD_ATTRIBUTE = "pos.mustChangePassword";

	public static final String WRITE_BLOCK_ATTRIBUTE = "pos.writeBlock";

	private static final String AUTH = "/api/v1/auth/";

	private static final String PLATFORM = "/api/v1/platform/";

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final List<PrincipalRefresher> refreshers;

	private final JsonMapper json;

	private final com.selectcode.pos.shared.web.RateLimiter rateLimiter;

	/** API calls per signed-in user per minute (docs/API-CONVENTIONS.md §5, OPS-13). */
	static final int REQUESTS_PER_MINUTE = 600;

	public RequestPrincipalFilter(List<PrincipalRefresher> refreshers, JsonMapper json,
			com.selectcode.pos.shared.web.RateLimiter rateLimiter) {
		this.refreshers = refreshers;
		this.json = json;
		this.rateLimiter = rateLimiter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof ActorPrincipal principal)) {
			chain.doFilter(request, response);
			return;
		}
		Long shopId = principal.tenantShopId();
		if (shopId != null) {
			TenantContext.set(shopId);
		}
		try {
			PrincipalRefresher.Refreshed refreshed = refreshers.stream()
				.filter(r -> r.supports(principal))
				.findFirst()
				.map(r -> r.refresh(principal))
				.orElse(PrincipalRefresher.Refreshed.invalid());

			if (!refreshed.valid()) {
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
				SecurityContextHolder.clearContext();
				write(response, Problems.of(HttpStatus.UNAUTHORIZED, "session_expired",
						"Your session has ended. Please sign in again."));
				return;
			}

			// Fresh authorities for this request only (not saved to the session).
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
					refreshed.authorities()));
			SecurityContextHolder.setContext(context);
			request.setAttribute(MUST_CHANGE_PASSWORD_ATTRIBUTE, refreshed.mustChangePassword());
			request.setAttribute(WRITE_BLOCK_ATTRIBUTE, refreshed.writeBlock());

			String path = request.getRequestURI();
			if (refreshed.mustChangePassword() && !path.startsWith(AUTH)) {
				write(response, Problems.of(HttpStatus.FORBIDDEN, "password_change_required",
						"Change your password to continue"));
				return;
			}
			boolean shopApi = path.startsWith("/api/v1/") && !path.startsWith(AUTH) && !path.startsWith(PLATFORM)
					&& !path.startsWith("/api/v1/public/") && !path.startsWith("/api/v1/dev/");
			if (shopApi && shopId == null) {
				write(response, Problems.of(HttpStatus.FORBIDDEN, "no_shop", "Open a shop first"));
				return;
			}
			try {
				rateLimiter.check("api:" + principal.actorType() + ":" + principal.actorId(), REQUESTS_PER_MINUTE,
						java.time.Duration.ofMinutes(1));
			}
			catch (com.selectcode.pos.shared.web.ApiException e) {
				write(response, Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
						"Too many requests. Please slow down and try again in a minute."));
				return;
			}
			PrincipalRefresher.WriteBlock block = refreshed.writeBlock();
			if (block != null && shopApi && !SAFE_METHODS.contains(request.getMethod())) {
				write(response, Problems.of(HttpStatusCode.valueOf(block.status()), block.code(), block.message()));
				return;
			}
			chain.doFilter(request, response);
		}
		finally {
			TenantContext.clear();
		}
	}

	private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), problem);
	}
}
