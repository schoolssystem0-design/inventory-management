package com.selectcode.pos.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Links to the web app for emails and SMS: shops live at {@code <code>.<root-domain>}, the platform portal at
 * {@code admin.<root-domain>} (docs/architecture/ARCHITECTURE.md §1).
 */
@Component
public class AppLinks {

	private final String scheme;

	private final String rootDomain;

	AppLinks(@Value("${app.web-scheme:http}") String scheme, @Value("${app.root-domain:localhost:3000}") String rootDomain) {
		this.scheme = scheme;
		this.rootDomain = rootDomain.replaceAll("^https?://", "").replaceAll("/+$", "");
	}

	public String shop(String shopCode, String path) {
		return scheme + "://" + shopCode + "." + rootDomain + path;
	}

	public String platform(String path) {
		return scheme + "://admin." + rootDomain + path;
	}
}
