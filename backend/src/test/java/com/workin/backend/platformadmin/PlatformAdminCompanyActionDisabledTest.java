package com.workin.backend.platformadmin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

	@Test
	void theSurfaceRefusesAdministrativeActionsByDefault() {
		assertThat(this.companyService.actionsEnabled()).isFalse();

		assertThat(this.companyService.apply(1L, true,
				PlatformAdminCompanyService.ACTION_SUSPEND, 1L, "reason", "approval"))
			.isEqualTo(PlatformAdminCompanyService.Outcome.SURFACE_DISABLED);
	}

}
