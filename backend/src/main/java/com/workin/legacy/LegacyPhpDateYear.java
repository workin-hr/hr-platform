package com.workin.legacy;

import java.time.LocalDate;

/**
 * {@code (int) date('Y', strtotime($value))}, over
 * {@link LegacyPhpStrtotime}'s bounded grammar.
 *
 * <p>{@code employees/create.php} derives the leave-balance year this way. This
 * class exists so that dependency is a named, tested compatibility boundary
 * instead of an expression buried in a service, and so the grammar itself has
 * one home: {@code employee_excel_normalize_date_value()} needs the same
 * parsing for whole dates, and a second reading of {@code strtotime()} would
 * eventually disagree with this one.
 *
 * <h2>Why a rejection is the parity-correct outcome</h2>
 * <p>When {@code strtotime()} returns {@code false}, {@code date('Y', false)}
 * raises a {@code TypeError} under {@code strict_types=1}, which inside
 * create's transaction rolls the whole insert back and answers 500.
 * Manufacturing a year for an input PHP rejects would turn a rejected request
 * into a stored employee, so anything outside the measured grammar throws here
 * too.
 *
 * <p>{@link LegacyPhpStrtotime} documents the grammar and the divergence it
 * admits. Two of its measured behaviours are worth repeating because they are
 * counter-intuitive: a bare {@code 2026} is <em>today</em> (20:26 is a valid
 * time) while a bare {@code 1990} is the year 1990 (19:90 is not), and month or
 * day zero rolls backwards rather than failing, so {@code 0000-00-00} resolves
 * to 30 November of year -1 -- the {@code -0001} PHP renders.
 */
public final class LegacyPhpDateYear {

	/** PHP's message when {@code date()} is handed {@code strtotime()}'s {@code false}. */
	public static final String TYPE_ERROR_MESSAGE =
			"date(): Argument #2 ($timestamp) must be of type ?int, false given";

	private LegacyPhpDateYear() {
	}

	/**
	 * @param raw the value {@code $hire_date} holds
	 * @param today the current date under legacy's configured offset -- see
	 *        {@link LegacyClock}, which is what makes the relative keywords match
	 * @return the year {@code (int) date('Y', strtotime($raw))} produces
	 * @throws LegacyPhpDateException when {@code strtotime()} would return
	 *         {@code false}, carrying the message PHP's {@code TypeError} does
	 */
	public static int of(String raw, LocalDate today) {
		LocalDate parsed = LegacyPhpStrtotime.dateOf(raw, today);
		if (parsed == null) {
			throw new LegacyPhpDateException();
		}
		return parsed.getYear();
	}

	/**
	 * What {@code strtotime()} returning {@code false} costs: PHP raises a
	 * {@code TypeError} from {@code date()}, and the caller's transaction rolls
	 * back around it.
	 */
	public static class LegacyPhpDateException extends RuntimeException {

		public LegacyPhpDateException() {
			super(TYPE_ERROR_MESSAGE);
		}

	}

}
