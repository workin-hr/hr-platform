package com.workin.backend.platformadmin.hr;

/**
 * A row of {@code workforce_planning} as its list needs it
 * ({@code dashboard/pages/workforce_planning}).
 *
 * <p>How many people a company means to have in one branch, department and
 * job title, beside how many it actually has. The planned figure is stored;
 * the actual one is counted per row by a correlated subquery and is not a
 * column.
 *
 * <p>The table carries {@code company_id} of its own <i>and</i> three foreign
 * keys that each belong to a company, which makes it the fullest <b>D-176</b>
 * case on this surface: the ownership column and everything that depends on it
 * are writable in a single request.
 */
public record WorkforcePlan(
		long id, long companyId, long branchId, String branchName, long departmentId,
		String departmentName, long jobTitleId, String jobTitleName, int plannedCount,
		int actualCount) {

	/**
	 * Legacy renders each name with {@code clean($row['x'] ?? '—')}, so a
	 * {@code LEFT JOIN} that found nothing shows an em dash rather than an
	 * empty cell. A department is genuinely optional; a branch or job title
	 * reads as one only if its row has since been deleted.
	 */
	public String branchLabel() {
		return label(this.branchName);
	}

	public String departmentLabel() {
		return label(this.departmentName);
	}

	public String jobTitleLabel() {
		return label(this.jobTitleName);
	}

	private static String label(String name) {
		return name == null || name.isEmpty() ? "—" : name;
	}

	/**
	 * One entry of the form's branch, department or job-title select.
	 *
	 * <p>{@code companyName} is set only for an administrator with no company
	 * filter, who sees options from every company and needs them told apart --
	 * {@code org_option_label()}'s rule.
	 */
	public record Option(long id, String name, String companyName) {

		public String label() {
			return this.companyName == null || this.companyName.isEmpty()
					? this.name : this.name + " — " + this.companyName;
		}
	}

	/** Legacy colours the actual figure red when it is short of the plan. */
	public boolean understaffed() {
		return this.actualCount < this.plannedCount;
	}

}
