package com.selectcode.pos.shared.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The money rules of selling and buying (docs/architecture/ARCHITECTURE.md §6–7, CODING-STANDARDS.md §3), kept free of the database
 * so the shared test vectors in {@code docs/test-vectors/money.json} can check them directly.
 */
public final class SaleMath {

	public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private SaleMath() {
	}

	/** qty × price at 2 decimals (1.250 kg × 180.00 = 225.00). */
	public static BigDecimal lineAmount(BigDecimal qty, BigDecimal price) {
		return Amounts.money(qty.multiply(price));
	}

	/**
	 * Spreads a bill discount over the lines in proportion to their amounts; the last line with an amount takes the
	 * rounding remainder, so the shares always add up to the discount exactly.
	 */
	public static List<BigDecimal> spread(List<BigDecimal> amounts, BigDecimal discount) {
		List<BigDecimal> shares = new ArrayList<>();
		BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		if (discount == null || discount.signum() == 0 || total.signum() == 0) {
			amounts.forEach(a -> shares.add(Amounts.money(BigDecimal.ZERO)));
			return shares;
		}
		int last = -1;
		for (int i = 0; i < amounts.size(); i++) {
			if (amounts.get(i).signum() > 0) {
				last = i;
			}
		}
		BigDecimal given = BigDecimal.ZERO;
		for (int i = 0; i < amounts.size(); i++) {
			BigDecimal share;
			if (i == last) {
				share = discount.subtract(given);
			}
			else {
				share = Amounts.money(discount.multiply(amounts.get(i)).divide(total, 10, RoundingMode.HALF_UP));
			}
			given = given.add(share);
			shares.add(Amounts.money(share));
		}
		return shares;
	}

	/** Tax on a line: added on top (EXCLUSIVE) or contained in the amount (INCLUSIVE). */
	public static BigDecimal tax(String taxMode, BigDecimal net, BigDecimal rate) {
		if (rate == null || rate.signum() == 0 || taxMode == null || "NONE".equals(taxMode)) {
			return Amounts.money(BigDecimal.ZERO);
		}
		if ("INCLUSIVE".equals(taxMode)) {
			return Amounts.money(net.multiply(rate).divide(HUNDRED.add(rate), 10, RoundingMode.HALF_UP));
		}
		return Amounts.money(net.multiply(rate).divide(HUNDRED, 10, RoundingMode.HALF_UP));
	}

	/** What the customer pays for a line: net plus tax when tax is added on top. */
	public static BigDecimal lineTotal(String taxMode, BigDecimal net, BigDecimal tax) {
		return "EXCLUSIVE".equals(taxMode) ? net.add(tax) : net;
	}

	/**
	 * Weighted average cost after receiving (PUR-04): stock below zero counts as zero, and with nothing on hand the new
	 * cost is the receipt's cost.
	 */
	public static BigDecimal averageCost(BigDecimal onHand, BigDecimal currentAvg, BigDecimal qtyIn,
			BigDecimal unitCost) {
		BigDecimal held = onHand.signum() < 0 ? BigDecimal.ZERO : onHand;
		if (held.signum() == 0 || currentAvg == null) {
			return Amounts.money(unitCost);
		}
		BigDecimal value = held.multiply(currentAvg).add(qtyIn.multiply(unitCost));
		return value.divide(held.add(qtyIn), 2, RoundingMode.HALF_UP);
	}

	/**
	 * The most that can be paid back for a return (TC-SAL-02): the return value first clears what the customer still
	 * owes on the invoice, only the rest can be refunded.
	 */
	public static BigDecimal maxRefund(BigDecimal saleTotal, BigDecimal alreadyReturned, BigDecimal paid,
			BigDecimal returnValue) {
		BigDecimal due = dueBefore(saleTotal, alreadyReturned, paid);
		BigDecimal rest = returnValue.subtract(due);
		return Amounts.money(rest.signum() > 0 ? rest : BigDecimal.ZERO);
	}

	public static BigDecimal dueBefore(BigDecimal saleTotal, BigDecimal alreadyReturned, BigDecimal paid) {
		BigDecimal due = saleTotal.subtract(alreadyReturned).subtract(paid);
		return Amounts.money(due.signum() > 0 ? due : BigDecimal.ZERO);
	}

	/**
	 * Value of returning {@code qty} of a sale line: the line total per unit, worked out as the difference of the
	 * running totals, so returns in any number of parts always add up to exactly the line total.
	 */
	public static BigDecimal returnValue(BigDecimal lineTotal, BigDecimal lineQty, BigDecimal alreadyReturnedQty,
			BigDecimal qty) {
		BigDecimal after = alreadyReturnedQty.add(qty);
		BigDecimal upToAfter = after.compareTo(lineQty) >= 0 ? lineTotal
				: Amounts.money(lineTotal.multiply(after).divide(lineQty, 10, RoundingMode.HALF_UP));
		BigDecimal upToBefore = Amounts.money(lineTotal.multiply(alreadyReturnedQty).divide(lineQty, 10, RoundingMode.HALF_UP));
		return Amounts.money(upToAfter.subtract(upToBefore));
	}

	/**
	 * Suggested order quantity (PUR-07): enough for the larger of 30 days' sales or twice the reorder level, less
	 * what is on hand and already on order; never negative.
	 */
	public static BigDecimal reorderSuggestion(BigDecimal reorderLevel, BigDecimal sold30, BigDecimal onHand,
			BigDecimal onOrder) {
		BigDecimal target = sold30.max(reorderLevel.multiply(BigDecimal.TWO));
		BigDecimal suggest = target.subtract(onHand.max(BigDecimal.ZERO)).subtract(onOrder);
		return suggest.signum() > 0 ? suggest.setScale(0, RoundingMode.CEILING) : BigDecimal.ZERO;
	}

	/** Percentage of {@code part} in {@code whole} (2 decimals); 0 when whole is 0. */
	public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
		if (whole == null || whole.signum() == 0) {
			return BigDecimal.ZERO.setScale(2);
		}
		return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
	}
}
