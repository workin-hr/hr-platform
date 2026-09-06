package com.workin.backend.platformadmin.hr;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workin.backend.platformadmin.PlatformAdminAuditEventType;
import com.workin.backend.platformadmin.PlatformAdminAuditService;
import com.workin.backend.platformadmin.web.DashboardSession;
import com.workin.legacy.payroll.LegacyPayrollBatchService;
import com.workin.legacy.payroll.LegacyPayrollBatchStore;
import com.workin.legacy.payroll.LegacyPayrollFiscalSettings;
import com.workin.legacy.wire.LegacyApiException;

/**
 * The write half of {@code dashboard/pages/payroll/page.php}.
 *
 * <p><b>This page is where the port diverges from its source, and the
 * divergence is authorization only (R-064).</b> Five actions take {@code id}
 * from the request. {@code create_run} resolves its company server-side and is
 * correct. The other four — {@code calculate}, {@code finalize},
 * {@code reopen}, {@code delete_run} — and {@code edit_detail} pass the posted
 * id straight to {@code dbUpdate}/{@code dbDelete}/{@code dbFind}, each of
 * which is a plain {@code WHERE id = ?}. {@code 505004f} closed the identical
 * hole on the attendance page next door and did not reach this one. Every
 * action here is guarded with the same shape {@code hr_verify_post_row()} uses
 * there. Nothing else about the page's behaviour is changed.
 *
 * <p><b>What is deliberately <em>not</em> reused.</b>
 * {@link LegacyPayrollBatchService} has {@code finalize()} and {@code reopen()}
 * methods with these exact names and a scoped lookup already built in, and
 * calling them would have been the short path. They are the <em>API</em>'s
 * finalize and reopen: they rewrite the batch's fiscal period, apply advance
 * payments, mark or unmark penalties, and refuse a second finalize. The
 * dashboard's are bare status flips — {@code dbUpdate('payroll_batches',
 * ['status' => PAY_FINALIZED], $id)} and nothing more. Routing the page
 * through the service would silently start applying financial side effects
 * that this page has never applied, which is a behaviour change wearing an
 * authorization fix's clothing. The bare writes are kept and the divergence
 * between the two PHP paths is recorded as a legacy finding rather than
 * quietly resolved here.
 *
 * <p>{@code calculate} is the opposite case and <em>is</em> reused:
 * {@code payroll_calculate_batch()} is literally the same function the API
 * endpoint calls, so {@link LegacyPayrollBatchService#calculate} gives parity
 * and the scoped batch lookup in one.
 */
@Service
@Profile("phase1-mysql")
public class PayrollAdminService {

	public enum Refusal {

		/** {@code admin_actions_disabled}. */
		ACTIONS_DISABLED,

		/** {@code mfa_required_for_actions}. */
		FACTOR_NOT_BOUND,

		/** {@code error_db}: the batch or payslip belongs to another company. */
		FOREIGN_ROW,

		/** {@code error_required}: no company, or an unusable month/year. */
		INVALID,

		/** {@code payroll_batch_exists}: one batch per company per month. */
		DUPLICATE_PERIOD
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

	private final PayrollStore store;

	private final LegacyPayrollBatchStore batchStore;

	private final LegacyPayrollBatchService batchService;

	private final LegacyPayrollFiscalSettings fiscalSettings;

	private final PlatformAdminAuditService auditService;

	private final boolean actionsEnabled;

	public PayrollAdminService(
			PayrollStore store, LegacyPayrollBatchStore batchStore,
			LegacyPayrollBatchService batchService, LegacyPayrollFiscalSettings fiscalSettings,
			PlatformAdminAuditService auditService,
			@Value("${app.platform-admin.actions.enabled:false}") boolean actionsEnabled) {
		this.store = store;
		this.batchStore = batchStore;
		this.batchService = batchService;
		this.fiscalSettings = fiscalSettings;
		this.auditService = auditService;
		this.actionsEnabled = actionsEnabled;
	}

