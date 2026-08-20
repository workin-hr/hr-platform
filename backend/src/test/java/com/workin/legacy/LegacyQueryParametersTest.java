package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LegacyQueryParametersTest {

	@Test
	void absentEmptyAndScalarParametersRemainDistinct() {
		LegacyQueryParameters query = LegacyQueryParameters.parse("branch_id=&other=value");

		assertThat(query.value("missing")).isNull();
		assertThat(query.value("branch_id")).isEqualTo("");
		assertThat(LegacyQueryParameters.parse("branch_id").value("branch_id")).isEqualTo("");
		assertThat(LegacyQueryParameters.parse("branch_id=%2B18811branch").value("branch_id"))
				.isEqualTo("+18811branch");
	}

	@Test
	void duplicatePlainKeysKeepTheLastScalarLikePhpParseStr() {
		Object value = LegacyQueryParameters.parse("branch_id=18811&branch_id=18812").value("branch_id");

		assertThat(value).isEqualTo("18812");
	}

	@Test
	void externalParameterNamesNormalizeDotsAndSpacesBeforeAssignment() {
		LegacyQueryParameters query = LegacyQueryParameters.parse(
				"branch.id=18811&department+id=18841&branch%20ids=18811%2C18812");

		assertThat(query.value("branch_id")).isEqualTo("18811");
		assertThat(query.value("department_id")).isEqualTo("18841");
		assertThat(query.value("branch_ids")).isEqualTo("18811,18812");
	}

	@Test
	void bracketAppendKeysProduceAnOrderedArrayIncludingMalformedAndEmptyValues() {
		Object value = LegacyQueryParameters.parse(
				"branch_id%5B%5D=18811&branch_id[]=abc&branch_id[]=").value("branch_id");

		assertThat(value).isEqualTo(List.of("18811", "abc", ""));
	}

	@Test
	void keyedArraysProduceAnAssociativeValueUnderTheNormalizedBaseName() {
		Object value = LegacyQueryParameters.parse(
				"branch.id[item]=18811&branch.id[other]=18812").value("branch_id");

		assertThat(value).isEqualTo(Map.of("item", "18811", "other", "18812"));
	}

	@Test
	void appendAndKeyedArrayAssignmentsPreserveNumericAndStringKeyOrder() {
		Object appendThenKey = LegacyQueryParameters.parse(
				"branch_id[]=18811&branch_id[item]=18812").value("branch_id");
		Object keyThenAppend = LegacyQueryParameters.parse(
				"branch_id[item]=18811&branch_id[]=18812").value("branch_id");

		Map<Object, String> expectedAppendThenKey = new LinkedHashMap<>();
		expectedAppendThenKey.put(0L, "18811");
		expectedAppendThenKey.put("item", "18812");
		Map<Object, String> expectedKeyThenAppend = new LinkedHashMap<>();
		expectedKeyThenAppend.put("item", "18811");
		expectedKeyThenAppend.put(0L, "18812");
		assertThat(appendThenKey).isEqualTo(expectedAppendThenKey);
		assertThat(keyThenAppend).isEqualTo(expectedKeyThenAppend);
		assertThat(new ArrayList<Object>(((Map<?, ?>) appendThenKey).keySet()))
				.containsExactly(0L, "item");
		assertThat(new ArrayList<Object>(((Map<?, ?>) keyThenAppend).keySet()))
				.containsExactly("item", 0L);
	}

	@Test
	void mixedScalarAndBracketAssignmentsUseTheLastAssignmentShape() {
		Object scalarThenArray = LegacyQueryParameters.parse(
				"department_id=18841&department_id[]=18842").value("department_id");
		Object arrayThenScalar = LegacyQueryParameters.parse(
				"department_id[]=18841&department_id=18842").value("department_id");

		assertThat(scalarThenArray).isEqualTo(List.of("18842"));
		assertThat(arrayThenScalar).isEqualTo("18842");
	}

	@Test
	void negativeZeroArrayKeyStaysAStringKeyDistinctFromIntegerZero() {
		// PHP 8.3 parse_str: (string)(int)"-0" === "0", not "-0", so "-0" is not a canonical
		// integer key and parse_str keeps it as a distinct string key from int key 0.
		Object keyedThenKeyed = LegacyQueryParameters.parse(
				"branch_ids[-0]=aaa&branch_ids[0]=bbb").value("branch_ids");
		Map<Object, String> expectedKeyedThenKeyed = new LinkedHashMap<>();
		expectedKeyedThenKeyed.put("-0", "aaa");
		expectedKeyedThenKeyed.put(0L, "bbb");
		assertThat(keyedThenKeyed).isEqualTo(expectedKeyedThenKeyed);

		// A following [] append computes its index from existing integer keys only; "-0" is a
		// string key and does not contribute, so the append still lands on index 0.
		Object keyedThenAppended = LegacyQueryParameters.parse(
				"branch_ids[-0]=aaa&branch_ids[]=ccc").value("branch_ids");
		Map<Object, String> expectedKeyedThenAppended = new LinkedHashMap<>();
		expectedKeyedThenAppended.put("-0", "aaa");
		expectedKeyedThenAppended.put(0L, "ccc");
		assertThat(keyedThenAppended).isEqualTo(expectedKeyedThenAppended);
	}

	@Test
	void mixedScalarAndKeyedAssignmentsUseTheLastAssignmentShape() {
		Object scalarThenArray = LegacyQueryParameters.parse(
				"department_id=18841&department_id[item]=18851").value("department_id");
		Object arrayThenScalar = LegacyQueryParameters.parse(
				"department_id[item]=18841&department_id=18851").value("department_id");

		assertThat(scalarThenArray).isEqualTo(Map.of("item", "18851"));
		assertThat(arrayThenScalar).isEqualTo("18851");
	}
}
