package com.workin.backend.platformadmin.org;

import java.util.List;
import java.util.Map;

/**
 * The five maps {@code org_filter_cascade_payload()} puts on a form -- branches by
 * company, departments by company and by branch, job titles by department and by
 * company -- for the companies one form may use. Each is keyed by the group's id.
 * {@link #NONE} when no form is open.
 */
public record OrgCascade(
		Map<Long, List<Option>> branchesByCompany,
		Map<Long, List<Option>> departmentsByCompany,
		Map<Long, List<Option>> departmentsByBranch,
		Map<Long, List<Option>> jobTitlesByDepartment,
		Map<Long, List<Option>> jobTitlesByCompany) {

	public static final OrgCascade NONE = new OrgCascade(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

	/** The name of job title {@code id} in the company's list, or the empty string. */
	public String jobTitleName(long companyId, long id) {
		return this.jobTitlesByCompany.getOrDefault(companyId, List.of()).stream()
				.filter(option -> option.id() == id)
				.map(Option::name)
				.findFirst()
				.orElse("");
	}

	/** One entry of a map a form script fills a select from: {@code {"id", "name"}}. */
	public record Option(long id, String name) {
	}

	/**
	 * The branch, department and job title one edited record already has, listed
	 * even once retired, for a form whose service keeps them. Zero is none.
	 */
	public record Kept(long branchId, long departmentId, long jobTitleId) {

		public static final Kept NONE = new Kept(0, 0, 0);
	}
}
