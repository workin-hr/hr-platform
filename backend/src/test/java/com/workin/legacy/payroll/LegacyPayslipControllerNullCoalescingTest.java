package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LegacyPayslipControllerNullCoalescingTest {

	@Test
	void explicitNullPenaltyDaysIsNormalizedToThePhpOmittedKeyShape() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("penalty_days", null);
		body.put("other_deductions", 25);

		Map<String, Object> normalized = LegacyPayslipController.phpUpdateBody(body);

		assertThat(normalized).doesNotContainKey("penalty_days").containsEntry("other_deductions", 25);
		assertThat(body).containsKey("penalty_days"); // adapter normalization must not mutate the parsed request map
	}

	@Test
	void explicitPenaltyOverrideIsPreserved() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("penalty_days", 2);

		Map<String, Object> normalized = LegacyPayslipController.phpUpdateBody(body);

		assertThat(normalized).isSameAs(body).containsEntry("penalty_days", 2);
	}
}
