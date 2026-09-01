package com.workin.legacy.wire;

import java.math.BigDecimal;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Renders whole floating-point values the way PHP's {@code json_encode} does:
 * {@code 2604}, not {@code 2604.0}.
 *
 * <h2>The difference this closes</h2>
 * <p>Legacy casts aggregates to {@code (float)} before responding -- e.g.
 * {@code leave_balances/stats.php:62} does
 * {@code (float) ($stats['total_days'] ?? 0)} -- and then
 * {@code json_encode} writes the shortest representation, which for a whole
 * float drops the fractional part. Verified against the running PHP:
 *
 * <pre>
 * 0.0     -&gt; 0
 * 2604.0  -&gt; 2604
 * 2604.5  -&gt; 2604.5
 * </pre>
 *
 * <p>Jackson writes {@code 0.0} and {@code 2604.0}. The <em>value</em> is the
 * same; the <b>JSON type</b> is not, and that is what reaches the client.
 * Dart's {@code json.decode} yields {@code int} for {@code 2604} and
 * {@code double} for {@code 2604.0}, so a client doing {@code as int} throws on
 * one and not the other.
 *
 * <p>Measured with the parity harness on 2026-08-31: every one of the 157
 * differing leaf values in {@code dashboard/stats} was this, and it was the
 * sole cause of all four differing endpoints
 * ({@code dashboard/stats}, {@code leave_balances/stats},
 * {@code payslips/list}, {@code penalties/stats}). Only whole values differ --
 * {@code 2604.5} already matched -- which is why it hides in review and shows
 * up in production, where counts and sums are usually whole.
 *
 * <h2>Scope</h2>
 * <p>Registered only under {@code phase1-mysql}, where the entire served
 * surface is the legacy contract and the tenant API is not exposed. The new
 * platform's own responses keep Jackson's defaults -- PHP's rendering is a
 * legacy compatibility obligation, not a house style.
 *
 * <p><b>Not reproduced</b>: PHP renders very large floats in exponent form
 * ({@code 1.0e+20}) where Jackson writes {@code 1.0E20}. No field on this
 * surface carries values near that magnitude -- these are day counts, money
 * sums and rates -- so it is left alone rather than guessed at. If one ever
 * does, it needs its own measurement rather than an inference from this one.
 */
@Configuration
@Profile("phase1-mysql")
public class LegacyPhpNumberJsonConfig {

	/** 2^53: above this a double cannot represent every integer, so "is it whole" stops being a safe question. */
	private static final double MAX_EXACT_INTEGRAL = 9007199254740992.0d;

	private static boolean rendersAsIntegerInPhp(double value) {
		return Double.isFinite(value)
				&& value == Math.rint(value)
				&& Math.abs(value) < MAX_EXACT_INTEGRAL;
	}

	private static final class PhpDoubleSerializer extends ValueSerializer<Double> {
		@Override
		public void serialize(Double value, JsonGenerator gen, SerializationContext context) {
			if (rendersAsIntegerInPhp(value)) {
				gen.writeNumber((long) value.doubleValue());
				return;
			}
			gen.writeNumber(value.doubleValue());
		}
	}

	private static final class PhpFloatSerializer extends ValueSerializer<Float> {
		@Override
		public void serialize(Float value, JsonGenerator gen, SerializationContext context) {
			if (rendersAsIntegerInPhp(value)) {
				gen.writeNumber((long) value.floatValue());
				return;
			}
			gen.writeNumber(value.floatValue());
		}
	}

	/**
	 * {@code BigDecimal} needs the same treatment and is the commoner case
	 * here: money and rate columns arrive as {@code DECIMAL} and are rounded
	 * into {@code BigDecimal}, so Jackson writes {@code 5000.00} where PHP --
	 * having cast to float -- writes {@code 5000}. Measured on
	 * {@code payslips/list}: 134 of the 134 remaining differences were this.
	 */
	private static final class PhpBigDecimalSerializer extends ValueSerializer<BigDecimal> {
		@Override
		public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext context) {
			BigDecimal stripped = value.stripTrailingZeros();
			if (stripped.scale() <= 0
					&& value.abs().compareTo(BigDecimal.valueOf(MAX_EXACT_INTEGRAL)) < 0) {
				gen.writeNumber(value.longValueExact());
				return;
			}
			// The stripped value, not the original: PHP casts to float and
			// json_encode writes the shortest representation, so a DECIMAL(10,2)
			// holding 100.50 arrives as `100.5` and 0.10 as `0.1`. Writing the
			// scaled original would emit `100.50` and miss parity on every
			// non-integral amount ending in a zero -- which payslip enrichment,
			// working entirely in scale-2 BigDecimals, produces constantly.
			gen.writeNumber(stripped);
		}
	}

	@Bean
	public SimpleModule legacyPhpNumberModule() {
		SimpleModule module = new SimpleModule("legacy-php-number-rendering");
		module.addSerializer(Double.class, new PhpDoubleSerializer());
		module.addSerializer(Double.TYPE, new PhpDoubleSerializer());
		module.addSerializer(Float.class, new PhpFloatSerializer());
		module.addSerializer(Float.TYPE, new PhpFloatSerializer());
		module.addSerializer(BigDecimal.class, new PhpBigDecimalSerializer());
		return module;
	}

}
