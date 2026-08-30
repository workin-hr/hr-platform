package com.workin.legacy.payroll;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;

/** Regression for PHP's {@code $body[key] ?? $stored} semantics in update.php. */
class LegacyAdvanceNullCoalescingTest {

	@Test
	void employeeExplicitNullAmountAndReasonKeepStoredValues() {
		LegacyAdvanceStore store = mock(LegacyAdvanceStore.class);
		LegacyAdvanceService service = new LegacyAdvanceService(store);
		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("employee_id", 31L);
		existing.put("status", "pending");
		existing.put("amount", new BigDecimal("500.00"));
		existing.put("reason", "stored");
		when(store.scoped(17L, 96L)).thenReturn(existing);
		when(store.updateEmployee(96L, new BigDecimal("500.00"), "stored")).thenReturn(1);
		when(store.withEmployee(96L)).thenReturn(Map.of("id", 96L));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", null);
		body.put("reason", null);

		service.update(new LegacyRequestContext(31L, 17L, LegacyEmployee.Role.EMPLOYEE, "employee"), 96L, body);

		verify(store).updateEmployee(96L, new BigDecimal("500.00"), "stored");
	}
}
