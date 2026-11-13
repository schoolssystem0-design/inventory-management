package com.selectcode.pos.shared.security;

import com.selectcode.pos.shared.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Session-cookie security for the Next.js app (docs/SECURITY.md): server-side sessions (Spring Session JDBC), CSRF
 * double-submit cookie, CORS restricted to our domains, JSON problem responses for 401/403.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

	public static final String SESSION_COOKIE = "POS_SESSION";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json,
			java.util.List<PrincipalRefresher> refreshers, com.selectcode.pos.shared.web.RateLimiter rateLimiter)
			throws Exception {
		http.cors(cors -> {
		})
			// CSRF: cookie + raw token returned by GET /api/v1/auth/csrf, which the app sends as X-XSRF-TOKEN.
			.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository())
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.requestCache(cache -> cache.disable())
			.securityContext(context -> context.securityContextRepository(securityContextRepository()))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
				.requestMatchers("/v3/api-docs/**").permitAll()
				.requestMatchers("/api/v1/auth/login", "/api/v1/auth/csrf", "/api/v1/auth/password-reset/**",
						"/api/v1/auth/setup")
				.permitAll()
				.requestMatchers("/api/v1/platform/auth/login", "/api/v1/platform/auth/totp/enroll").permitAll()
				.requestMatchers("/api/v1/platform/**").hasAuthority(PlatformAuthorities.ROLE_PLATFORM)
				.requestMatchers("/api/v1/public/**", "/api/v1/dev/**").permitAll()
				.requestMatchers("/error").permitAll()
				.anyRequest().authenticated())
			.logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
				.deleteCookies(SESSION_COOKIE))
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, ex) -> writeProblem(response, json,
						Problems.of(HttpStatus.UNAUTHORIZED, "not_signed_in", "Sign in to continue")))
				.accessDeniedHandler((request, response, ex) -> writeProblem(response, json,
						Problems.of(HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this action"))))
			// API answers are JSON or files: never framed, no base URL, no forms.
			.headers(headers -> headers
				.referrerPolicy(r -> r.policy(
						org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				.contentSecurityPolicy(c -> c.policyDirectives("frame-ancestors 'none'; base-uri 'none'; form-action 'none'")))
			.addFilterBefore(new RequestPrincipalFilter(refreshers, json, rateLimiter), AuthorizationFilter.class);
		return http.build();
	}

	private static CookieCsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
		repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").path("/"));
		return repository;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.allowedOrigins());
		config.setAllowedOriginPatterns(properties.allowedOriginPatterns());
		config.setAllowedMethods(java.util.List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(java.util.List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "X-Request-Id",
				"X-Approval-User", "X-Approval-Pin", "If-None-Match"));
		config.setExposedHeaders(java.util.List.of("X-Request-Id", "ETag", "Content-Disposition"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	CookieSerializer cookieSerializer(SecurityProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName(SESSION_COOKIE);
		serializer.setCookiePath("/");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(properties.cookieSecure());
		serializer.setSameSite("Lax");
		if (StringUtils.hasText(properties.cookieDomain())) {
			serializer.setDomainName(properties.cookieDomain());
		}
		return serializer;
	}

	private static void writeProblem(HttpServletResponse response, JsonMapper json, ProblemDetail problem)
			throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), problem);
	}
}
