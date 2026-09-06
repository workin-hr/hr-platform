package com.workin.legacy;

/**
 * {@code AppConfig::DEFAULT_ANNUAL_LEAVE_DAYS}.
 *
 * <p>The annual leave entitlement a new {@code leave_balance} row is opened
 * with. A product default, not a per-company setting and not a deployment
 * knob: {@code leave_balances/generate.php} and {@code request_actions_helper}
 * both read the constant directly, and neither consults company settings for
 * the annual figure any more.
 *
 * <p><b>This replaced a per-company lookup.</b> Until {@code hr-legacy}
 * {@code 505004f} both sites read the {@code monthly_leave_accrual} company
 * setting and fell back to {@code 21.0}. That setting still exists and is
 * still editable, but nothing reads it for the annual entitlement -- at HEAD
 * its only remaining appearance in PHP is the enum case that names it. A port
 * that kept the old lookup would open balances at 21 days, or at whatever a
 * company had configured, where the application it replaces opens them at 15.
 *
 * <p><b>The value is checked, not copied.</b> It lived only in a git-ignored
 * {@code constants.php} on one machine until {@code a2dd5d7} added it to the
 * tracked {@code apis/config/constants.example.php}.
 * {@code scripts/check_legacy_product_defaults_drift.py} reads it back out of
 * that tracked file at {@code HEAD} and fails if this constant disagrees, so
 * the number here is derived from a commit rather than transcribed from a
 * local file nobody else can see.
 */
public final class LegacyLeavePolicy {

	/**
	 * {@code 15.0}. Kept as {@code double} because PHP casts it with
	 * {@code (float)} at both call sites and the value reaches
	 * {@code leave_balance.total_days} through the same arithmetic.
	 */
	public static final double DEFAULT_ANNUAL_LEAVE_DAYS = 15.0d;

	private LegacyLeavePolicy() {
	}

}
