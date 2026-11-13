package com.selectcode.pos.catalog.app;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Weighing-scale labels (SUP-03, HW-05): EAN-13 starting 20–29, then the 5-digit item code (the product's scale
 * code), a 5-digit value and the check digit. The value is the weight in grams or the price in whole rupees,
 * as the shop sets ({@code scale_value_kind}). The till reads them the same way
 * ({@code frontend/src/features/pos/scale.ts}); both are checked against {@code docs/test-vectors/promotions.json}.
 */
public final class ScaleBarcode {

	/**
	 * @param value kilograms (3 decimals) for WEIGHT, rupees (2 decimals) for PRICE
	 */
	public record Reading(String itemCode, BigDecimal value) {
	}

	private ScaleBarcode() {
	}

	public static Optional<Reading> read(String barcode, String mode) {
		if (barcode == null) {
			return Optional.empty();
		}
		String b = barcode.trim();
		if (!b.matches("2[0-9]{12}") || checkDigit(b.substring(0, 12)) != b.charAt(12) - '0') {
			return Optional.empty();
		}
		String code = b.substring(2, 7);
		BigDecimal raw = new BigDecimal(b.substring(7, 12));
		BigDecimal value = "PRICE".equals(mode) ? raw.setScale(2) : raw.movePointLeft(3).setScale(3);
		return Optional.of(new Reading(code, value));
	}

	static int checkDigit(String twelve) {
		int sum = 0;
		for (int i = 0; i < 12; i++) {
			int d = twelve.charAt(i) - '0';
			sum += i % 2 == 0 ? d : d * 3;
		}
		return (10 - sum % 10) % 10;
	}
}
