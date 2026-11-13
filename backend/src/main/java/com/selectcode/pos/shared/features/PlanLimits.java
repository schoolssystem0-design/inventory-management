package com.selectcode.pos.shared.features;

import java.util.OptionalInt;
import org.springframework.http.HttpStatus;
import com.selectcode.pos.shared.web.ApiException;
import java.util.Map;

/**
 * Limits of the current shop's plan (BIL-08). Implemented by the platform module. Creating beyond a limit returns 402
 * {@code plan_limit}; nothing is ever deleted when a plan is lower than what the shop already has.
 */
public interface PlanLimits {

	enum Limit {
		USERS("users"), BRANCHES("branches"), PRODUCTS("products"), TILLS("tills");

		private final String key;

		Limit(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}

	/** Maximum for the current shop, empty = unlimited. */
	OptionalInt max(Limit limit);

	/** Throws 402 plan_limit if adding {@code adding} items to {@code current} would exceed the plan. */
	default void check(Limit limit, long current, long adding) {
		OptionalInt max = max(limit);
		if (max.isPresent() && current + adding > max.getAsInt()) {
			throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "plan_limit",
					"Your plan allows " + max.getAsInt() + " " + limit.key() + ". Upgrade the plan to add more.",
					Map.of("limit", limit.key(), "max", max.getAsInt()));
		}
	}
}
