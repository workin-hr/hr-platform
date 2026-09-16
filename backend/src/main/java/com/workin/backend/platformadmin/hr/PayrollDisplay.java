package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.PhpMath;

/**
 * How {@code payroll/page.php} prints a payslip's figures: every amount through {@code $fmtPay},
 * which is {@code number_format($v, 0)}, and overtime hours to one decimal. A missing value is
 * legacy's {@code ?? 0}, so it prints as zero rather than a dash.
 *
 * <p>A payslip row mixes stored decimals, computed ones and numeric strings, as PHP's rows do, so
 * each value goes through {@code (float)}'s conversion first.
 */
public final class PayrollDisplay {

	private PayrollDisplay() {
	}

	/** {@code $fmtPay((float) ($v ?? 0))}: whole pounds, grouped in threes. */
	public static String money(Object value) {
		return PhpMath.numberFormat(value == null ? 0d : LegacyValues.toPhpDecimal(value).doubleValue());
	}

	/**
	 * {@code number_format((float) ($v ?? 0), 1)}. The column is {@code decimal(5,1)}, so this
	 * rounds nothing that is stored; it groups the thousands and always shows the one place.
	 */
	public static String oneDecimal(Object value) {
		BigDecimal number = value == null ? BigDecimal.ZERO : LegacyValues.toPhpDecimal(value);
		return String.format(Locale.ROOT, "%,.1f", number.setScale(1, RoundingMode.HALF_UP));
	}

}
