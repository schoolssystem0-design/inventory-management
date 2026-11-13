package com.selectcode.pos.sales.app;

import com.selectcode.pos.shared.money.Amounts;
import com.selectcode.pos.shared.money.SaleMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Offers and coupons (SUP-05, XSL-02): which offer applies to each line and to the bill, and how much it takes off.
 * Pure arithmetic, no database: the till runs the same rules in {@code frontend/src/features/pos/promotions.ts} and
 * both are checked against {@code docs/test-vectors/promotions.json}.
 *
 * <p>Rules: an item offer applies only to a line sold in the base unit at the list price with no discount typed by the
 * cashier (what the cashier types always wins); the best offer per line applies, never two together; ties go to the
 * older offer. A bill offer applies when the bill (after line discounts) reaches its minimum and no bill discount was
 * typed. Coupon offers apply only when their code is entered.
 */
public final class PromotionEngine {

	public static final Set<String> LINE_KINDS = Set.of("PERCENT", "AMOUNT", "BUY_GET", "MULTI_PRICE");

	public static final Set<String> BILL_KINDS = Set.of("BILL_PERCENT", "BILL_AMOUNT");

	/**
	 * @param days ISO days of the week (1 = Monday); empty means every day
	 * @param fromTime and {@code toTime}: a daily window (happy hour); both null means all day; a window that ends
	 *     before it starts runs past midnight
	 */
	public record Offer(long id, String name, String kind, BigDecimal value, BigDecimal buyQty, BigDecimal getQty,
			BigDecimal minAmount, String scope, Set<Long> categoryIds, Set<Long> productIds, LocalDate startsOn,
			LocalDate endsOn, Set<Integer> days, LocalTime fromTime, LocalTime toTime, String couponCode,
			Integer maxUses, int uses, boolean active) {

		boolean coupon() {
			return couponCode != null && !couponCode.isBlank();
		}
	}

	/**
	 * @param eligible base unit, list price and no typed discount
	 */
	public record LineIn(long productId, Long categoryId, BigDecimal qty, BigDecimal price, boolean eligible) {
	}

	/** {@code offerId} null and amount 0 when nothing applies. */
	public record Applied(Long offerId, String name, BigDecimal amount) {

		static final Applied NONE = new Applied(null, null, BigDecimal.ZERO.setScale(2));
	}

	public record Result(List<Applied> lines, Applied bill, boolean couponUsed) {
	}

	private PromotionEngine() {
	}

	/** Offers running at this date and time (dates, days of the week, hours, active, use limit). */
	public static boolean running(Offer o, LocalDate date, LocalTime time) {
		if (!o.active()) {
			return false;
		}
		if (o.startsOn() != null && date.isBefore(o.startsOn()) || o.endsOn() != null && date.isAfter(o.endsOn())) {
			return false;
		}
		if (o.days() != null && !o.days().isEmpty() && !o.days().contains(date.getDayOfWeek().getValue())) {
			return false;
		}
		if (o.fromTime() != null && o.toTime() != null) {
			boolean inside = o.fromTime().isBefore(o.toTime())
					? !time.isBefore(o.fromTime()) && time.isBefore(o.toTime())
					: !time.isBefore(o.fromTime()) || time.isBefore(o.toTime());
			if (!inside) {
				return false;
			}
		}
		return o.maxUses() == null || o.uses() < o.maxUses();
	}

	static boolean covers(Offer o, LineIn line) {
		return switch (o.scope()) {
			case "CATEGORY" -> line.categoryId() != null && o.categoryIds().contains(line.categoryId());
			case "PRODUCTS" -> o.productIds().contains(line.productId());
			default -> true;
		};
	}

