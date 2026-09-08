package com.workin.backend.platformadmin.org;

import java.util.List;

/**
 * A row of {@code departments} as the dashboard's list and form need it
 * ({@code dashboard/pages/departments}).
 *
 * <p>A department is attached to one or more <b>branches</b> through
 * {@code department_branches}, and at least one is required -- that is the
 * only validation rule on this page and the reason it is not simply
 * "branches with a different table name".
 *
 * @param branchNames the joined branch names for the list column, already
 *                    ordered and comma-separated by the query's
 *                    {@code GROUP_CONCAT}; {@code null} when the department is
 *                    attached to none, which the list renders as an em dash
 * @param branchIds   populated only for the edit form, where the checkboxes
 *                    need the ids rather than the names
 */
public record Department(
		long id, long companyId, String companyName, String name, boolean active,
		String branchNames, String createdAt, int employeeCount, int jobTitleCount,
		List<Long> branchIds) {

	public Department {
		branchIds = branchIds == null ? List.of() : List.copyOf(branchIds);
	}

	/** {@code org_department_branches_display()}: an em dash when there are none. */
	public String branchesDisplay() {
		return this.branchNames == null || this.branchNames.trim().isEmpty()
				? "—" : this.branchNames.trim();
	}

	/** {@code substr((string) $row['created_at'], 0, 10)}. */
	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	/**
	 * {@code org_department_parse_branch_ids_from_post()}: the posted ids,
	 * deduplicated, in first-seen order, with anything non-positive dropped.
	 *
	 * <p>The deduplication is not cosmetic. {@code $ids[$id] = $id} is a map
	 * keyed by the id, and {@code org_department_sync_branches()} inserts one
	 * row per entry into a table whose {@code (department_id, branch_id)} pair
	 * is unique -- so a form posting the same branch twice would fail the
	 * insert without it.
	 */
	public static List<Long> parseBranchIds(String[] raw) {
		if (raw == null) {
			return List.of();
		}
		java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
		for (String value : raw) {
			long id = phpInt(value);
			if (id > 0) {
				ids.add(id);
			}
		}
		return List.copyOf(ids);
	}

	/** {@code (int) $value}: the leading integer, or 0. */
	private static long phpInt(String raw) {
		if (raw == null) {
			return 0L;
		}
		String trimmed = raw.trim();
		int end = 0;
		if (end < trimmed.length() && (trimmed.charAt(end) == '+' || trimmed.charAt(end) == '-')) {
			end++;
		}
		while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
			end++;
		}
		String digits = trimmed.substring(0, end);
		if (digits.isEmpty() || "+".equals(digits) || "-".equals(digits)) {
			return 0L;
		}
		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException ex) {
			return 0L;
		}
	}

	/** One row of the branch picker: {@code org_branches_for_company()}. */
	public record BranchOption(long id, String name, String companyName) {
	}

}
