package com.selectcode.pos.shared.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holds the shop (tenant) of the current request or job.
 *
 * <p>Set only by {@code RequestPrincipalFilter} (from the authenticated session), by sign-in before the user is known,
 * or by platform code. Never set it from client input. Always cleared in {@code finally}.
 */
public final class TenantContext {

	private record State(Long shopId, boolean allShops) {
	}

	private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static Optional<Long> shopId() {
		State state = CURRENT.get();
		return state == null ? Optional.empty() : Optional.ofNullable(state.shopId());
	}

	public static Long requireShopId() {
		return shopId().orElseThrow(() -> new IllegalStateException("No shop (tenant) in context"));
	}

	public static boolean isAllShops() {
		State state = CURRENT.get();
		return state != null && state.allShops();
	}

	/** For the request filter only. Application code uses {@link #callAsShop}. */
	public static void set(Long shopId) {
		CURRENT.set(new State(shopId, false));
	}

	/** For the request filter only. */
	public static void clear() {
		CURRENT.remove();
	}

	/** Runs {@code work} as the given shop, restoring the previous context afterwards. */
	public static <T> T callAsShop(long shopId, Supplier<T> work) {
		return callWith(new State(shopId, false), work);
	}

	public static void runAsShop(long shopId, Runnable work) {
		callAsShop(shopId, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * Runs {@code work} with access to every shop. For platform cross-shop queries only (counts and totals); callers
	 * must have verified a platform principal first.
	 */
	public static <T> T callAcrossAllShops(Supplier<T> work) {
		return callWith(new State(null, true), work);
	}

	private static <T> T callWith(State state, Supplier<T> work) {
		State previous = CURRENT.get();
		CURRENT.set(state);
		try {
			return work.get();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
	}
}
