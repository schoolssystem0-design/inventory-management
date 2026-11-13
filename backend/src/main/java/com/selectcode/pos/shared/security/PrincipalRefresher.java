package com.selectcode.pos.shared.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

/**
 * Re-checks a session principal on every request (account still active, shop not suspended) and returns its current
 * authorities. Deactivation, suspension and role changes therefore take effect on the next click. Implemented by the
 * iam module (shop users) and the platform module (platform users).
 */
public interface PrincipalRefresher {

	/** Why writes are refused for this request, or none. */
	record WriteBlock(int status, String code, String message) {
	}

	record Refreshed(boolean valid, Collection<? extends GrantedAuthority> authorities, boolean mustChangePassword,
			WriteBlock writeBlock) {

		public Refreshed(boolean valid, Collection<? extends GrantedAuthority> authorities, boolean mustChangePassword) {
			this(valid, authorities, mustChangePassword, null);
		}

		public static Refreshed invalid() {
			return new Refreshed(false, java.util.List.of(), false, null);
		}
	}

	boolean supports(ActorPrincipal principal);

	/** Called with the principal's shop already in TenantContext. */
	Refreshed refresh(ActorPrincipal principal);
}
