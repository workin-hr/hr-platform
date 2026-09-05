package com.workin.backend.platformadmin.web;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * Who is looking at the dashboard, as the three audiences PHP's
 * {@code $_SESSION} distinguishes: the platform administrator, a company
 * owner, and an HR or manager employee of one company (ADR-0016).
 *
 * <p>PHP keeps them apart with three independent session flags --
 * {@code admin_logged_in}, {@code company_logged_in}, {@code hr_logged_in} --
 * and each login path {@code unset()}s the other two. Three booleans that
 * <em>must</em> be mutually exclusive is a state machine written as flags, and
 * a missing {@code unset()} silently produces a session that is two audiences
 * at once. Here it is one enum, so that state cannot be constructed.
 *
 * <p>Nothing about authorization is decided here. {@link DashboardAccess} reads
 * this and answers; the permission set is data reloaded per session, and the
 * revalidation filter is what keeps it from outliving the row it came from.
 *
 * @param audience    which of the three
 * @param companyId   the company being acted on: the owner's or the HR
 *                    employee's own, and for an administrator the one they
 *                    have filtered to, {@code 0} meaning all companies
 * @param employeeId  the HR or manager employee's id; {@code 0} for the other
 *                    two, which are not employees
 * @param role        {@code hr} or {@code manager}; empty for the other two
 * @param permissions the {@code can_*} column names that are set on this
 *                    employee's {@code hr_permissions} row. Empty for an
 *                    administrator or an owner, who never consult it --
 *                    {@link DashboardAccess#hasFullAccess} short-circuits
 *                    before the set is read
 */
public record DashboardSession(
		Audience audience, long companyId, long employeeId, String role, Set<String> permissions)
		implements Serializable {

	/** The three PHP session flags, as the one thing they actually are. */
	public enum Audience {

		/** {@code admin_logged_in}: the platform administrator, across all companies. */
		ADMIN,

		/** {@code company_logged_in}: the owner of one company. */
		COMPANY,

		/** {@code hr_logged_in}: an HR or manager employee of one company. */
		HR
	}

	public DashboardSession {
		role = role == null ? "" : role;
		permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
	}

	public static DashboardSession admin(long filteredCompanyId) {
		return new DashboardSession(Audience.ADMIN, filteredCompanyId, 0L, "", Set.of());
	}

	public static DashboardSession company(long companyId) {
		return new DashboardSession(Audience.COMPANY, companyId, 0L, "", Set.of());
	}

	public static DashboardSession hr(
			long employeeId, long companyId, String role, Set<String> permissions) {
		return new DashboardSession(Audience.HR, companyId, employeeId, role, permissions);
	}

	/**
	 * The permission names set on an {@code hr_permissions} row.
	 *
	 * <p>PHP stores the whole row in the session after unsetting {@code id},
	 * {@code employee_id} and {@code updated_at}, then tests
	 * {@code !empty($perms[$flag])}. So a column holding {@code 0}, the empty
	 * string or {@code NULL} is "not granted" and only a truthy value grants --
	 * which for these {@code tinyint(1)} columns means {@code 1}. Collapsing the
	 * row to the set of granted names here keeps that rule in one place instead
	 * of at every call site.
	 */
	public static Set<String> grantedFrom(Map<String, Object> row) {
		if (row == null) {
			return Set.of();
		}
		return row.entrySet().stream()
				.filter(entry -> entry.getKey().startsWith("can_"))
				.filter(entry -> isTruthy(entry.getValue()))
				.map(Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/** {@code !empty($value)} for what a {@code tinyint(1)} column can hold. */
	private static boolean isTruthy(Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean flag) {
			return flag;
		}
		if (value instanceof Number number) {
			return number.longValue() != 0;
		}
		String text = value.toString().trim();
		return !text.isEmpty() && !"0".equals(text);
	}

	public boolean isAdmin() {
		return this.audience == Audience.ADMIN;
	}

	public boolean isCompany() {
		return this.audience == Audience.COMPANY;
	}

	public boolean isHr() {
		return this.audience == Audience.HR;
	}

	/** {@code org_is_scoped_company()}: bound to one company, so no filter applies. */
	public boolean isScopedToOneCompany() {
		return isCompany() || isHr();
	}

}
