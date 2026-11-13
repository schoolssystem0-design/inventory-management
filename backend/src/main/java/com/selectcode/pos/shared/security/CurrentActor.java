package com.selectcode.pos.shared.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Access to the principal of the current request. */
public final class CurrentActor {

	private CurrentActor() {
	}

	public static Optional<ActorPrincipal> get() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof ActorPrincipal principal) {
			return Optional.of(principal);
		}
		return Optional.empty();
	}

	public static ActorPrincipal require() {
		return get().orElseThrow(() -> new IllegalStateException("No signed-in actor"));
	}

	public static boolean isPlatform() {
		return hasAuthority(PlatformAuthorities.ROLE_PLATFORM);
	}

	public static boolean hasPermission(String permission) {
		return hasAuthority(permission);
	}

	public static boolean hasAuthority(String authority) {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
	}

	/** Display name of the current actor for history rows (price changes, settings changes). */
	public static String name() {
		return get().map(ActorPrincipal::displayName).orElse("System");
	}

	public static ActorType type() {
		return get().map(ActorPrincipal::actorType).orElse(ActorType.SYSTEM);
	}

	public static Long id() {
		return get().map(ActorPrincipal::actorId).orElse(null);
	}
}
