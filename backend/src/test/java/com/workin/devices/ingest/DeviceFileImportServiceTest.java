package com.workin.devices.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.workin.devices.zkteco.ZkTecoAttlogParser;

class DeviceFileImportServiceTest {

	/** A USB export right-aligns the PIN in spaces; the parser's PIN rule is digits only. */
	@Test
	void aUsbExportLineParsesToTheSamePunchAsThePushedLine() {
		String exported = "﻿        2001\t2026-09-16 08:02:11\t0\t1\t0\t0\r\n\r\n";
		String pushed = "2001\t2026-09-16 08:02:11\t0\t1\t0\t0";

		String normalised = String.join("\n", DeviceFileImportService.normalisedLines(exported));
		ZkTecoAttlogParser.Result fromFile = ZkTecoAttlogParser.parse("SN1", normalised, ZoneId.of("Africa/Cairo"));
		ZkTecoAttlogParser.Result fromPush = ZkTecoAttlogParser.parse("SN1", pushed, ZoneId.of("Africa/Cairo"));

		assertThat(fromFile.malformed()).isZero();
		assertThat(fromFile.events()).hasSize(1);
		assertThat(fromFile.events().get(0).dedupKey())
				.as("so importing a file the terminal already pushed stores nothing twice")
				.isEqualTo(fromPush.events().get(0).dedupKey());
	}

	@Test
	void blankLinesAreNotRecordsAndOtherLinesAreKeptForTheParserToJudge() {
		assertThat(DeviceFileImportService.normalisedLines("\n  \n 1\t2026-01-01 00:00:00\nrubbish\n"))
				.containsExactly("1\t2026-01-01 00:00:00", "rubbish");
	}
}
