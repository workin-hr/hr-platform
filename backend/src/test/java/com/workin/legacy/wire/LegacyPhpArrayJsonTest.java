package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * PHP's array-to-JSON rule. Every case here is one where a Java {@code Map}
 * and a PHP array disagree about the JSON <em>type</em> of the same data.
 */
class LegacyPhpArrayJsonTest {

	private static Map<String, Object> map(String... keys) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (int i = 0; i < keys.length; i++) {
			out.put(keys[i], "v" + i);
		}
		return out;
	}

	@Test
	void ordinaryNameKeysStayAnObject() {
		assertThat(LegacyPhpArrayJson.encode(map("Engineering", "Sales")))
				.isInstanceOf(Map.class);
	}

	/** The case that motivates this: one department genuinely named "0". */
	@Test
	@SuppressWarnings("unchecked")
	void aSingleZeroKeyBecomesAJsonArray() {
		Object encoded = LegacyPhpArrayJson.encode(map("0"));
		assertThat(encoded).isInstanceOf(List.class);
		assertThat((List<Object>) encoded).containsExactly("v0");
	}

	@Test
	void aCompleteZeroBasedSequenceBecomesAnArray() {
		assertThat(LegacyPhpArrayJson.encode(map("0", "1", "2"))).isInstanceOf(List.class);
	}

	/** A gap breaks the sequence, so PHP keeps the keys and emits an object. */
	@Test
	void aGapInTheSequenceStaysAnObject() {
		assertThat(LegacyPhpArrayJson.encode(map("0", "2"))).isInstanceOf(Map.class);
	}

	/** Insertion order decides it, not sorted order. */
	@Test
	void anOutOfOrderSequenceStaysAnObject() {
		assertThat(LegacyPhpArrayJson.encode(map("1", "0"))).isInstanceOf(Map.class);
	}

	@Test
	void aSequenceNotStartingAtZeroStaysAnObject() {
		assertThat(LegacyPhpArrayJson.encode(map("1", "2"))).isInstanceOf(Map.class);
	}

	@Test
	void aMixOfIntegerAndStringKeysStaysAnObject() {
		assertThat(LegacyPhpArrayJson.encode(map("0", "Sales"))).isInstanceOf(Map.class);
	}

	/**
	 * Only a <em>canonical</em> decimal integer converts. These all stay string
	 * keys in PHP, so the array keeps them and stays an object.
	 */
	@Test
	void nonCanonicalIntegerLikeKeysStayStringKeys() {
		for (String key : List.of("00", "01", "+0", " 0", "0.0", "-0", "0x0", "")) {
			assertThat(LegacyPhpArrayJson.isCanonicalInteger(key))
					.as("%s must not convert to an integer key", key.isEmpty() ? "(empty)" : key)
					.isFalse();
		}
		assertThat(LegacyPhpArrayJson.encode(map("00"))).isInstanceOf(Map.class);
	}

	/**
	 * {@code "-0"} is the trap: it looks canonical but PHP canonicalises the
	 * integer 0 back to {@code "0"}, so the two would not round-trip. This
	 * repository has met it before, in {@code LegacyQueryParameters#phpArrayKey}.
	 */
	@Test
	void negativeZeroIsNotACanonicalIntegerKey() {
		assertThat(LegacyPhpArrayJson.isCanonicalInteger("-0")).isFalse();
		assertThat(LegacyPhpArrayJson.isCanonicalInteger("-12")).isTrue();
		assertThat(LegacyPhpArrayJson.isCanonicalInteger("0")).isTrue();
	}

	/** An empty map keeps the {@code (object)[]} cast's shape. */
	@Test
	void anEmptyMapStaysAnObject() {
		assertThat(LegacyPhpArrayJson.encode(new LinkedHashMap<>())).isInstanceOf(Map.class);
	}

	/**
	 * A key above {@link Integer#MAX_VALUE} is still a canonical PHP integer, so
	 * it has to fail the sequence test rather than blow up the encoder: these
	 * keys are department and branch <em>names</em>, and nothing stops one being
	 * named {@code 2147483648}.
	 */
	@Test
	void aCanonicalKeyBeyondIntRangeStaysAnObjectInsteadOfThrowing() {
		assertThat(LegacyPhpArrayJson.isCanonicalInteger("2147483648")).isTrue();
		assertThat(LegacyPhpArrayJson.encode(map("2147483648"))).isInstanceOf(Map.class);
		assertThat(LegacyPhpArrayJson.encode(map("0", "2147483648"))).isInstanceOf(Map.class);
	}

	/**
	 * The same for a key past the 32-bit <em>unsigned</em> range, so the guard
	 * is not read as covering only the one boundary above.
	 */
	@Test
	void aFarOutOfRangeCanonicalKeyAlsoStaysAnObject() {
		assertThat(LegacyPhpArrayJson.encode(map("4294967296"))).isInstanceOf(Map.class);
	}
}
