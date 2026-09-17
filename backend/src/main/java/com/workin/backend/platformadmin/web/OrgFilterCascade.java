package com.workin.backend.platformadmin.web;

import java.util.List;
import java.util.Map;

import com.workin.backend.platformadmin.org.OrgCascade;
import com.workin.backend.platformadmin.org.OrgCascadeStore;

/**
 * The maps {@code org_filter_cascade_form_attrs()} ({@code org_helper.php:450-466})
 * puts on a list page's toolbar form, as the JSON {@code org-filter-cascade.js}
 * reads: branches by company, departments by company and by branch, and job titles
 * by department. The script narrows the toolbar's branch, department and job title
 * selects to the company, branch and department chosen above them.
 *
 * <p>Every company's rows, because the company select changes without a request
 * and an administrator's reach is every company. They come from
 * {@link OrgCascadeStore}, which lists a department or job title only under a
 * group of its own company (R-051).
 *
 * <p>{@link #NONE} for a session bound to one company, which renders no company
 * select. Legacy attaches the script there too, and with no company to read it
 * empties the branch, department and job title selects to "All"; attaching
 * nothing leaves the server's own lists, which are already that company's.
 */
public record OrgFilterCascade(
		String branchesByCompany,
		String departmentsByCompany,
		String departmentsByBranch,
		String jobTitlesByDepartment) {

	public static final OrgFilterCascade NONE = new OrgFilterCascade(null, null, null, null);

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	static OrgFilterCascade of(OrgCascade cascade) {
		return new OrgFilterCascade(json(cascade.branchesByCompany()), json(cascade.departmentsByCompany()),
				json(cascade.departmentsByBranch()), json(cascade.jobTitlesByDepartment()));
	}

	/** Whether the form carries {@code data-org-filters}, which is what the script initialises. */
	public boolean attached() {
		return this.branchesByCompany != null;
	}

	private static String json(Map<Long, List<OrgCascade.Option>> grouped) {
		return JSON.writeValueAsString(grouped);
	}
}
