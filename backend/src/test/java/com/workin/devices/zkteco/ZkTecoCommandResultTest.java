package com.workin.devices.zkteco;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What the unauthenticated {@code /iclock/devicecmd} route is allowed to keep
 * from a body.
 *
 * <p>The receiver used to log 200 characters of whatever arrived, for anyone
 * who could produce a syntactically valid serial. Parsing is what makes the log
 * a diagnostic rather than an attacker-controlled string, and dropping unknown
 * keys is what stops it being a way around the biometric filtering
 * {@code OPERLOG} bodies get.
 */
class ZkTecoCommandResultTest {

	@Test
	void keepsOnlyTheThreeFieldsTheRouteIsDefinedToCarry() {
		var fields = ZkTecoCommandResult.parse("ID=7&Return=0&CMD=DATA");

		assertThat(fields).containsExactlyInAnyOrderEntriesOf(
				java.util.Map.of("ID", "7", "Return", "0", "CMD", "DATA"));
	}

	@Test
	void dropsEverythingElse() {
		// A biometric template pasted into this route must not survive parsing:
		// OPERLOG filters those, and this route must not be the way around it.
		var fields = ZkTecoCommandResult.parse(
				"ID=7&Return=0&CMD=DATA&FP=BASE64TEMPLATEDATA&BIODATA=more&note=arbitrary");

		assertThat(fields).containsOnlyKeys("ID", "Return", "CMD");
		assertThat(fields.values()).noneMatch(v -> v.contains("TEMPLATE"));
	}

	@Test
	void acceptsTheLineSeparatedFormSomeFirmwareSends() {
		var fields = ZkTecoCommandResult.parse("ID=9\r\nReturn=-1\r\nCMD=INFO");

		assertThat(fields).containsEntry("ID", "9").containsEntry("Return", "-1")
				.containsEntry("CMD", "INFO");
	}

	@Test
	void aBodyCarryingNoneOfThemYieldsNothingToLog() {
		var fields = ZkTecoCommandResult.parse("<script>alert(1)</script> and a very long line");

		assertThat(fields).isEmpty();
	}

	@Test
	void theFirstOccurrenceWinsSoARepeatedKeyCannotOverwriteTheDiagnostic() {
		var fields = ZkTecoCommandResult.parse("ID=7&ID=injected&Return=0&CMD=DATA");

		assertThat(fields).containsEntry("ID", "7");
	}

	@Test
	void aNullOrEmptyBodyIsNotAnError() {
		assertThat(ZkTecoCommandResult.parse(null)).isEmpty();
		assertThat(ZkTecoCommandResult.parse("")).isEmpty();
	}
}
