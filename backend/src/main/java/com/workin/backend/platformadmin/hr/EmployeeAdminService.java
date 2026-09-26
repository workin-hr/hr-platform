package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.legacy.PhpCast;
import com.workin.legacy.phone.CanonicalPhone;
import com.workin.legacy.phone.LegacyPhoneNumbers;

/**
 * The write half of {@code dashboard/pages/employees/page.php}.
 *
 * <p>The page behind <b>R-053</b>, and the reason that entry is High rather
 * than Medium: legacy's POST block carries no tenant check at all, and one of
 * the four actions it gates writes {@code password_hash} -- the column
 * {@code login_employee.php} verifies. A company owner could set another
 * tenant's employee's password and then sign in as them from the mobile app.
 * Another deletes a row that fourteen tables cascade from.
 *
 * <p><b>D-176</b> here has both halves. {@code company_id} stays out of the
 * update -- as it already does in legacy -- and the three org foreign keys are
 * validated against the company read from the <b>existing row</b>, which legacy
 * does not check at all. The shift is held to the same rule, because an
 * assignment row pointing at another tenant's shift is the same defect wearing
 * a different column name.
 */
@Service
public class EmployeeAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,


		/** {@code error_required}: a missing company, name, code or shift. */
		INVALID,

		/** {@code employee_code_invalid}: not one to sixty-four digits. */
		CODE_INVALID,

		/** {@code employee_code_already_exists}: taken within this company. */
		CODE_TAKEN,

		/** {@code error_invalid_phone}. */
		PHONE_INVALID,

		/** {@code phone_exists}: another employee holds the number, in any spelling (D-291). */
		PHONE_TAKEN,

		/** {@code error_db}: the row is not this session's to touch. */
		FOREIGN_ROW
	}

	public static class RefusedException extends RuntimeException {

		private final transient Refusal refusal;

		public RefusedException(Refusal refusal) {
			super(refusal.name());
			this.refusal = refusal;
		}

		public Refusal refusal() {
			return this.refusal;
		}
	}

	/** {@code dashboard_employee_code_is_valid()}: {@code /^[0-9]{1,64}$/}. */
	private static final Pattern CODE_FORMAT = Pattern.compile("^[0-9]{1,64}$");

	/** The opening balance legacy grants every new employee. */

	private final EmployeeStore store;

	private final PlatformAdminAuditService auditService;

	private final LegacyPhoneNumbers phoneNumbers;

	private final BCryptPasswordEncoder employeePasswordEncoder = new BCryptPasswordEncoder();

	private final boolean actionsEnabled;

	/**
	 * Legacy's clock. The hire date defaulted below is written to the row, and
	 * {@code LegacyClock}'s own note says why that matters: PHP dates it from
	 * the timezone the database hands it, so the JVM default would file a
	 * late-evening hire under the wrong day.
	 */
	private final com.workin.legacy.LegacyClock clock;

	public EmployeeAdminService(
			EmployeeStore store, PlatformAdminAuditService auditService,
			LegacyPhoneNumbers phoneNumbers, com.workin.legacy.LegacyClock clock,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.auditService = auditService;
		this.phoneNumbers = phoneNumbers;
		this.clock = clock;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	private void gate() {
		if (!this.actionsEnabled) {
			throw new RefusedException(Refusal.ACTIONS_DISABLED);
		}
	}

	/**
	 * The guard legacy's POST block does not have (<b>R-053</b>). Returns the
	 * row's own company, which is the only company the rest of an edit may
	 * consult.
	 */
	private long assertRowVisible(DashboardSession session, long id) {
		Long owner = this.store.companyOf(id);
		if (owner == null) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		if (session.isScopedToOneCompany()) {
			if (owner != session.companyId()) {
				throw new RefusedException(Refusal.FOREIGN_ROW);
			}
			return owner;
		}
		if (session.companyId() > 0 && owner != session.companyId()) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		return owner;
	}

	private static long companyForCreate(DashboardSession session, long postedCompanyId) {
		if (session.isScopedToOneCompany()) {
			return session.companyId();
		}
		return postedCompanyId > 0 ? postedCompanyId : session.companyId();
	}

	/**
	 * <b>D-176</b>, indirect half. Legacy validates none of these on either
	 * path; the branch is required by the form but never checked, and the other
	 * three are free text as far as the server is concerned.
	 *
	 * @param current the employee as stored, whose own branch, department and
	 *     job title still pass once deactivated -- keeping one is not choosing
	 *     it; null on the create path
	 */
	private void assertOrgWithinCompany(
			long companyId, Long branchId, Long departmentId, Long jobTitleId, long shiftId,
			Employee.Form current) {
		// R-055: the column is NOT NULL, and legacy writes null into it when
		// the form's select was left alone -- error 1048, uncaught, on both
		// write paths. Requiring it here refuses what could never have been
		// stored anyway.
		if (branchId == null || !this.store.belongsToCompany(
				"branches", branchId, companyId, current == null ? 0 : current.branchId())) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (departmentId != null && !this.store.belongsToCompany(
				"departments", departmentId, companyId, current == null ? 0 : current.departmentId())) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (jobTitleId != null && !this.store.belongsToCompany(
				"job_titles", jobTitleId, companyId, current == null ? 0 : current.jobTitleId())) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (shiftId > 0 && !this.store.belongsToCompany("shifts", shiftId, companyId)) {
			throw new RefusedException(Refusal.INVALID);
		}
	}

	/**
	 * A phone is optional, but an incomplete one is not: legacy refuses a
	 * number with no country code before it validates anything else, and
	 * refuses one that is not a number an account may hold -- the one
	 * normalizer's rule now, {@link LegacyPhoneNumbers#forAccount} (D-291).
	 *
	 * <p>PHP stored the typed digits and validated a normalised copy, so an
	 * Egyptian number typed without its leading zero was stored as
	 * {@code 10...}. Every Java write stores the number's national form and
	 * its own dial code instead (ADR-0020): one number, one stored spelling,
	 * the one PHP's clients already show.
	 *
	 * @return the number, or null when no phone was given
	 */
	private CanonicalPhone normalizedPhone(String rawPhone, String rawCountryCode) {
		String phone = rawPhone == null ? "" : rawPhone.trim();
		if (phone.isEmpty()) {
			return null;
		}
		String countryCode = rawCountryCode == null ? "" : rawCountryCode.trim();
		if (countryCode.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		String resolved = this.phoneNumbers.resolveCode(countryCode);
		if (LegacyPhoneNumbers.digitsOnly(phone).isEmpty()) {
			throw new RefusedException(Refusal.PHONE_INVALID);
		}
		return this.phoneNumbers.forAccount(phone, resolved)
				.orElseThrow(() -> new RefusedException(Refusal.PHONE_INVALID));
	}

	private void assertCode(long companyId, String code, long excludeId) {
		if (code == null || code.trim().isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		if (!CODE_FORMAT.matcher(code.trim()).matches()) {
			throw new RefusedException(Refusal.CODE_INVALID);
		}
		if (this.store.codeExistsInCompany(companyId, code, excludeId)) {
			throw new RefusedException(Refusal.CODE_TAKEN);
		}
	}

	/**
	 * {@code employee_parse_contract_months()}: a blank or non-positive
	 * duration is null, and years are stored as months.
	 *
	 * <p>{@code (int) $raw} is {@link PhpCast#intval}, so {@code "1e1"} is ten. The stored
	 * months are bounded to an {@code int}, the column's type (D-263); PHP's arithmetic is
	 * 64-bit and legacy's insert fails past it.
	 */
	static Integer contractMonths(String rawDuration, String unit) {
		String raw = rawDuration == null ? "" : rawDuration.trim();
		if (raw.isEmpty()) {
			return null;
		}
		long months = Math.min(PhpCast.intval(raw), Integer.MAX_VALUE);
		if (months <= 0) {
			return null;
		}
		return Math.clamp("years".equals(unit) ? months * 12 : months, 1, Integer.MAX_VALUE);
	}

	@Transactional
	public long add(DashboardSession session, long adminId, AddCommand command) {
		gate();
		long companyId = companyForCreate(session, command.companyId());

		String firstName = trimmed(command.firstName());
		String code = trimmed(command.employeeCode());
		// Legacy requires exactly these four, and notably not a last name.
		if (companyId <= 0 || command.shiftId() <= 0 || firstName.isEmpty() || code.isEmpty()) {
			throw new RefusedException(Refusal.INVALID);
		}
		assertCode(companyId, code, 0);
		assertOrgWithinCompany(companyId, command.branchId(), command.departmentId(),
				command.jobTitleId(), command.shiftId(), null);

		CanonicalPhone number = normalizedPhone(command.phone(), command.countryCode());
		if (number != null && this.store.phoneTaken(number, 0L)) {
			throw new RefusedException(Refusal.PHONE_TAKEN);
		}
		String phone = number == null ? "" : number.nationalDigits();
		String countryCode = number == null ? null : number.dialCode();
		// A password with no phone is silently dropped, because there would be
		// no way to sign in with it.
		String passwordHash = !phone.isEmpty() && command.password() != null
				&& !command.password().isEmpty()
						? this.employeePasswordEncoder.encode(command.password()) : null;

		String hireDate = trimmed(command.hireDate()).isEmpty()
				? this.clock.todayAsString() : trimmed(command.hireDate());
		String shiftEffective = trimmed(command.shiftEffectiveFrom()).isEmpty()
				? hireDate : trimmed(command.shiftEffectiveFrom());

		long id = this.store.insert(new EmployeeStore.EmployeeWrite(
				companyId, command.branchId(), command.departmentId(), command.jobTitleId(),
				code, firstName, trimmed(command.lastName()),
				phone.isEmpty() ? null : phone, countryCode,
				nullIfBlank(command.nationalId()), nullIfBlank(command.birthDate()),
				nullIfBlank(command.gender()), nullIfBlank(command.address()), hireDate,
				contractMonths(command.contractDuration(), command.contractDurationUnit()),
				command.mobileAttendance()), passwordHash);

		if (command.salary() != null && command.salary().basic() != null
				&& command.salary().basic().compareTo(BigDecimal.ZERO) > 0) {
			this.store.insertSalaryContract(id, command.salary(), hireDate);
		}
		this.store.syncShiftAssignment(id, command.shiftId(), shiftEffective);

		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, id,
				"employee created in company " + companyId);
		return companyId;
	}

	@Transactional
	public long saveEdit(
			DashboardSession session, long adminId, long id,
			EditCommand command) {
		gate();
		long companyId = assertRowVisible(session, id);
		Employee.Form current = this.store.editForm(id);
		if (current == null) {
			throw new RefusedException(Refusal.FOREIGN_ROW);
		}
		// Only a replaced value is validated. Legacy's rows hold what the rules
		// below refuse -- every seeded code looks like E000002, and
		// join_company.php stores a phone with no country code (R-019) -- so
		// re-checking an untouched value would make those employees uneditable.
		String code = trimmed(command.employeeCode());
		if (!code.equals(current.employeeCode().trim())) {
			assertCode(companyId, code, id);
		}
		assertOrgWithinCompany(companyId, command.branchId(), command.departmentId(),
				command.jobTitleId(), command.shiftId(), current);

		String phone;
		String countryCode;
		if (trimmed(command.phone()).equals(current.phone().trim())
				&& trimmed(command.countryCode()).equals(current.countryCode().trim())) {
			phone = current.phone();
			countryCode = current.countryCode();
		}
		else {
			CanonicalPhone number = normalizedPhone(command.phone(), command.countryCode());
			if (number != null && this.store.phoneTaken(number, id)) {
				throw new RefusedException(Refusal.PHONE_TAKEN);
			}
			phone = number == null ? null : number.nationalDigits();
			countryCode = number == null ? null : number.dialCode();
		}
		// Unlike the create path, an edit hashes a password whether or not the
		// employee has a phone. Legacy's asymmetry, kept.
		String passwordHash = command.password() != null && !command.password().isEmpty()
				? this.employeePasswordEncoder.encode(command.password()) : null;

		String hireDate = trimmed(command.hireDate());
		// An empty date input over a stored value it could not hold stands for
		// that value, so the store sees it unchanged.
		String birthDateWritten = keepsUnshownDate(current.birthDate(), command.birthDate())
				? current.birthDate() : nullIfBlank(command.birthDate());
		String hireDateWritten = keepsUnshownDate(current.hireDate(), hireDate)
				? current.hireDate() : hireDate.isEmpty() ? null : hireDate;
		this.store.update(id, new EmployeeStore.EmployeeWrite(
				companyId, command.branchId(), command.departmentId(), command.jobTitleId(),
				code, trimmed(command.firstName()), trimmed(command.lastName()),
				phone, countryCode,
				nullIfBlank(command.nationalId()), birthDateWritten,
				nullIfBlank(command.gender()), nullIfBlank(command.address()),
				hireDateWritten,
				contractMonths(command.contractDuration(), command.contractDurationUnit()),
				command.mobileAttendance()), passwordHash, current);

		String postedEffective = trimmed(command.shiftEffectiveFrom());
		// The same shift with its unshown start date left empty is the current
		// assignment kept, not a new one dated from the hire date.
		boolean assignmentKept = command.shiftId() == current.shiftId()
				&& keepsUnshownDate(current.shiftEffectiveFrom(), postedEffective);
		String shiftEffective = postedEffective.isEmpty() ? hireDate : postedEffective;
		if (command.shiftId() > 0 && !shiftEffective.isEmpty() && !assignmentKept) {
			this.store.syncShiftAssignment(id, command.shiftId(), shiftEffective);
		}

		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				"employee updated in company " + companyId);
		return companyId;
	}

	/**
	 * A stored date no date input can hold -- legacy's {@code 0000-00-00} --
	 * reaches the browser as an empty input and comes back empty. Left empty,
	 * it keeps what is stored; a date typed over it replaces it.
	 */
	private static boolean keepsUnshownDate(String stored, String posted) {
		return trimmed(posted).isEmpty() && !stored.isEmpty() && !Employee.Form.isDateInputValue(stored);
	}

	@Transactional
	public long setActive(
			DashboardSession session, long adminId, long id, boolean active) {
		gate();
		long companyId = assertRowVisible(session, id);

		this.store.setActive(id, active);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, id,
				(active ? "employee reactivated in company " : "employee deactivated in company ")
						+ companyId);
		return companyId;
	}

	/**
	 * A hard delete, as legacy's is, and fourteen tables cascade from it. The
	 * tenant check is the whole of what stands between a posted id and another
	 * company's attendance and payroll history.
	 */
	@Transactional
	public long delete(DashboardSession session, long adminId, long id) {
		gate();
		long companyId = assertRowVisible(session, id);

		this.store.delete(id);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, id,
				"employee deleted in company " + companyId);
		return companyId;
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(
				adminId, type, "employee", String.valueOf(id), detail);
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	private static String nullIfBlank(String value) {
		String trimmed = trimmed(value);
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** What {@code add_employee} reads from the form. */
	public record AddCommand(
			long companyId, Long branchId, Long departmentId, Long jobTitleId, long shiftId,
			String firstName, String lastName, String employeeCode, String phone,
			String countryCode, String password, String nationalId, String birthDate,
			String gender, String address, String hireDate, String shiftEffectiveFrom,
			String contractDuration, String contractDurationUnit, boolean mobileAttendance,
			EmployeeStore.EmployeeSalary salary) {
	}

	/** What {@code save_edit} reads. No salary: legacy's edit does not touch it. */
	public record EditCommand(
			Long branchId, Long departmentId, Long jobTitleId, long shiftId, String firstName,
			String lastName, String employeeCode, String phone, String countryCode,
			String password, String nationalId, String birthDate, String gender, String address,
			String hireDate, String shiftEffectiveFrom, String contractDuration,
			String contractDurationUnit, boolean mobileAttendance) {
	}

}
