package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.workin.legacy.wire.LegacyApiException;

class LegacySalaryContractServiceTest {

	private final LegacySalaryContractStore store = mock(LegacySalaryContractStore.class);
	private final LegacySalaryContractService service = new LegacySalaryContractService(store);

	@Test
	void createDailyModeZerosMonthlyCompensationButPreservesDailyAndDeductions() {
		when(store.employeeOwned(17L, 31L)).thenReturn(true);
		when(store.insert(org.mockito.ArgumentMatchers.anyMap())).thenReturn(91L);
		when(store.byId(91L)).thenReturn(Map.of("id", 91L));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("employee_id", 31);
		body.put("effective_from", "2026-08-01");
		body.put("salary_mode", "daily");
		body.put("basic_salary", 9000);
		body.put("daily_wage", 450);
		body.put("transport_allowance", 1200);
		body.put("food_allowance", 800);
		body.put("risk_allowance", 300);
		body.put("incentives", 700);
		body.put("insurance_deduction", 150);
		body.put("tax_deduction", 250);
		body.put("advances_deduction", 350);
		body.put("fund_deduction", 75);
		body.put("penalty_deduction", 25);

		service.create(17L, body);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
		verify(store).insert(values.capture());
		Map<String, Object> written = values.getValue();
		assertThat(written)
				.containsEntry("employee_id", 31L)
				.containsEntry("salary_mode", "daily")
				.containsEntry("basic_salary", 0.0)
				.containsEntry("daily_wage", 450.0)
				.containsEntry("transport_allowance", 0.0)
				.containsEntry("food_allowance", 0.0)
				.containsEntry("risk_allowance", 0.0)
				.containsEntry("incentives", 0.0)
				.containsEntry("insurance_deduction", 150.0)
				.containsEntry("tax_deduction", 250.0)
				.containsEntry("advances_deduction", 350.0)
				.containsEntry("fund_deduction", 75.0)
				.containsEntry("penalty_deduction", 25.0)
				.containsEntry("effective_from", "2026-08-01");
	}

	@Test
	void updateEmptyDailyWageClearsItAndInvalidSalaryModeFallsBackToMonthly() {
		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("id", 91L);
		existing.put("salary_mode", "daily");
		existing.put("basic_salary", 0);
		existing.put("daily_wage", 300);
		existing.put("transport_allowance", 0);
		existing.put("food_allowance", 0);
		existing.put("risk_allowance", 0);
		existing.put("incentives", 0);
		existing.put("insurance_deduction", 10);
		existing.put("tax_deduction", 20);
		existing.put("advances_deduction", 30);
		existing.put("fund_deduction", 40);
		existing.put("penalty_deduction", 50);
		existing.put("effective_from", "2026-08-01");
		when(store.scoped(17L, 91L)).thenReturn(existing);
		when(store.byId(91L)).thenReturn(Map.of("id", 91L));

		service.update(17L, 91L, Map.of("salary_mode", "hourly", "daily_wage", ""));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
		verify(store).update(eq(91L), values.capture());
		assertThat(values.getValue())
				.containsEntry("salary_mode", "monthly")
				.containsEntry("daily_wage", null)
				.containsEntry("effective_from", "2026-08-01");
	}

	@Test
	void createRejectsEmployeeOutsideCompanyBeforeAnyInsert() {
		when(store.employeeOwned(17L, 31L)).thenReturn(false);

		assertThatThrownBy(() -> service.create(17L,
				Map.of("employee_id", 31, "effective_from", "2026-08-01")))
				.isInstanceOf(LegacyApiException.class);
		verify(store).employeeOwned(17L, 31L);
	}
}
