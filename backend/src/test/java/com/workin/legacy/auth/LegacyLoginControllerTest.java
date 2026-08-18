package com.workin.legacy.auth;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.workin.backend.identity.JwtService;
import com.workin.legacy.TenantFilterActivator;
import com.workin.legacy.companies.LegacyCompany;
import com.workin.legacy.companies.LegacyCompanyRepository;
import com.workin.legacy.employees.LegacyEmployee;
import com.workin.legacy.employees.LegacyEmployeeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers {@link LegacyLoginController} properties
 * {@link LegacyLoginEndToEndTest} cannot: cases needing two
 * {@code employees} rows sharing one phone number, which the vendored
 * schema's own {@code UNIQUE KEY `phone` (`phone`)} makes unseedable
 * against real MariaDB (see that class's javadoc for the 409 case, the
 * same constraint). Mocked repositories have no such constraint, so the
 * sibling-candidate shape is testable here at the unit level instead.
 */
@ExtendWith(MockitoExtension.class)
class LegacyLoginControllerTest {

	private static final String PASSWORD = "Secret123!";

	@Mock
	private LegacyEmployeeRepository legacyEmployeeRepository;
	@Mock
	private LegacyCompanyRepository legacyCompanyRepository;
	@Mock
	private TenantFilterActivator tenantFilterActivator;
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private JwtService jwtService;
	@Mock
	private LegacyRefreshTokenService legacyRefreshTokenService;

	@Test
	void aDanglingCompanyReferenceOnOneCandidateDoesNotFailLoginForAValidSiblingCandidate() {
		LegacyLoginController controller = new LegacyLoginController(
				legacyEmployeeRepository, legacyCompanyRepository, tenantFilterActivator,
				passwordEncoder, jwtService, legacyRefreshTokenService);

		// toCandidate() returns before reading role/joinRequestStatus/
		// active/passwordHash when the company lookup is empty, so this
		// mock only stubs what actually gets read on the excluded path.
		LegacyEmployee danglingEmployee = org.mockito.Mockito.mock(LegacyEmployee.class);
		when(danglingEmployee.getId()).thenReturn(90051L);
		when(danglingEmployee.getCompanyId()).thenReturn(9999L);
		LegacyEmployee validEmployee = mockEmployee(90052L, 9001L);
		when(legacyEmployeeRepository.findByPhoneOrderByIdDesc("+201100090051"))
				.thenReturn(List.of(danglingEmployee, validEmployee));

		LegacyCompany validCompany = mockCompany("active");
		when(legacyCompanyRepository.findById(9999L)).thenReturn(Optional.empty());
		when(legacyCompanyRepository.findById(9001L)).thenReturn(Optional.of(validCompany));

		when(passwordEncoder.matches(eq(PASSWORD), anyString())).thenReturn(true);
		when(legacyRefreshTokenService.issue(90052L))
				.thenReturn(new LegacyRefreshTokenService.IssuedLegacyRefreshToken("raw-token", "family-id"));
		when(jwtService.issueAccessToken(eq(90052L), eq(90052L), eq(9001L), anyString()))
				.thenReturn("access-token");

		LegacyAuthResponse response = controller.login(new LegacyLoginRequest("+201100090051", PASSWORD));

		assertThat(response.employeeId()).isEqualTo(90052L);
		assertThat(response.companyId()).isEqualTo(9001L);
		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(response.refreshToken()).isEqualTo("raw-token");
	}

	private static LegacyEmployee mockEmployee(long id, long companyId) {
		LegacyEmployee employee = org.mockito.Mockito.mock(LegacyEmployee.class);
		when(employee.getId()).thenReturn(id);
		when(employee.getCompanyId()).thenReturn(companyId);
		when(employee.role()).thenReturn(LegacyEmployee.Role.EMPLOYEE);
		when(employee.joinRequestStatus()).thenReturn(LegacyEmployee.JoinRequestStatus.ACCEPTED);
		when(employee.active()).thenReturn(true);
		when(employee.getPasswordHash()).thenReturn("irrelevant-hash-passwordEncoder-is-mocked");
		return employee;
	}

	private static LegacyCompany mockCompany(String status) {
		LegacyCompany company = org.mockito.Mockito.mock(LegacyCompany.class);
		when(company.getStatus()).thenReturn(status);
		return company;
	}

}