	public boolean actionsEnabled() {
		return this.actionsEnabled;
	}

	private void gate(boolean factorBound) {
		if (!this.actionsEnabled) {
			throw new RefusedException(Refusal.ACTIONS_DISABLED);
		}
		if (!factorBound) {
			throw new RefusedException(Refusal.FACTOR_NOT_BOUND);
		}
	}

	/**
	 * {@code hr_verify_post_row('payroll_batches', $id, $cid)} — the guard this
	 * page does not have. Identical in shape to the attendance page's, which is
	 * the point: one rule, applied the same way, rather than a second dialect
	 * of tenant check invented for payroll.
	 */
	private long assertBatchVisible(DashboardSession session, long batchId) {
		return assertVisible(session, this.store.companyOfBatch(batchId));
	}

	/** The same guard for a payslip, whose company arrives through its batch. */
	private long assertPayslipVisible(DashboardSession session, long payslipId) {
		return assertVisible(session, this.store.companyOfPayslip(payslipId));
	}

	private long assertVisible(DashboardSession session, Long owner) {
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

	/**
	 * {@code create_run}.
	 *
	 * <p>Already correct in the source: a company-scoped session's own company
	 * wins over the posted one, and an unfiltered administrator's posted
	 * {@code company_id} is R-044's deliberate reach rather than a hole.
	 */
	@Transactional
	public long createRun(DashboardSession session, long adminId, boolean factorBound,
			long postedCompanyId, int month, int year) {
		gate(factorBound);
		long companyId = session.isScopedToOneCompany() ? session.companyId() : postedCompanyId;
		if (companyId <= 0 || month <= 0 || month > 12 || year <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		// `$co = dbFind('companies', $cid); if ($co) { ... }` -- an unknown
		// company writes nothing and says nothing. Reproduced rather than turned
		// into an error, and reproduced rather than left out: without it the
		// insert would fail on the foreign key and surface as a 500, which is
		// worse than either.
		if (!this.store.companyExists(companyId)) {
			return companyId;
		}
		if (this.batchStore.existsForPeriod(companyId, month, year)) {
			throw new RefusedException(Refusal.DUPLICATE_PERIOD);
		}

		String[] bounds = this.fiscalSettings.fiscalPeriodBounds(companyId, year, month);
		long batchId = this.batchStore.insert(companyId, month, year, bounds[0], bounds[1], "draft");
		audit(adminId, PlatformAdminAuditEventType.ORG_CREATED, batchId,
				"payroll batch " + month + "/" + year + " created in company " + companyId);
		return companyId;
	}

	/** What a calculate reports back: the company it ran in, and how many payslips it wrote. */
	public record Calculation(long companyId, int calculated) {
	}

	/**
	 * {@code calculate}.
	 *
	 * <p>PHP reads the batch with an unscoped {@code dbFind} and skips silently
	 * when it is already finalized. The skip is kept — a finalized batch
	 * redirects with no message, exactly as it does today — but the unscoped
	 * read is not: {@link LegacyPayrollBatchService#calculate} looks the batch
	 * up scoped, so another company's id is refused instead of calculated.
	 */
	@Transactional
	public Calculation calculate(DashboardSession session, long adminId, boolean factorBound,
			long batchId, String weeklyRestLabel) {
		gate(factorBound);
		long owner = assertBatchVisible(session, batchId);
		try {
			LegacyPayrollBatchService.CalculationResult result =
					this.batchService.calculate(owner, batchId, weeklyRestLabel);
			audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, batchId,
					"payroll batch " + batchId + " calculated in company " + owner
							+ " (" + result.calculatedCount() + " payslips)");
			return new Calculation(owner, result.calculatedCount());
		}
		catch (LegacyApiException alreadyFinalized) {
			// `if ($run && $run['status'] !== PAY_FINALIZED)`: PHP falls
			// straight through to the redirect with no flash at all.
			if ("batch_already_finalized".equals(alreadyFinalized.getMessageKey())) {
				return new Calculation(owner, 0);
			}
			throw alreadyFinalized;
		}
	}

	/**
	 * {@code finalize}: {@code dbUpdate('payroll_batches', ['status' =>
	 * PAY_FINALIZED], $id)} and nothing else. See this class's note on why the
	 * API's {@code finalize()} is not called here.
	 */
	@Transactional
	public long finalizeRun(DashboardSession session, long adminId, boolean factorBound, long batchId) {
		gate(factorBound);
		long owner = assertBatchVisible(session, batchId);
		this.batchStore.updateStatus(batchId, "finalized");
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, batchId,
				"payroll batch " + batchId + " finalized in company " + owner);
		return owner;
	}

