package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.workin.legacy.LegacyQueryParameters;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;

class LegacyAdvanceServiceTest {

	private final LegacyAdvanceStore store = mock(LegacyAdvanceStore.class);
	private final LegacyAdvanceService service = new LegacyAdvanceService(store);

	@Test
	void employeeCreateUsesAuthenticatedEmployeeAndForcesPendingStatus() {
		LegacyRequestContext employee = context(31L, LegacyEmployee.Role.EMPLOYEE);
		when(store.employeeCompanyId(31L)).thenReturn(17L);
		when(store.insert(org.mockito.ArgumentMatchers.anyMap())).thenReturn(81L);
		when(store.withEmployee(81L)).thenReturn(Map.of("id", 81L));

		service.create(employee, Map.of("amount", 500, "employee_id", 999, "status", "approved"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
		verify(store).insert(values.capture());
		assertThat(values.getValue())
				.containsEntry("employee_id", 31L)
				.containsEntry("status", "pending")
				.containsEntry("deduction_mode", "single_payroll_month")
				.containsEntry("deduction_type", "single_month")
				.containsEntry("deduction_month_count", 1L);
	}

	@Test
	void adminCreatePreservesUnvalidatedStatusAndNormalizesDeductionControls() {
		when(store.employeeCompanyId(77L)).thenReturn(17L);
		when(store.insert(org.mockito.ArgumentMatchers.anyMap())).thenReturn(82L);
		when(store.withEmployee(82L)).thenReturn(Map.of("id", 82L));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", "800.50");
		body.put("employee_id", "77abc");
		body.put("status", "custom_status");
		body.put("deduction_mode", "other");
		body.put("deduction_type", "multiple_months");
		body.put("deduction_payroll_year", "");
		body.put("deduction_payroll_month", null);

		service.create(context(0L, LegacyEmployee.Role.HR), body);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
		verify(store).insert(values.capture());
		assertThat(values.getValue())
				.containsEntry("employee_id", 77L)
				.containsEntry("status", "custom_status")
				.containsEntry("deduction_mode", "single_payroll_month")
				.containsEntry("deduction_type", "multiple_months")
				.containsEntry("deduction_payroll_year", null)
				.containsEntry("deduction_payroll_month", null);
	}

	@Test
	void adminCreateRejectsAnEmployeeIdBelongingToAnotherCompany() {
		when(store.employeeCompanyId(999L)).thenReturn(41L);

		assertThatThrownBy(() -> service.create(
				context(0L, LegacyEmployee.Role.HR), Map.of("amount", 500, "employee_id", 999)))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).insert(org.mockito.ArgumentMatchers.anyMap());
	}

	@Test
	void employeeOneChecksOwnershipOnlyAfterCompanyScopedLookup() {
		when(store.scopedWithEmployee(17L, 90L)).thenReturn(Map.of("id", 90L, "employee_id", 44L));

		assertThatThrownBy(() -> service.one(context(31L, LegacyEmployee.Role.EMPLOYEE), 90L))
				.isInstanceOf(LegacyApiException.class);
		verify(store).scopedWithEmployee(17L, 90L);
	}

	@Test
	void approveIsCompanyScopedAndRejectsAForeignAdvanceId() {
		when(store.scoped(17L, 91L)).thenReturn(null);

		assertThatThrownBy(() -> service.approve(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 91L))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).approve(91L);
	}

	@Test
	void approveWritesOnceScopeConfirmedEvenIfTheReReadRaces() {
		when(store.scoped(17L, 91L)).thenReturn(Map.of("id", 91L));
		when(store.withEmployeeNameOnly(91L)).thenReturn(null);

		assertThatThrownBy(() -> service.approve(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 91L))
				.isInstanceOf(IllegalStateException.class);
		verify(store).approve(91L);
	}

	@Test
	void payIsCompanyScopedAndRejectsAForeignAdvanceId() {
		when(store.scopedPaymentState(17L, 92L)).thenReturn(null);

		assertThatThrownBy(() -> service.pay(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 92L, Map.of("amount", "40.01")))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).payIfSufficientBalance(eq(92L), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void payRejectsWhenTheAtomicUpdateAffectsNoRows() {
		when(store.scopedPaymentState(17L, 92L))
				.thenReturn(Map.of("amount", new BigDecimal("100.00"), "remaining", new BigDecimal("40.00")));
		when(store.payIfSufficientBalance(eq(92L), org.mockito.ArgumentMatchers.any())).thenReturn(0);

		assertThatThrownBy(() -> service.pay(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 92L, Map.of("amount", "40.01")))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).withEmployeeNameOnly(92L);
	}

