package com.selectcode.pos.shared.money;

import com.selectcode.pos.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money and quantity rules (docs/architecture/CODING-STANDARDS.md §3): money is {@code BigDecimal} at 2 decimals, quantities at 3,
 * both HALF_UP, and both travel as strings in JSON. Never use double/float (ArchitectureTest).
 */
public final class Amounts {

	public static final int MONEY_SCALE = 2;

	public static final int QTY_SCALE = 3;

	public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

	private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999.99");

	private Amounts() {
	}

	public static BigDecimal money(BigDecimal value) {
		return value == null ? null : value.setScale(MONEY_SCALE, ROUNDING);
	}

	public static BigDecimal qty(BigDecimal value) {
		return value == null ? null : value.setScale(QTY_SCALE, ROUNDING);
	}

	/** Parses a money string ("3500", "3,500.50"); null/blank → null. 400 if invalid, negative or over 2 decimals. */
	public static BigDecimal parseMoney(String raw, String field) {
		BigDecimal value = parse(raw, field, "Enter an amount like 3500 or 3500.50");
		if (value == null) {
			return null;
		}
		if (value.signum() < 0) {
			throw ApiException.field(field, "negative", "Amount cannot be negative");
		}
		if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
			throw ApiException.field(field, "too_many_decimals", "Use at most 2 decimal places");
		}
		if (value.compareTo(MAX_MONEY) > 0) {
			throw ApiException.field(field, "too_large", "Amount is too large");
		}
		return money(value);
	}

	/** Parses a required money string. */
	public static BigDecimal requireMoney(String raw, String field) {
		BigDecimal value = parseMoney(raw, field);
		if (value == null) {
			throw ApiException.field(field, "required", "Enter an amount");
		}
		return value;
	}

	/** Parses a quantity string ("2.5"); null/blank → null. 400 if invalid, negative or over 3 decimals. */
	public static BigDecimal parseQty(String raw, String field) {
		BigDecimal value = parse(raw, field, "Enter a quantity like 12 or 2.5");
		if (value == null) {
			return null;
		}
		if (value.signum() < 0) {
			throw ApiException.field(field, "negative", "Quantity cannot be negative");
		}
		if (value.stripTrailingZeros().scale() > QTY_SCALE) {
			throw ApiException.field(field, "too_many_decimals", "Use at most 3 decimal places");
		}
		return qty(value);
	}

	/** Percentage 0–100 with up to 4 decimals. */
	public static BigDecimal parsePercent(String raw, String field) {
		BigDecimal value = parse(raw, field, "Enter a percentage like 17 or 17.5");
		if (value == null) {
			return null;
		}
		if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
			throw ApiException.field(field, "out_of_range", "Enter a percentage between 0 and 100");
		}
		return value.setScale(4, ROUNDING);
	}

	/** Wire format: plain string with fixed decimals ("3500.00"); null stays null. */
	public static String text(BigDecimal value) {
		return value == null ? null : money(value).toPlainString();
	}

	public static String qtyText(BigDecimal value) {
		return value == null ? null : qty(value).toPlainString();
	}

	public static String percentText(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	public static BigDecimal nz(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static BigDecimal parse(String raw, String field, String hint) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(raw.trim().replace(",", ""));
		}
		catch (NumberFormatException e) {
			throw ApiException.field(field, "invalid", hint);
		}
	}
}
