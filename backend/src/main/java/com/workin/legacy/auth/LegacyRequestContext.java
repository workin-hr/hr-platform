package com.workin.legacy.auth;

import com.workin.legacy.employees.LegacyEmployee;

/**
 * P-2: the per-request legacy auth context {@link LegacyRequestGuard#requireAuth}
 * returns -- the employee id and company id a legacy controller needs,
 * already validated by the time it holds one, plus the role claim so a
 * controller can make its own finer-grained decisions (list/one do not
 * need it; nothing else in this module currently does either, but a
 * later module might).
 */
public record LegacyRequestContext(long employeeId, long companyId, LegacyEmployee.Role role) {
}
