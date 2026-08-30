package com.workin.legacy.organization.php;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.auth.LegacyRequestGuard;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.organization.LegacyDepartmentService;
import com.workin.legacy.organization.LegacyDepartmentView;
import com.workin.legacy.wire.LegacyMessages;

class LegacyDepartmentPhpControllerBatchReadTest {

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void listLoadsAllLexicalWireRowsWithOneCompanyScopedQuery() {
		LegacyDepartmentService service = mock(LegacyDepartmentService.class);
		LegacyRequestGuard guard = mock(LegacyRequestGuard.class);
		LegacyMessages messages = mock(LegacyMessages.class);
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		LegacyDepartmentPhpController controller = new LegacyDepartmentPhpController(
				service, guard, messages, mock(DataSource.class));
		ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);

		when(guard.requireAuth(
				LegacyEmployee.Role.COMPANY_ADMIN, LegacyEmployee.Role.HR, LegacyEmployee.Role.MANAGER))
				.thenReturn(new LegacyRequestContext(10L, 9L, LegacyEmployee.Role.HR, "employee"));

		LegacyDepartmentView first = new LegacyDepartmentView(
				1L, 9L, null, "Engineering", true, Instant.parse("2026-01-01T00:00:00Z"),
				"11", "HQ", null);
		LegacyDepartmentView second = new LegacyDepartmentView(
				2L, 9L, null, "Operations", true, Instant.parse("2026-01-02T00:00:00Z"),
				"12", "Branch 2", null);
		when(service.list(9L, 0L, List.of())).thenReturn(List.of(first, second));

		Map<String, Object> firstRaw = new LinkedHashMap<>();
		firstRaw.put("id", 1L);
		firstRaw.put("company_id", 9L);
		firstRaw.put("name", "Engineering");
		Map<String, Object> secondRaw = new LinkedHashMap<>();
		secondRaw.put("id", 2L);
		secondRaw.put("company_id", 9L);
		secondRaw.put("name", "Operations");
		when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(9L)))
				.thenReturn(List.of(firstRaw, secondRaw));

		controller.list(new MockHttpServletRequest("GET", "/apis/api/departments/list.php"));

		verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(9L));
		verifyNoMoreInteractions(jdbcTemplate);
	}
}
