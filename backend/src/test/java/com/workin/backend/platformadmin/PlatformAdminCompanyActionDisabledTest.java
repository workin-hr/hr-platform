package com.workin.backend.platformadmin;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;
import com.workin.backend.platformadmin.org.BranchAdminService;
import com.workin.backend.platformadmin.web.DashboardSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The default must be off.
 *
 * <p>ADR-0015 prerequisite 7 makes reaching the PHP admin surface a shipment
 * gate, and no code can check whether that surface is reachable. So the flag
 * exists, defaults closed, and this test pins the default -- a property whose
 * safe value depends on nobody having overridden it is not a gate.
 */
class PlatformAdminCompanyActionDisabledTest extends AbstractIntegrationTest {

	@Autowired
	private PlatformAdminCompanyService companyService;

	@Autowired
	@Qualifier("legacyDataSource")
	private DataSource legacyDataSource;

	@Autowired
	private BranchAdminService branchService;

	@Test
	void theSurfaceRefusesAdministrativeActionsByDefault() {
		assertThat(this.companyService.actionsEnabled()).isFalse();

		assertThat(this.companyService.apply(1L,
				PlatformAdminCompanyService.ACTION_SUSPEND, 1L, "reason"))
			.isEqualTo(PlatformAdminCompanyService.Outcome.SURFACE_DISABLED);
	}

	@Test
	void theSurfaceRefusesToDeleteACompanyByDefault() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long companyId = jdbc.queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status)"
						+ " VALUES ('Closed surface', '+9000000245', 'unused-hash', 'active') RETURNING id",
				Long.class);

		assertThat(this.companyService.delete(1L, companyId, "Closed surface"))
			.isEqualTo(PlatformAdminCompanyService.Outcome.SURFACE_DISABLED);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE id = ?", Integer.class, companyId))
			.as("even the right name deletes nothing while the surface is closed")
			.isOne();
	}

	/**
	 * The branch QR window is rendered by the server from {@code ?action=qr&id=N}, behind a
	 * link the switch does not gate, and its expiry field renders with the switch off -- the
	 * same shape {@code branch-form.jte:25} has always had, where the form is written and only
	 * the submit is gated. So the field can be filled and submitted with the keyboard while the
	 * button is absent. This pins what happens then: the write is refused before it reaches the
	 * store, and the row is untouched (raised by Codex on #305).
	 */
	@Test
	void theSurfaceRefusesAGeneratedQrCodeByDefault() {
		JdbcTemplate jdbc = new JdbcTemplate(this.legacyDataSource);
		long companyId = jdbc.queryForObject(
				"INSERT INTO companies (company_name, phone, password_hash, status)"
						+ " VALUES ('Closed surface qr', '+9000000246', 'unused-hash', 'active') RETURNING id",
				Long.class);
		long branchId = jdbc.queryForObject(
				"INSERT INTO branches (company_id, name, is_active, created_at)"
						+ " VALUES (?, 'Closed surface branch', 1, NOW()) RETURNING id",
				Long.class, companyId);

		assertThatThrownBy(() -> this.branchService.generateQr(
				DashboardSession.admin(0L), 1L, branchId, companyId,
				"2099-01-01T23:59", java.time.LocalDateTime.now()))
			.isInstanceOf(BranchAdminService.RefusedException.class)
			.extracting(thrown -> ((BranchAdminService.RefusedException) thrown).refusal())
			.isEqualTo(BranchAdminService.Refusal.ACTIONS_DISABLED);

		assertThat(jdbc.queryForObject(
				"SELECT qr_code FROM branches WHERE id = ?", String.class, branchId))
			.as("a keyboard submission with the switch off writes no code")
			.isNull();
	}

}
