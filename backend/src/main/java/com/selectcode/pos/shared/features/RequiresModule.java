package com.selectcode.pos.shared.features;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The endpoint (or every endpoint of the controller) belongs to a switchable module: the call is refused with 403
 * {@code module_disabled} when the module is off for the shop. Hiding a menu item in the app is cosmetic; this is the
 * gate.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequiresModule {

	ModuleKey value();
}
