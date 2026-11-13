package com.selectcode.pos.shared.security;

import java.util.List;
import java.util.Map;

/**
 * Authorities of platform (SelectCode staff) users. Every platform user has {@link #ROLE_PLATFORM}; capabilities depend
 * on the platform role (requirements §2, "Platform side").
 */
public final class PlatformAuthorities {

	public static final String ROLE_PLATFORM = "ROLE_PLATFORM";

	public static final String SHOPS_VIEW = "platform.shops.view";

	public static final String SHOPS_CREATE = "platform.shops.create";

	public static final String SHOPS_MANAGE = "platform.shops.manage";

	public static final String SHOPS_DELETE = "platform.shops.delete";

	public static final String SUPPORT = "platform.support";

	public static final String BILLING = "platform.billing";

	public static final String ADMIN = "platform.admin";

	public static final String IMPORT = "platform.import";

	/** Platform role → capabilities. */
	public static final Map<String, List<String>> BY_ROLE = Map.of(
			"SUPER_ADMIN", List.of(SHOPS_VIEW, SHOPS_CREATE, SHOPS_MANAGE, SHOPS_DELETE, SUPPORT, BILLING, ADMIN, IMPORT),
			"SUPPORT", List.of(SHOPS_VIEW, SUPPORT),
			"BILLING", List.of(SHOPS_VIEW, BILLING),
			"ONBOARDING", List.of(SHOPS_VIEW, SHOPS_CREATE, IMPORT, SUPPORT));

	private PlatformAuthorities() {
	}

	public static final String HAS_SHOPS_VIEW = "hasAuthority('" + SHOPS_VIEW + "')";

	public static final String HAS_SHOPS_CREATE = "hasAuthority('" + SHOPS_CREATE + "')";

	public static final String HAS_SHOPS_MANAGE = "hasAuthority('" + SHOPS_MANAGE + "')";

	public static final String HAS_SHOPS_DELETE = "hasAuthority('" + SHOPS_DELETE + "')";

	public static final String HAS_SUPPORT = "hasAuthority('" + SUPPORT + "')";

	public static final String HAS_BILLING = "hasAuthority('" + BILLING + "')";

	public static final String HAS_ADMIN = "hasAuthority('" + ADMIN + "')";

	public static final String HAS_IMPORT = "hasAuthority('" + IMPORT + "')";

	public static final String IS_PLATFORM = "hasAuthority('" + ROLE_PLATFORM + "')";
}
