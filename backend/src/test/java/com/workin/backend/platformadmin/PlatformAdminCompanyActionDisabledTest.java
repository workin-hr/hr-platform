package com.workin.backend.platformadmin;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.workin.backend.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

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

}
