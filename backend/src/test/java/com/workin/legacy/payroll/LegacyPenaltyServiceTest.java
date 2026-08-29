package com.workin.legacy.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.wire.LegacyApiException;
import com.workin.legacy.wire.LegacyMessages;

class LegacyPenaltyServiceTest {

	private final LegacyPenaltyStore store = mock(LegacyPenaltyStore.class);
	private final LegacyMessages messages = mock(LegacyMessages.class);
	// A real LegacyPenaltyAmounts over the mocked store, not a mock of it: the
	// money calculation is the part most worth exercising, and stubbing it would
	// leave these tests asserting nothing about the figures they produce.
	private final LegacyPenaltyAmounts penaltyAmounts = new LegacyPenaltyAmounts(store);
	private final LegacyPenaltyService service =
			new LegacyPenaltyService(store, penaltyAmounts, messages);

	@Test
	void createNormalizesQuarterDayAndPersistsNotificationAfterInsert() {
		when(store.employeeCompanyId(31L)).thenReturn(17L);
		when(store.insert(31L, "absence", 0.25, "2026-08-25", "reason")).thenReturn(81L);
		when(store.publicMutationRow(81L)).thenReturn(Map.of("id", 81L, "employee_id", 31L));
		when(messages.translate(eq("en"), eq("notif_penalty_issued_title"), any())).thenReturn("Penalty");
		when(messages.translate(eq("en"), eq("notif_penalty_issued_body"), any())).thenReturn("Penalty body");

		Map<String, Object> row = service.create(context(0L, LegacyEmployee.Role.HR),
				Map.of("employee_id", "31", "penalty_type", "absence", "penalty_days", "0.25001",
						"penalty_date", "2026-08-25", "reason", "reason"), "en");

		assertThat(row).containsEntry("id", 81L);
		verify(store).insertEmployeeNotification(17L, 31L, null, "Penalty", "Penalty body", 81L);
	}

	@Test
	void createRejectsPenaltyDaysOutsideFrozenWhitelistBeforeInsert() {
		assertThatThrownBy(() -> service.create(context(0L, LegacyEmployee.Role.HR),
				Map.of("employee_id", 31, "penalty_type", "absence", "penalty_days", 1.5,
						"penalty_date", "2026-08-25"), "en"))
				.isInstanceOf(LegacyApiException.class);
		verify(store, never()).insert(anyLong(), anyString(), anyDouble(), anyString(), anyString());
	}

	@Test
	void updateUsesDefault400ForMissingRowAndNeverWrites() {
		when(store.mutableState(90L)).thenReturn(null);

		assertThatThrownBy(() -> service.update(17L, 90L, Map.of("reason", "x")))
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(400));
		verify(store, never()).updateFields(eq(90L), any());
	}

	@Test
	void appliedPenaltyIsImmutable() {
		Map<String, Object> existing = new LinkedHashMap<>();
		existing.put("company_id", 17L);
		existing.put("applied_to_payroll", 1L);
		when(store.mutableState(91L)).thenReturn(existing);

		assertThatThrownBy(() -> service.update(17L, 91L, Map.of("reason", "x")))
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));
		verify(store, never()).updateFields(eq(91L), any());
	}

	@Test
	void oneManagerChecksBranchOnlyAfterIdOnlyReadAndCompanyCheck() {
		when(store.oneRow(92L)).thenReturn(Map.of("id", 92L, "employee_id", 31L, "company_id", 17L));
		when(store.managerCanAccess(7L, 31L, 17L)).thenReturn(false);

		assertThatThrownBy(() -> service.one(context(7L, LegacyEmployee.Role.MANAGER), 92L))
				.isInstanceOf(LegacyApiException.class);
		verify(store).oneRow(92L);
		verify(store).managerCanAccess(7L, 31L, 17L);
	}

	@Test
	void updatePreservesWhitelistOrderAndExplicitNullReason() {
		when(store.mutableState(93L)).thenReturn(Map.of("company_id", 17L, "applied_to_payroll", 0L));
		when(store.updateFields(eq(93L), any())).thenReturn(1);
		when(store.publicMutationRow(93L)).thenReturn(Map.of("id", 93L));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("reason", null);
		body.put("penalty_days", 2.0);

		service.update(17L, 93L, body);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
		verify(store).updateFields(eq(93L), values.capture());
		assertThat(values.getValue().keySet()).containsExactly("penalty_days", "reason");
		assertThat(values.getValue()).containsEntry("penalty_days", 2.0).containsEntry("reason", null);
	}

	@Test
	void updateRejectsWhenFinalizationAppliesPenaltyAfterPreflight() {
		when(store.mutableState(94L)).thenReturn(Map.of("company_id", 17L, "applied_to_payroll", 0L));
		when(store.updateFields(eq(94L), any())).thenReturn(0);

		assertThatThrownBy(() -> service.update(17L, 94L, Map.of("reason", "late edit")))
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(403);
					assertThat(ex.getMessageKey()).isEqualTo("forbidden");
				});
		verify(store, never()).publicMutationRow(94L);
	}

	@Test
	void deleteRejectsWhenFinalizationAppliesPenaltyAfterPreflight() {
		when(store.mutableState(95L)).thenReturn(Map.of("company_id", 17L, "applied_to_payroll", 0L));
		when(store.deleteById(95L)).thenReturn(0);

		assertThatThrownBy(() -> service.delete(17L, 95L))
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(403);
					assertThat(ex.getMessageKey()).isEqualTo("forbidden");
				});
	}

	@Test
	void penaltyDayNormalizerUsesLegacyTolerance() {
		assertThat(LegacyPenaltyDays.normalize(0.25001)).isEqualTo(0.25);
		assertThat(LegacyPenaltyDays.normalize(5.00009)).isEqualTo(5.0);
		assertThat(LegacyPenaltyDays.normalize(0.2502)).isNull();
		assertThat(LegacyPenaltyDays.normalize(1.5)).isNull();
	}

	private static LegacyRequestContext context(long employeeId, LegacyEmployee.Role role) {
		return new LegacyRequestContext(employeeId, 17L, role, "employee");
	}
}
