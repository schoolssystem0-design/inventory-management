package com.selectcode.pos.shared.tenant;

import java.util.Optional;

/**
 * Read access to the platform's shop registry for modules that must not depend on the platform module (avoids a
 * platform ↔ iam cycle). Implemented by the platform module.
 */
public interface ShopLookup {

	/**
	 * @param status TRIAL, ACTIVE, GRACE, READ_ONLY, SUSPENDED or CLOSED
	 */
	record ShopRef(long id, String code, String name, String status, String typeKey) {

		/** Users may sign in (read-only shops can still sign in to view and export). */
		public boolean canSignIn() {
			return !"SUSPENDED".equals(status) && !"CLOSED".equals(status);
		}

		/** Sales, stock and settings may change. */
		public boolean writable() {
			return "TRIAL".equals(status) || "ACTIVE".equals(status) || "GRACE".equals(status);
		}
	}

	Optional<ShopRef> findByCode(String code);

	Optional<ShopRef> findById(long id);
}
