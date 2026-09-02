package com.workin.devices;

import static org.assertj.core.api.Assertions.assertThat;

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
}
