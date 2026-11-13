package com.selectcode.pos.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * Business dates (docs/DATA-MODEL.md §1): stored in UTC, but "today", same-day cancellation and daily reports follow
 * the shop's time zone. Every Phase 1 shop is in Pakistan.
 */
@Component
public class ShopTime {

	public static final ZoneId ZONE = ZoneId.of("Asia/Karachi");

	/** Demo and QA data are made "as of" past days; normal requests never set this. */
	private static final ThreadLocal<LocalDate> AS_OF = new ThreadLocal<>();

	private final Clock clock;

	ShopTime(Clock clock) {
		this.clock = clock;
	}

	public LocalDate today() {
		LocalDate asOf = AS_OF.get();
		return asOf != null ? asOf : LocalDate.now(clock.withZone(ZONE));
	}

	/** Runs {@code work} with "today" set to {@code date} on this thread (seeding history only). */
	public static void asOf(LocalDate date, Runnable work) {
		LocalDate previous = AS_OF.get();
		AS_OF.set(date);
		try {
			work.run();
		}
		finally {
			if (previous == null) {
				AS_OF.remove();
			}
			else {
				AS_OF.set(previous);
			}
		}
	}

	public Instant now() {
		return clock.instant();
	}

	public static LocalDate dateOf(Instant instant) {
		return instant.atZone(ZONE).toLocalDate();
	}

	/** Start of a business date as an instant. */
	public static Instant startOf(LocalDate date) {
		return date.atStartOfDay(ZONE).toInstant();
	}
}
