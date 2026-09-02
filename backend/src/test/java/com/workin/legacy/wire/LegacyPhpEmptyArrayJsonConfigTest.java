package com.workin.legacy.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins this module against PHP's {@code json_encode}, measured rather than
 * reasoned about: {@code php -r 'echo json_encode(["a" => []]);'} answers
 * <code>{"a":[]}</code>, and there is no array shape that answers
 * <code>{}</code> -- only the explicit {@code (object)[]} cast does.
 *
 * <p>The three response fields this was found on are named in
 * {@link LegacyPhpEmptyArrayJsonConfig}'s javadoc; the point of the tests here
 * is the rule underneath them, including the nesting, because every one of the
 * three was an empty map several levels inside {@code data}.
 */
class LegacyPhpEmptyArrayJsonConfigTest {

	private final ObjectMapper mapper = JsonMapper.builder()
			.addModule(new LegacyPhpEmptyArrayJsonConfig().legacyPhpEmptyArrayModule())
			.build();

	@Test
	void anEmptyMapRendersAsAnArray() {
		assertThat(mapper.writeValueAsString(Map.of("field_errors", Map.of())))
				.isEqualTo("{\"field_errors\":[]}");
	}

	@Test
	void aPopulatedMapIsUntouched() {
		Map<String, Object> errors = new LinkedHashMap<>();
		errors.put("first_name", "required");
		errors.put("last_name", "required");
		assertThat(mapper.writeValueAsString(Map.of("field_errors", errors)))
				.isEqualTo("{\"field_errors\":{\"first_name\":\"required\",\"last_name\":\"required\"}}");
	}

	@Test
	void theRuleReachesMapsNestedInsideTheResponse() {
		// All three measured divergences were nested: rows[].field_errors,
		// data.summary, data.failed[].data. A rule that only fired on a
		// top-level map would have closed none of them.
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("row_index", 1);
		row.put("field_errors", new LinkedHashMap<>());
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("rows", List.of(row));
		assertThat(mapper.writeValueAsString(data))
				.isEqualTo("{\"rows\":[{\"row_index\":1,\"field_errors\":[]}]}");
	}

	@Test
	void theRuleDoesNotDependOnTheMapImplementation() {
		// A response map may be any of these; PHP has one array type and does
		// not care which Java class happened to build it.
		assertThat(mapper.writeValueAsString(Map.of("v", new LinkedHashMap<>()))).isEqualTo("{\"v\":[]}");
		assertThat(mapper.writeValueAsString(Map.of("v", new TreeMap<>()))).isEqualTo("{\"v\":[]}");
		assertThat(mapper.writeValueAsString(Map.of("v", Map.of()))).isEqualTo("{\"v\":[]}");
	}

	@Test
	void anEmptyListIsStillAnEmptyList() {
		// The other half of PHP's single array type: `errors` and `employees`
		// are already lists on both sides and must not be disturbed.
		assertThat(mapper.writeValueAsString(Map.of("errors", List.of()))).isEqualTo("{\"errors\":[]}");
	}

	@Test
	void theObjectCastStillRendersAsAnObject() {
		// dashboard/stats.php's `empty($x) ? (object)[] : $x`. This is the one
		// shape on the legacy surface that must NOT follow the rule above.
		assertThat(mapper.writeValueAsString(Map.of("v", LegacyPhpArrayJson.EMPTY_OBJECT)))
				.isEqualTo("{\"v\":{}}");
	}

	@Test
	void theObjectCastRendersAsAnObjectWithoutThisModuleToo() {
		// It is profile-scoped; the cast's shape is not.
		assertThat(new ObjectMapper().writeValueAsString(Map.of("v", LegacyPhpArrayJson.EMPTY_OBJECT)))
				.isEqualTo("{\"v\":{}}");
	}

}
