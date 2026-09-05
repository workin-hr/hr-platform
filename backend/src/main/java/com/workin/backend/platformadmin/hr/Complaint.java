package com.workin.backend.platformadmin.hr;

/**
 * A row of {@code complaints} as the dashboard's list needs it
 * ({@code dashboard/pages/complaints}).
 *
 * <p>Two kinds share the table, told apart by {@code source}: an
 * {@code employee} complaint raised inside a company, and a
 * {@code company_support} one the company raised to the platform. Who may see
 * and change which is most of this page's behaviour.
 *
 * @param employeeId nullable -- a {@code company_support} complaint has no
 *                   employee behind it, and an employee whose row was deleted
 *                   takes the reference with it ({@code ON DELETE CASCADE})
 */
public record Complaint(
		long id, Long employeeId, Long companyId, String companyName, String source, String name,
		String email, String phone, String message, String status, String reply,
		String createdAt) {

	/** {@code source = 'employee'}: raised by a member of staff. */
	public boolean fromEmployee() {
		return "employee".equals(this.source);
	}

	public String createdDate() {
		return this.createdAt == null
				? "" : this.createdAt.substring(0, Math.min(10, this.createdAt.length()));
	}

	/**
	 * The three values {@code set_status} accepts.
	 *
	 * <p>{@code reply} does <b>not</b> validate against this list -- it writes
	 * {@code $_POST['status'] ?? 'pending'} straight through. The column is an
	 * enum, so a value outside it becomes the empty string under the non-strict
	 * mode production runs in, which is how a complaint can end up with no
	 * status at all. Reproduced with the validation the port adds on the reply
	 * path too, because writing an empty enum is not behaviour worth carrying.
	 */
	public static final java.util.List<String> STATUSES =
			java.util.List.of("pending", "done", "closed");

	/**
	 * The null check is not decoration: {@code List.of(...)} is an immutable
	 * list, and its {@code contains(null)} <b>throws</b> rather than answering
	 * false. The status filter is absent on most requests, so without this the
	 * page 500s whenever nobody has chosen one.
	 */
	public static boolean isValidStatus(String status) {
		return status != null && STATUSES.contains(status);
	}

}