	@Test
	void employeeUpdateReplacesRemainingWithNewAmountExactlyLikePhp() {
		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("employee_id", 31L);
		existing.put("status", "pending");
		existing.put("amount", new BigDecimal("500.00"));
		existing.put("remaining", new BigDecimal("125.00"));
		existing.put("reason", "old");
		when(store.scoped(17L, 93L)).thenReturn(existing);
		when(store.updateEmployee(93L, new BigDecimal("600.00"), "old")).thenReturn(1);
		when(store.withEmployee(93L)).thenReturn(Map.of("id", 93L));

		service.update(context(31L, LegacyEmployee.Role.EMPLOYEE), 93L, Map.of("amount", new BigDecimal("600.00")));

		verify(store).updateEmployee(93L, new BigDecimal("600.00"), "old");
	}

	@Test
	void employeeUpdateRejectsWhenApprovalWinsAfterThePendingPreflight() {
		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("employee_id", 31L);
		existing.put("status", "pending");
		existing.put("amount", new BigDecimal("500.00"));
		existing.put("reason", "old");
		when(store.scoped(17L, 93L)).thenReturn(existing);
		when(store.updateEmployee(93L, new BigDecimal("600.00"), "old")).thenReturn(0);

		assertThatThrownBy(() -> service.update(
				context(31L, LegacyEmployee.Role.EMPLOYEE), 93L, Map.of("amount", new BigDecimal("600.00"))))
				.isInstanceOf(LegacyApiException.class)
				.satisfies(ex -> assertThat(((LegacyApiException) ex).getMessageKey())
						.isEqualTo("cannot_edit_non_pending_advance"));
		verify(store, never()).withEmployee(93L);
	}

	@Test
	void administrativeUpdateClearsExplicitPayrollMonthAndFallsBackInvalidModes() {
		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("amount", 100);
		existing.put("remaining", 50);
		existing.put("reason", null);
		existing.put("status", "pending");
		existing.put("rejection_reason", null);
		existing.put("deduction_mode", "installments");
		existing.put("deduction_type", "multiple_months");
		existing.put("deduction_month_count", 3);
		existing.put("deduction_amount_per_month", new BigDecimal("10.00"));
		existing.put("deduction_payroll_year", 2026);
		existing.put("deduction_payroll_month", 8);
		existing.put("deduction_installments_json", "[]");
		when(store.scoped(17L, 94L)).thenReturn(existing);
		when(store.withEmployee(94L)).thenReturn(Map.of("id", 94L));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("deduction_mode", "invalid");
		body.put("deduction_type", "invalid");
		body.put("deduction_payroll_month", "");

		service.update(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 94L, body);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
		verify(store).updateAdministrative(eq(94L), values.capture());
		assertThat(values.getValue())
				.containsEntry("deduction_mode", "single_payroll_month")
				.containsEntry("deduction_type", "single_month")
				.containsEntry("deduction_payroll_month", null)
				.containsEntry("deduction_payroll_year", 2026);
	}

	@Test
	void invalidListStatusFailsBeforeDatabaseQueries() {
		LegacyQueryParameters query = LegacyQueryParameters.parse("status=paid");

		assertThatThrownBy(() -> service.list(context(0L, LegacyEmployee.Role.HR), query))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).count(anyList(), anyList());
	}

	@Test
	void adminDeleteSucceedsForAnAdvanceWithinTheCallersCompany() {
		when(store.scopedDeleteState(17L, 95L)).thenReturn(Map.of("employee_id", 999L, "status", "pending"));

		service.delete(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 95L);

		verify(store).delete(95L);
	}

	@Test
	void adminDeleteIsCompanyScopedAndRejectsAForeignAdvanceId() {
		when(store.scopedDeleteState(17L, 95L)).thenReturn(null);

		assertThatThrownBy(() -> service.delete(context(0L, LegacyEmployee.Role.COMPANY_ADMIN), 95L))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).delete(95L);
	}

	private static LegacyRequestContext context(long employeeId, LegacyEmployee.Role role) {
		return new LegacyRequestContext(employeeId, 17L, role, "employee");
	}
}
