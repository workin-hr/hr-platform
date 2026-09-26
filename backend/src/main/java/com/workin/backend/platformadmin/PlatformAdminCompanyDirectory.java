package com.workin.backend.platformadmin;

import com.workin.legacy.phone.CanonicalPhone;
import java.util.List;

/**
 * The companies this surface administers, over whichever database the active
 * profile selects.
 *
 * <p>This exists because the platform-admin surface runs under both profiles
 * and they do not share a company mapping: the PostgreSQL domain has
 * {@code identity.Company}, and the Phase 1 profile has
 * {@code legacy.companies.LegacyCompany} over the frozen MySQL table. The
 * alternative -- letting {@link PlatformAdminCompanyService} depend on one of
 * them -- is what made it fail to start on MySQL with
 * {@code NoSuchBeanDefinitionException: CompanyRepository}.
 *
 * <p>The lifecycle actions run through {@link CompanyView} alone. What only a
 * page shows stays out of it: the list's columns in {@code CompanyDirectoryStore},
 * and the detail page's card, counts and tables in {@link CompanyDetail}.
 */
public interface PlatformAdminCompanyDirectory {

	/**
	 * @param id the company's identifier
	 * @param name its name, which is null for a pending signup (D-035)
	 * @param status one of {@code active}, {@code pending}, {@code rejected}, {@code suspended}
	 */
	record CompanyView(long id, String name, String status) {
	}

	/**
	 * One company as {@code dashboard/pages/companies/detail.php} shows it: the header card, six
	 * counts and three tables, plus the rejection reason the port's own actions show beside them.
	 *
	 * @param activeEmployees  {@code dbCount('employees', ['company_id' => $cid, 'is_active' => 1])},
	 *                         which legacy labels {@code total_employees}
	 * @param totalEmployees   every employee row, active or not; also the employees table's count
	 * @param checkedInToday   distinct employees with a check-in dated {@code CURDATE()}
	 * @param branches         every branch, active or not, by name
	 * @param staff            the HR, manager and company-admin employees, by role
	 * @param employees        the first {@link #EMPLOYEES_LISTED} employees, active first, then by name
	 */
	record CompanyDetail(CompanyView company, Profile profile, String rejectionReason,
			long activeEmployees, long totalEmployees, long checkedInToday,
			long pendingRequests, long pendingAdvances,
			List<Branch> branches, List<StaffUser> staff, List<Employee> employees) {

		/** {@code array_slice($employees, 0, 15)} ({@code detail.php:80}). */
		public static final int EMPLOYEES_LISTED = 15;

		/** {@code detail.php:82}'s "and N more", zero when the table lists every employee. */
		public long moreEmployees() {
			return Math.max(0L, this.totalEmployees - EMPLOYEES_LISTED);
		}

		/** {@code company_logo_src()}'s fallback name: {@code trim($name) ?: 'C'}. */
		public String avatarName() {
			return com.workin.backend.platformadmin.companies.CompanyRow.avatarName(this.company.name());
		}

		/** The header card's fields, which the lifecycle actions have no use for. */
		public record Profile(String phone, String email, boolean otpVerified, String createdAt,
				String logoUrl, String commercialRegUrl) {

			/** {@code $company['email'] ?? '—'}: a dash only for no value, not for an empty one. */
			public String emailLabel() {
				return this.email == null ? "—" : this.email;
			}

			/** {@code substr($company['created_at'], 0, 10)}. */
			public String registeredOn() {
				return com.workin.backend.platformadmin.hr.EmployeeDisplay.date(this.createdAt);
			}

			/**
			 * The logo the card loads, or null to draw the initials instead.
			 * {@code company_logo_src()} reaches {@code dashboard_media_url()} only for a value
			 * that names http or https, or that is a file on this server
			 * ({@code company_media_file_exists()}, {@code company_helper.php:146-176}); anything
			 * else falls back to an avatar, so legacy never fetches {@code //host/p.png}. This
			 * has no file check, and uses {@code StoredUrl} to keep the same value off the page.
			 */
			public String logoSrc() {
				return com.workin.backend.platformadmin.hr.StoredUrl.href(this.logoUrl);
			}

			public boolean hasLogo() {
				return logoSrc() != null;
			}

			/**
			 * The commercial registration button's link, or null for no button.
			 * {@code detail.php:50} gates the button on PHP truthiness, so a stored {@code "0"}
			 * draws none; {@code StoredUrl} then decides whether the value may be a link.
			 */
			public String commercialRegHref() {
				return "0".equals(this.commercialRegUrl) ? null
						: com.workin.backend.platformadmin.hr.StoredUrl.href(this.commercialRegUrl);
			}
		}

		/** A branch and its active employees ({@code detail.php:14}'s {@code ec}). */
		public record Branch(String name, long activeEmployees) {
		}

		/** An HR, manager or company-admin employee: the phone and the role badge. */
		public record StaffUser(String phone, String role) {
		}

		/**
		 * One row of the employees table.
		 *
		 * @param code      {@code dashboard_employee_code_sql()}: the stored code, else the id
		 * @param name      first and last name joined and trimmed
		 * @param hireDate  as stored, or null
		 */
		public record Employee(long id, String code, String name, String phone, String branchName,
				String hireDate, boolean active) {

			/** {@code dashboard_employee_display_name($e)}: an em dash for a blank name. */
			public String nameLabel() {
				return com.workin.backend.platformadmin.hr.EmployeeDisplay.displayName(this.name, "—");
			}

			/** {@code $e['branch_name'] ?? '—'}: an employee whose branch row is gone. */
			public String branchLabel() {
				return this.branchName == null ? "—" : this.branchName;
			}

			/** {@code $e['hire_date'] ? substr($e['hire_date'], 0, 10) : '—'}. */
			public String hireDateLabel() {
				return com.workin.backend.platformadmin.hr.EmployeeDisplay.date(this.hireDate);
			}
		}
	}

