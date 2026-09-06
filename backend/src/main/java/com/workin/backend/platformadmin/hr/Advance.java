package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

/**
 * A row of {@code advances} as the dashboard's list and form need it
 * ({@code dashboard/pages/advances}).
 *
 * <p>{@code remaining} is what the employee still owes. It is not derived --
 * it is stored and adjusted -- which is why an edit has arithmetic rather than
 * a simple write.
 */
public record Advance(
		long id, long employeeId, long companyId, String companyName, String employeeCode,
		String employeeName, BigDecimal amount, BigDecimal remaining, String reason,
		String rejectionReason, String status, String requestDate, String createdAt) {

	public boolean isPending() {
		return "pending".equals(this.status);
	}

	public boolean isApproved() {
		return "approved".equals(this.status);
	}

	public boolean isRejected() {
		return "rejected".equals(this.status);
	}

	/**
	 * {@code $status === REQ_REJECTED || ($status === REQ_APPROVED && $remaining <= 0)}.
	 *
	 * <p>A rejected advance is finished, and an approved one with nothing left
	 * to repay is finished too. Neither can be edited: there is no amount left
	 * for a change to mean anything about.
	 */
	public boolean isSettled() {
		if (isRejected()) {
			return true;
		}
		return isApproved() && (this.remaining == null || this.remaining.signum() <= 0);
	}

	/**
	 * The {@code remaining} an edit should store, given the new amount.
	 *
	 * <p>Two cases, and they are genuinely different:
	 *
	 * <ul>
	 * <li><b>Still pending</b> -- nothing has been repaid, so the balance is
	 *     simply the new amount.</li>
	 * <li><b>Already approved</b> -- some of it may have been repaid, and that
	 *     repayment must survive the edit. The balance moves by the
	 *     <em>delta</em>, floored at zero so a reduction cannot make the
	 *     employee a creditor, and capped at the new amount so an increase
	 *     cannot leave them owing more than was advanced.</li>
	 * </ul>
	 *
	 * <p>Reducing an advance from 1000 to 300 when 400 has been repaid gives
	 * {@code min(max(0, 600 + (300 - 1000)), 300) = 0}: the debt is cleared
	 * rather than going negative.
	 */
	public static BigDecimal remainingAfterEdit(
			String status, BigDecimal oldRemaining, BigDecimal oldAmount, BigDecimal newAmount) {
		if ("pending".equals(status)) {
			return newAmount;
		}
		// `(float) ($advRow['remaining'] ?? 0)`. The `??` guards a missing array
		// key, not a meaningful null: `remaining` is `decimal(10,2) NOT NULL`
		// with no default and every insert sets it, so a null cannot reach here
		// from the database. Kept only because this is a public method and PHP
		// has the same coalesce.
		BigDecimal remaining = oldRemaining == null ? BigDecimal.ZERO : oldRemaining;
		BigDecimal previous = oldAmount == null ? BigDecimal.ZERO : oldAmount;
		BigDecimal adjusted = remaining.add(newAmount.subtract(previous));
		if (adjusted.signum() < 0) {
			adjusted = BigDecimal.ZERO;
		}
		return adjusted.min(newAmount);
	}

	/** {@code (float) $raw}: the leading number, or 0. */
	public static BigDecimal amount(String raw) {
		if (raw == null) {
			return BigDecimal.ZERO;
		}
		String trimmed = raw.trim();
		int end = 0;
		if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
			end++;
		}
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		if (end < trimmed.length() && trimmed.charAt(end) == '.') {
			end++;
			while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
				end++;
			}
		}
		String number = trimmed.substring(0, end);
		if (number.isEmpty() || "+".equals(number) || "-".equals(number) || ".".equals(number)) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(number);
		} catch (NumberFormatException ex) {
			return BigDecimal.ZERO;
		}
	}

	public String amountDisplay() {
		return plain(this.amount);
	}

	public String remainingDisplay() {
		return plain(this.remaining);
	}

	private static String plain(BigDecimal value) {
		return value == null ? "0" : value.stripTrailingZeros().toPlainString();
	}

	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

}
