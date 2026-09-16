package com.workin.devices.ingest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.workin.devices.DeviceAttendanceEvent;
import com.workin.devices.DeviceDelivery;
import com.workin.devices.registry.AttendanceDevice;
import com.workin.devices.zkteco.ZkTecoAttlogParser;
import com.workin.legacy.LegacyClock;

/**
 * Imports an attendance log a terminal exported to a USB stick
 * ({@code attlog.dat}, {@code 1_attlog.dat}) -- the one path that works for a
 * terminal on no network at all.
 *
 * <p>The export is the ATTLOG line shape with the PIN right-aligned in spaces,
 * so each field is trimmed and the line handed to the receiver's parser. That
 * keeps the dedup key identical: importing a file whose punches the terminal
 * already pushed stores nothing twice. Which of the numeric columns after the
 * time is the in/out state has not been confirmed on hardware for the export,
 * so a mismatch shows up as duplicates that did not collapse, not as lost
 * punches -- the hardware checklist compares an export against the same
 * terminal's push.
 *
 * <p>Lines that do not parse are quarantined like a device's, so an operator
 * can see them; the rest are stored in bounded batches, each its own
 * transaction, so a large file is not one long lock.
 */
@Service
public class DeviceFileImportService {

	/** Several years of a busy terminal's log. */
	public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

	public record Result(int lines, int stored, int duplicates, int unmatched, int malformed) {
	}

	private final DevicePunchIngestionService ingestion;
	private final DeviceMalformedPunchStore malformedPunches;
	private final LegacyClock clock;
	private final int batchSize;

	public DeviceFileImportService(DevicePunchIngestionService ingestion, DeviceMalformedPunchStore malformedPunches,
			LegacyClock clock, @Value("${app.devices.ingest.max-records-per-upload}") int batchSize) {
		this.ingestion = ingestion;
		this.malformedPunches = malformedPunches;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	/** The caller has established that the device exists and that it may import for its company. */
	public Result importAttlog(AttendanceDevice device, byte[] content) {
		if (content.length > MAX_FILE_BYTES) {
			throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES + " bytes");
		}
		List<String> lines = normalisedLines(new String(content, StandardCharsets.UTF_8));
		int stored = 0;
		int duplicates = 0;
		int unmatched = 0;
		int malformed = 0;
		for (int from = 0; from < lines.size(); from += batchSize) {
			String batch = String.join("\n", lines.subList(from, Math.min(from + batchSize, lines.size())));
			ZkTecoAttlogParser.Result parsed = ZkTecoAttlogParser.parse(device.serialNumber(), batch, device.zone());
			List<DeviceAttendanceEvent> events = parsed.events();
			DevicePunchIngestionService.Outcome outcome = ingestion.ingest(device, events, DeviceDelivery.FILE);
			stored += outcome.stored();
			duplicates += outcome.duplicates();
			unmatched += outcome.unmatched();
			if (parsed.malformed() > 0) {
				malformedPunches.quarantine(device.id(), device.companyId(), parsed.malformedLines(), clock.now());
				malformed += parsed.malformed();
			}
		}
		return new Result(lines.size(), stored, duplicates, unmatched, malformed);
	}

	/** Each field trimmed; blank lines and a byte-order mark dropped. */
	static List<String> normalisedLines(String text) {
		List<String> lines = new ArrayList<>();
		String body = text.startsWith("﻿") ? text.substring(1) : text;
		for (String line : body.split("\\r?\\n")) {
			if (line.isBlank()) {
				continue;
			}
			String[] fields = line.split("\t", -1);
			for (int index = 0; index < fields.length; index++) {
				fields[index] = fields[index].strip();
			}
			lines.add(String.join("\t", fields));
		}
		return lines;
	}
}