	List<CompanyView> list(int limit);

	/**
	 * The dial codes {@code company_country_codes()} offers. The three lookup
	 * selects are not here: {@code CompanyDirectoryStore} already supplies
	 * them to this page for its filters, and a second copy would be a second
	 * ordering to keep in step.
	 */
	List<String> dialCodes();

	/** Another company holds the number, in any stored spelling (D-291). */
	boolean phoneTaken(CanonicalPhone phone, long excludeCompanyId);

	boolean companyCodeTaken(String companyCode, long excludeCompanyId);

	boolean lookupsExist(long activityId, long titleId, long sizeId);

	/**
	 * One company as the form needs it: the ids the selects bind to and the
	 * name split the way the row stores it, neither of which the list's
	 * {@code CompanyRow} carries -- it holds display names for a table.
	 */
	record EditableCompany(long id, String companyName, String firstName, String lastName,
			String countryCode, String phone, String mainBranchAddress,
			long activityId, long titleId, long sizeId, String companyCode) {
	}

	java.util.Optional<EditableCompany> editable(long companyId);

	/** The stored logo and commercial-registration URLs, for an edit that uploads neither. */
	record StoredFiles(String logoUrl, String commercialRegUrl) {
	}

	java.util.Optional<StoredFiles> storedFiles(long companyId);

	/**
	 * {@code company_admin_create()}: the row, then its main branch. Returns
	 * the new id.
	 */
	long create(CompanyForm.CompanyWrite write, String passwordHash,
			String logoUrl, String commercialRegUrl);

	/**
	 * {@code company_admin_update()}: the row, then its main branch -- updated
	 * where one exists and inserted where it does not, as the PHP does.
	 *
	 * @param passwordHash null leaves the stored password alone
	 */
	void update(long companyId, CompanyForm.CompanyWrite write, String passwordHash,
			String logoUrl, String commercialRegUrl);

	java.util.Optional<CompanyDetail> detail(long companyId);

	/** A company as deleting it needs it: what to show, and what the operator must type back. */
	record DeletionTarget(CompanyView company, String phone) {

		/**
		 * The name; the phone for a company without one; the id for a company
		 * with neither. Never blank, so an empty field can never confirm a delete.
		 * {@link #normalised}, because it is also what the page shows.
		 */
		public String confirmationText() {
			String name = normalised(company.name());
			if (!name.isEmpty()) {
				return name;
			}
			String number = normalised(phone);
			return number.isEmpty() ? "#" + company.id() : number;
		}

		/**
		 * Format characters (zero-width and bidi marks) removed, and every run of
		 * whitespace, non-breaking spaces included, made one space: what a browser
		 * shows of the text, and so all a reader can type back.
		 */
		public static String normalised(String text) {
			return text == null ? "" : text.replaceAll("\\p{Cf}", "").replaceAll("(?U)\\s+", " ").strip();
		}
	}

	java.util.Optional<DeletionTarget> deletionTarget(long companyId);

	/**
	 * @return whether a company with that id existed and was updated
	 */
	boolean updateStatus(long companyId, String status);

	/**
	 * Rejects a company, recording why.
	 *
	 * <p>Separate from {@link #updateStatus} rather than a nullable parameter on
	 * it, because the reason is written on rejection and on nothing else. PHP
	 * behaves the same way: approving a previously rejected company leaves the
	 * old reason in place rather than clearing it, and a single method with an
	 * optional argument would quietly invite the opposite.
	 *
	 * @return whether a company with that id existed and was updated
	 */
	boolean reject(long companyId, String reason);

}
