package com.selectcode.pos.catalog.app;

/**
 * Barcode helpers (CAT-07): which symbology a code prints in, check digits, and in-store codes for products without a
 * barcode. EAN-13/UPC-A/EAN-8 with a correct check digit print in their own format so every scanner reads them;
 * anything else prints as Code 128.
 */
public final class BarcodeFormats {

	private BarcodeFormats() {
	}

	public static String formatOf(String code) {
		if (code == null) {
			return "CODE128";
		}
		if (code.matches("\\d{13}") && validCheckDigit(code)) {
			return "EAN13";
		}
		if (code.matches("\\d{12}") && validCheckDigit(code)) {
			return "UPC";
		}
		if (code.matches("\\d{8}") && validCheckDigit(code)) {
			return "EAN8";
		}
		return "CODE128";
	}

	/** GS1 check digit (mod 10, weights 3/1 from the right, excluding the check digit). */
	public static int checkDigit(String digitsWithoutCheck) {
		int sum = 0;
		boolean three = true;
		for (int i = digitsWithoutCheck.length() - 1; i >= 0; i--) {
			int d = digitsWithoutCheck.charAt(i) - '0';
			sum += three ? d * 3 : d;
			three = !three;
		}
		return (10 - sum % 10) % 10;
	}

	public static boolean validCheckDigit(String code) {
		String body = code.substring(0, code.length() - 1);
		return checkDigit(body) == code.charAt(code.length() - 1) - '0';
	}

	/**
	 * An in-store EAN-13 for a product without a barcode: prefix "200" (GS1 restricted circulation, not used by weighing
	 * scales here) + 9-digit number + check digit.
	 */
	public static String inStoreEan13(long number) {
		String body = "200" + String.format("%09d", number % 1_000_000_000L);
		return body + checkDigit(body);
	}

	/** Allowed barcode text: printable ASCII without spaces, 3-64 characters. */
	public static boolean acceptable(String code) {
		return code != null && code.matches("[\\x21-\\x7E]{3,64}");
	}
}
