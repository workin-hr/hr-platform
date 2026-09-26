package com.workin.legacy.employees;

import java.util.Collection;
import java.util.List;

import com.workin.legacy.LegacyValues;
import com.workin.legacy.auth.LegacyRequestContext;
import com.workin.legacy.employees.LegacyEmployee.Role;

/**
 * D-289's rule that {@code can_employees} is not authority over a peer's
 * credentials: an HR session that could set another HR user's, a manager's or
 * the company admin's password, phone or active flag could take that account
 * over -- D-076's escalation. PHP admits it on {@code employees/update.php}
 * and {@code employees/update_bulk.php} alike; the port refuses it on both,
 * through this one rule, with legacy's {@code forbidden}.
 *
 * <p>A plain employee's credentials, the actor's own row, every other field,
 * and a company admin acting on anyone are all untouched by it.
 */
public final class LegacyHrPeerCredentials {

	/** The fields that let whoever sets them sign in as the account, or keep its owner out. */
	public static final List<String> FIELDS = List.of("password", "phone", "country_code", "is_active");

	private LegacyHrPeerCredentials() {
	}

	/**
	 * Whether {@code actor} may not write {@code fields} on the employee
	 * {@code targetId}, whose stored {@code role} is {@code targetRole}.
	 *
	 * @param fields the credential fields the write would set; any other names
	 *        are ignored
	 */
	public static boolean refuses(
			LegacyRequestContext actor, long targetId, Object targetRole, Collection<String> fields) {
		return actor.role() == Role.HR
				&& targetId != actor.employeeId()
				&& !"employee".equals(LegacyValues.toPhpString(targetRole))
				&& fields.stream().anyMatch(FIELDS::contains);
	}
}
