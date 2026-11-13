package com.selectcode.pos.shared.features;

import com.selectcode.pos.shared.web.ApiException;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Which modules are switched on for the current shop (docs/ARCHITECTURE.md §4). Implemented by the platform module,
 * cached per shop and evicted when a switch changes.
 */
public interface ShopFeatures {

	/** Enabled module keys of the current shop (TenantContext). */
	Set<ModuleKey> enabled();

	default boolean isOn(ModuleKey module) {
		return enabled().contains(module);
	}

	/** Throws 403 {@code module_disabled} unless the module is on for the current shop. */
	default void require(ModuleKey module) {
		if (!isOn(module)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "module_disabled",
					"\"" + module.label() + "\" is not switched on for this shop");
		}
	}
}
