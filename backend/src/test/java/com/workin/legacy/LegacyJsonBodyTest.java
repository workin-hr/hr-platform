package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The byte- and parser-level half of {@code body()}, measured against PHP 8.3.
 *
 * <p>These two edges decide whether a request reaches the endpoint at all, so
 * they are asserted on raw bytes rather than through a string the JVM has
 * already sanitised.
 */
class LegacyJsonBodyTest {

	@Test
	void malformedUtf8DecodesToAnEmptyArrayRatherThanReplacementCharacters() {
		// json_decode() requires valid UTF-8 and returns null otherwise --
		// "Malformed UTF-8 characters, possibly incorrectly encoded" -- so ?? []
		// hands the endpoint nothing. Decoding leniently would instead invent a
		// U+FFFD document PHP never accepted.
		byte[] loneContinuation = concat("{\"name\":\"".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0x80}, "\"}".getBytes(StandardCharsets.UTF_8));
		byte[] truncatedTwoByte = concat("{\"name\":\"".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xC3}, "\"}".getBytes(StandardCharsets.UTF_8));
		byte[] overlong = concat("{\"name\":\"".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xC0, (byte) 0xAF}, "\"}".getBytes(StandardCharsets.UTF_8));
		byte[] invalidByte = concat("{\"name\":\"".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xFF, (byte) 0xFE}, "\"}".getBytes(StandardCharsets.UTF_8));

		for (byte[] raw : java.util.List.of(loneContinuation, truncatedTwoByte, overlong, invalidByte)) {
			assertThat(LegacyJsonBody.decodeBytes(raw)).isEmpty();
		}

		// One bad byte poisons an otherwise valid document, exactly as in PHP.
		byte[] validThenInvalid = concat(
				"{\"first_name\":\"Nour\",\"last_name\":\"".getBytes(StandardCharsets.UTF_8),
				new byte[] {(byte) 0xFF}, "\"}".getBytes(StandardCharsets.UTF_8));
		assertThat(LegacyJsonBody.decodeBytes(validThenInvalid)).isEmpty();
	}

	@Test
	void validUtf8IncludingArabicSurvivesIntact() {
		Map<String, Object> body = LegacyJsonBody.decodeBytes(
				"{\"name\":\"Nour أحمد\"}".getBytes(StandardCharsets.UTF_8));
		assertThat(body).containsEntry("name", "Nour أحمد");
	}

	@Test
	void theNestingBoundaryIsPhpsFiveHundredAndEleven() {
		// Measured: 511 nested levels decode, 512 is "Maximum stack depth
		// exceeded" and therefore []. Jackson's own default is higher, so the
		// mapper behind this helper is constrained to match.
		assertThat(LegacyJsonBody.decode(nestedArrays(510))).isNotEmpty();
		assertThat(LegacyJsonBody.decode(nestedArrays(511))).isNotEmpty();
		assertThat(LegacyJsonBody.decode(nestedArrays(512))).isEmpty();
		assertThat(LegacyJsonBody.decode(nestedArrays(513))).isEmpty();

		assertThat(LegacyJsonBody.decode(nestedObjects(511))).isNotEmpty();
		assertThat(LegacyJsonBody.decode(nestedObjects(512))).isEmpty();
	}

	@Test
	void theEndpointLevelShapesAreUnchanged() {
		assertThat(LegacyJsonBody.decode("{\"a\":1}")).containsEntry("a", 1);
		assertThat(LegacyJsonBody.decode("[\"a\",\"b\"]"))
				.containsExactly(Map.entry("0", "a"), Map.entry("1", "b"));
		assertThat(LegacyJsonBody.decode("null")).isEmpty();
		assertThat(LegacyJsonBody.decode("{ not json")).isEmpty();
		assertThatThrownBy(() -> LegacyJsonBody.decode("\"scalar\""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must be of type array");
	}

	private static String nestedArrays(int levels) {
		return "[".repeat(levels) + "]".repeat(levels);
	}

	private static String nestedObjects(int levels) {
		return "{\"a\":".repeat(levels) + "1" + "}".repeat(levels);
	}

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		byte[] joined = new byte[length];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, joined, offset, part.length);
			offset += part.length;
		}
		return joined;
	}

}
