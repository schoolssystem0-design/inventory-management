package com.selectcode.pos.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Links to the web app for emails and SMS: shops live at {@code <code>.<root-domain>}, the platform portal at
 * {@code admin.<root-domain>} (docs/architecture/ARCHITECTURE.md §1).
 */
@Component
public class AppLinks {

	private static final String SHOP_CODE = "^[a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?$";

	private final String scheme;

	private final String rootDomain;

	private final boolean pathBased;

	AppLinks(@Value("${app.web-scheme:http}") String scheme, @Value("${app.root-domain:localhost:3000}") String rootDomain,
			@Value("${app.path-routing:false}") boolean pathRouting) {
		this.scheme = scheme;
		this.rootDomain = rootDomain.replaceAll("^https?://", "").replaceAll("/+$", "");
		this.pathBased = pathRouting || this.rootDomain.endsWith(".vercel.app");
	}

	public String shop(String shopCode, String path) {
		String normalized = path.startsWith("/") ? path : "/" + path;
		if (pathBased) {
			return scheme + "://" + rootDomain + "/s/" + shopCode + normalized;
		}
		return scheme + "://" + shopCode + "." + rootDomain + normalized;
	}

	public String platform(String path) {
		String normalized = path == null || path.isEmpty() ? "/" : path.startsWith("/") ? path : "/" + path;
		if (pathBased) {
			if ("/login".equals(normalized) || normalized.startsWith("/login?")) {
				return scheme + "://" + rootDomain + normalized;
			}
			if ("/".equals(normalized)) {
				return scheme + "://" + rootDomain + "/platform";
			}
			if (normalized.startsWith("/platform")) {
				return scheme + "://" + rootDomain + normalized;
			}
			return scheme + "://" + rootDomain + "/platform" + normalized;
		}
		return scheme + "://" + "admin." + rootDomain + normalized;
	}

	/** Rewrites a legacy subdomain shop URL to the path form when path routing is on. */
	public String normalizeShopUrl(String url) {
		if (!pathBased || url == null || url.isBlank()) {
			return url;
		}
		try {
			java.net.URI uri = java.net.URI.create(url.trim());
			String host = uri.getHost();
			if (host == null || !host.endsWith("." + rootDomain)) {
				return url;
			}
			String code = host.substring(0, host.length() - rootDomain.length() - 1);
			if (!code.matches(SHOP_CODE) || "admin".equals(code) || "www".equals(code)) {
				return url;
			}
			String path = uri.getRawPath() == null ? "" : uri.getRawPath();
			String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
			return scheme + "://" + rootDomain + "/s/" + code + path + query;
		}
		catch (IllegalArgumentException ex) {
			return url;
		}
	}
}