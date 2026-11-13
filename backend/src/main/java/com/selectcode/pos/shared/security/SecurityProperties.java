package com.selectcode.pos.shared.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedOrigins exact browser origins allowed to call the API with credentials
 * @param allowedOriginPatterns origin patterns such as {@code https://*.selectcodepos.com} (every shop subdomain)
 * @param cookieDomain session cookie domain, e.g. {@code .selectcodepos.com}; blank = host-only (local development)
 * @param cookieSecure whether cookies are marked Secure (true everywhere except local http)
 * @param signinLimitPerNetwork sign-in attempts allowed per IP address in 5 minutes
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(List<String> allowedOrigins, List<String> allowedOriginPatterns, String cookieDomain,
		boolean cookieSecure, Integer signinLimitPerNetwork) {

	public SecurityProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
		allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
		signinLimitPerNetwork = signinLimitPerNetwork == null ? 30 : signinLimitPerNetwork;
	}
}