	/** What an item offer takes off a line (never more than the line). */
	public static BigDecimal lineAmount(Offer o, LineIn line) {
		BigDecimal gross = SaleMath.lineAmount(line.qty(), line.price());
		BigDecimal off = switch (o.kind()) {
			case "PERCENT" -> Amounts.money(gross.multiply(o.value()).divide(SaleMath.HUNDRED));
			case "AMOUNT" -> Amounts.money(o.value().multiply(line.qty()));
			case "BUY_GET" -> {
				BigDecimal group = o.buyQty().add(o.getQty());
				BigDecimal free = line.qty().divide(group, 0, RoundingMode.DOWN).multiply(o.getQty());
				yield Amounts.money(free.multiply(line.price()));
			}
			case "MULTI_PRICE" -> {
				BigDecimal groups = line.qty().divide(o.buyQty(), 0, RoundingMode.DOWN);
				BigDecimal normal = o.buyQty().multiply(line.price());
				BigDecimal saving = normal.subtract(o.value());
				yield saving.signum() <= 0 ? BigDecimal.ZERO : Amounts.money(groups.multiply(saving));
			}
			default -> BigDecimal.ZERO;
		};
		return Amounts.money(off.min(gross).max(BigDecimal.ZERO));
	}

	/** What a bill offer takes off the bill (after line discounts), or zero below its minimum. */
	public static BigDecimal billAmount(Offer o, BigDecimal bill) {
		if (bill.compareTo(o.minAmount() == null ? BigDecimal.ZERO : o.minAmount()) < 0 || bill.signum() <= 0) {
			return BigDecimal.ZERO.setScale(2);
		}
		BigDecimal off = "BILL_PERCENT".equals(o.kind()) ? Amounts.money(bill.multiply(o.value()).divide(SaleMath.HUNDRED))
				: Amounts.money(o.value());
		return Amounts.money(off.min(bill));
	}

	/**
	 * Applies the offers to a bill.
	 * @param offers running offers (see {@link #running}); coupon offers are used only when {@code coupon} matches
	 * @param lineDiscounts discount typed on each line (to work out the bill after line discounts)
	 * @param billDiscountTyped a bill discount was typed: no bill offer
	 */
	public static Result apply(List<Offer> offers, List<LineIn> lines, List<BigDecimal> lineDiscounts,
			boolean billDiscountTyped, String coupon) {
		String code = coupon == null || coupon.isBlank() ? null : coupon.trim().toUpperCase();
		List<Offer> usable = offers.stream().filter(o -> !o.coupon() || o.couponCode().trim().toUpperCase().equals(code))
			.toList();
		boolean couponUsed = false;
		List<Applied> applied = new ArrayList<>();
		BigDecimal afterLine = BigDecimal.ZERO;
		for (int i = 0; i < lines.size(); i++) {
			LineIn line = lines.get(i);
			Applied best = Applied.NONE;
			Offer bestOffer = null;
			if (line.eligible()) {
				for (Offer o : usable) {
					if (!LINE_KINDS.contains(o.kind()) || !covers(o, line)) {
						continue;
					}
					BigDecimal amount = lineAmount(o, line);
					if (amount.compareTo(best.amount()) > 0
							|| amount.signum() > 0 && amount.compareTo(best.amount()) == 0 && o.id() < best.offerId()) {
						best = new Applied(o.id(), o.name(), amount);
						bestOffer = o;
					}
				}
			}
			if (bestOffer != null && bestOffer.coupon()) {
				couponUsed = true;
			}
			applied.add(best);
			BigDecimal typed = lineDiscounts.get(i) == null ? BigDecimal.ZERO : lineDiscounts.get(i);
			afterLine = afterLine.add(SaleMath.lineAmount(line.qty(), line.price()).subtract(typed).subtract(best.amount()));
		}
		Applied bill = Applied.NONE;
		Offer billOffer = null;
		if (!billDiscountTyped) {
			for (Offer o : usable) {
				if (!BILL_KINDS.contains(o.kind())) {
					continue;
				}
				BigDecimal amount = billAmount(o, afterLine);
				if (amount.compareTo(bill.amount()) > 0
						|| amount.signum() > 0 && amount.compareTo(bill.amount()) == 0 && o.id() < bill.offerId()) {
					bill = new Applied(o.id(), o.name(), amount);
					billOffer = o;
				}
			}
		}
		if (billOffer != null && billOffer.coupon()) {
			couponUsed = true;
		}
		return new Result(applied, bill, couponUsed);
	}
}
