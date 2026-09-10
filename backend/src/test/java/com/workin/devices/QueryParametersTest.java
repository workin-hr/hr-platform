package com.workin.devices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class QueryParametersTest {

	@Test
	void readsTheParametersADeviceSends() {
		assertThat(QueryParameters.parse("SN=BOCK200961014&table=ATTLOG&Stamp=9999"))
				.containsEntry("SN", "BOCK200961014")
				.containsEntry("table", "ATTLOG")
				.containsEntry("Stamp", "9999");
	}

	@Test
	void decodesEscapesAndPluses() {
		assertThat(QueryParameters.parse("DeviceType=middle%20east&other=a+b"))
				.containsEntry("DeviceType", "middle east")
				.containsEntry("other", "a b");
	}

	@Test
	void handlesTheEmptyAndValuelessCases() {
		assertThat(QueryParameters.parse(null)).isEmpty();
		assertThat(QueryParameters.parse("")).isEmpty();
		assertThat(QueryParameters.parse("SN")).containsEntry("SN", "");
		assertThat(QueryParameters.parse("SN=")).containsEntry("SN", "");
	}

	/** A repeated name must not let a caller choose which value a handler sees. */
	@Test
	void theFirstOccurrenceOfARepeatedNameWins() {
		assertThat(QueryParameters.parse("SN=real&SN=spoofed")).containsEntry("SN", "real");
	}

	@Test
	void aMalformedEscapeIsKeptRatherThanRefused() {
		assertThat(QueryParameters.parse("SN=%zz")).containsEntry("SN", "%zz");
	}

	@Test
	void aRedundantSeparatorDoesNotEndTheParse() {
		// `SN=DEV-1&&table=ATTLOG` used to stop at the gap, so `table` was never
		// read: the upload fell into the unknown-table default, discarded the
		// whole punch body, and answered 200 OK -- which the terminal takes as
		// delivery and drops records that were never stored.
		Map<String, String> parsed = QueryParameters.parse("SN=DEV-1&&table=ATTLOG");

		assertThat(parsed).containsEntry("SN", "DEV-1").containsEntry("table", "ATTLOG");
	}

	@Test
	void leadingAndTrailingSeparatorsAreIgnored() {
		assertThat(QueryParameters.parse("&SN=DEV-1&table=ATTLOG&"))
				.containsEntry("SN", "DEV-1").containsEntry("table", "ATTLOG");
	}

	@Test
	void aQueryStringOfNothingButSeparatorsIsBoundedAndEmpty() {
		// Empty segments now cost an iteration instead of ending the parse, so
		// the work has to be bounded some other way.
		assertThat(QueryParameters.parse("&".repeat(5000))).isEmpty();
	}

	@Test
	void realParametersAreStillFoundAfterManySeparators() {
		assertThat(QueryParameters.parse("&".repeat(50) + "table=ATTLOG"))
				.containsEntry("table", "ATTLOG");
	}
}