	/** {@code reopen}: the same bare status write, back to {@code draft}. */
	@Transactional
	public long reopenRun(DashboardSession session, long adminId, boolean factorBound, long batchId) {
		gate(factorBound);
		long owner = assertBatchVisible(session, batchId);
		this.batchStore.updateStatus(batchId, "draft");
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, batchId,
				"payroll batch " + batchId + " reopened in company " + owner);
		return owner;
	}

	/**
	 * {@code delete_run}.
	 *
	 * <p>PHP deletes the batch alone and lets {@code fk_payslip_payroll_batche}'s
	 * {@code ON DELETE CASCADE} take the payslips with it.
	 * {@link LegacyPayrollBatchStore#deleteWithPayslips} removes them
	 * explicitly first, which reaches the same end state on a schema that has
	 * the cascade and the right one on a schema that does not.
	 */
	@Transactional
	public long deleteRun(DashboardSession session, long adminId, boolean factorBound, long batchId) {
		gate(factorBound);
		long owner = assertBatchVisible(session, batchId);
		this.batchStore.deleteWithPayslips(batchId);
		audit(adminId, PlatformAdminAuditEventType.ORG_DELETED, batchId,
				"payroll batch " + batchId + " deleted in company " + owner);
		return owner;
	}

	/** Where {@code edit_detail} sends the operator back to, and whose company it ran in. */
	public record DetailEdit(long companyId, long batchId) {
	}

	/**
	 * {@code edit_detail}: the twelve-column payslip write.
	 *
	 * <p>The row's ownership is not writable from the request — D-176. Neither
	 * {@code batch_id} nor {@code employee_id} is in the written set, so the
	 * two columns that decide who this payslip belongs to cannot be reached
	 * from this form at all.
	 */
	@Transactional
	public DetailEdit editDetail(DashboardSession session, long adminId, boolean factorBound,
			long payslipId, PayrollStore.PayslipEdit edit) {
		gate(factorBound);
		if (payslipId <= 0) {
			throw new RefusedException(Refusal.INVALID);
		}
		long owner = assertPayslipVisible(session, payslipId);
		long batchId = this.store.batchOfPayslip(payslipId);

		this.store.updatePayslipDetail(payslipId, edit);
		audit(adminId, PlatformAdminAuditEventType.ORG_UPDATED, payslipId,
				"payslip " + payslipId + " edited in company " + owner);
		return new DetailEdit(owner, batchId);
	}

	/**
	 * {@code $_POST['x'] ?? 0} for the six fields PHP defaults, and the raw
	 * value for the six it does not — those write NULL when absent.
	 */
	public static BigDecimal decimalOrDefault(String raw, BigDecimal fallback) {
		if (raw == null || raw.isEmpty()) {
			return fallback;
		}
		try {
			return new BigDecimal(raw.trim());
		}
		catch (NumberFormatException notANumber) {
			// MariaDB's own non-strict coercion of a non-numeric string into a
			// DECIMAL column is a zero, which is what PHP's bind produces too.
			return BigDecimal.ZERO;
		}
	}

	private void audit(long adminId, PlatformAdminAuditEventType type, long id, String detail) {
		this.auditService.recordAction(adminId, type, "payroll", String.valueOf(id), null, detail);
	}

}
