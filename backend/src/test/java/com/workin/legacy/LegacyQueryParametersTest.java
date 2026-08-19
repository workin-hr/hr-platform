package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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
	void bracketAppendKeysProduceAnOrderedArrayIncludingMalformedAndEmptyValues() {
		Object value = LegacyQueryParameters.parse(
				"branch_id%5B%5D=18811&branch_id[]=abc&branch_id[]=").value("branch_id");

		assertThat(value).isEqualTo(List.of("18811", "abc", ""));
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
}
