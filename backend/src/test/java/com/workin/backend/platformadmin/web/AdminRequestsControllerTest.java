package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;

import com.workin.backend.platformadmin.hr.EmployeeRequest;
import com.workin.backend.platformadmin.hr.EmployeeRequestAdminService;
import com.workin.backend.platformadmin.hr.EmployeeRequestStore;

/**
 * Who the requests toolbar's type cascade is for (D-260, #273).
 *
 * <p>Every company's active request types go to a session that can choose the company, and
 * none to a session bound to one company (R-051). The decision reads the advice's session, as
 * the org toolbars' cascade does, so the maps the five toolbars attach follow one session. Whether
 * a toolbar renders its company select does not yet: the controllers replace that session with an
 * administrator view, which owner and HR logins must change (#220). No HTTP session can produce a
 * bound audience yet, so the controller is called directly.
 */
class AdminRequestsControllerTest {

	@Test
	void everyCompanysTypesGoOnlyToASessionThatCanChooseTheCompany() {
		int[] reads = {0};
		EmployeeRequestStore store = new EmployeeRequestStore(null) {
			@Override
			public Map<Long, List<EmployeeRequest.TypeOption>> activeTypesByCompany() {
				reads[0]++;
				return Map.of(11L, List.of());
			}

			@Override
			public List<EmployeeRequest.TypeOption> typeOptions(long companyId) {
				return List.of();
			}

			@Override
			public DashboardPage<EmployeeRequest> paginate(DashboardListFilters filters, String status, long typeId,
					String dateFrom, String dateTo, boolean showCompany) {
				return DashboardPage.of(List.of(), 0, 1, DashboardPage.SIZE_DEFAULT);
			}
		};
		AdminRequestsController controller = new AdminRequestsController(store,
				new EmployeeRequestAdminService(store, null, null, false));

		ExtendedModelMap administrator = new ExtendedModelMap();
		administrator.addAttribute("session", DashboardSession.admin(0));
		controller.page(null, new MockHttpServletRequest(), administrator, null);
		assertThat(administrator.getAttribute("typesByCompany")).isEqualTo("{\"11\":[]}");

		ExtendedModelMap bound = new ExtendedModelMap();
		bound.addAttribute("session", DashboardSession.company(7));
		controller.page(null, new MockHttpServletRequest(), bound, null);
		assertThat(bound.getAttribute("typesByCompany")).as("nothing for a session bound to one company").isNull();
		assertThat(reads[0]).as("and nothing read for it").isEqualTo(1);
	}
}
