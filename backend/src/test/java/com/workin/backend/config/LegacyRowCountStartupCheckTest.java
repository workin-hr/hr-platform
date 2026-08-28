package com.workin.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The advance and penalty race guards read a zero affected-row count as a lost race, which is
 * only sound under matched-row semantics. These cases pin the deployment-configuration guard;
 * the semantics themselves are proved against real MariaDB by
 * {@code LegacyAdvancePayEndToEndTest} and {@code LegacyPayrollBatchCalculateEndToEndTest}.
 */
class LegacyRowCountStartupCheckTest {

	@ParameterizedTest
	@ValueSource(strings = {
		"jdbc:mariadb://db:3306/workin?useAffectedRows=true",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=TRUE",
		"jdbc:mariadb://db:3306/workin?USEAFFECTEDROWS=true",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=1",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=yes",
		"jdbc:mariadb://db:3306/workin?connectTimeout=5000&useAffectedRows=true&tcpKeepAlive=true",
		// A valueless boolean option enables it, exactly as the driver treats it.
		"jdbc:mariadb://db:3306/workin?useAffectedRows",
	})
	void refusesUrlsThatEnableAffectedRows(String jdbcUrl) {
		assertThatThrownBy(() -> LegacyRowCountStartupCheck.verify(jdbcUrl))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("useAffectedRows");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"jdbc:mariadb://db:3306/workin",
		"jdbc:mariadb://db:3306/workin?connectTimeout=5000",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=false",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=FALSE",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=0",
		"jdbc:mariadb://db:3306/workin?useAffectedRows=",
		// Must match the option, not merely contain its name.
		"jdbc:mariadb://db:3306/workin?notUseAffectedRows=true",
		"jdbc:mariadb://db:3306/workin?useAffectedRowsExtra=true",
	})
	void acceptsUrlsThatLeaveMatchedRowSemanticsInPlace(String jdbcUrl) {
		assertThatCode(() -> LegacyRowCountStartupCheck.verify(jdbcUrl)).doesNotThrowAnyException();
	}

	/**
	 * The property defaults to empty because {@code LegacyPersistenceConfig}'s beans are
	 * {@code @Profile("phase1-mysql")} and are never constructed under the default profile.
	 */
	@Test
	void acceptsAnUnconfiguredLegacyUrl() {
		assertThatCode(() -> LegacyRowCountStartupCheck.verify("")).doesNotThrowAnyException();
		assertThatCode(() -> LegacyRowCountStartupCheck.verify(null)).doesNotThrowAnyException();
	}

}
