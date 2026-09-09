package com.workin.devices.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.workin.devices.assignment.DeviceAssignmentTimeline.Assignment;
import com.workin.devices.assignment.DeviceAssignmentTimeline.Resolution;
import com.workin.devices.assignment.DeviceAssignmentTimeline.Resolved;

/** The rule for deciding which configuration a punch belongs to. */
class DeviceAssignmentTimelineTest {

	private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
	private static final ZoneId LONDON = ZoneId.of("Europe/London");
	private static final long BRANCH_A = 101;
	private static final long BRANCH_B = 202;

	private static LocalDateTime at(String iso) {
		return LocalDateTime.parse(iso.replace(' ', 'T'));
	}

	/** A in Cairo from 1 June; B in London from 3 June 09:00Z. */
	private static DeviceAssignmentTimeline twoConfigurations() {
		return new DeviceAssignmentTimeline(List.of(
				new Assignment(1, BRANCH_A, CAIRO, at("2025-06-01 00:00:00")),
				new Assignment(2, BRANCH_B, LONDON, at("2025-06-03 09:00:00"))));
	}

	@Test
	void aBufferedPunchDeliveredAfterAReassignmentStaysWithTheBranchItHappenedIn() {
		// 2 June 06:00Z is inside A's interval, whatever the registry says now.
		Resolved resolved = twoConfigurations().forInstant(at("2025-06-02 06:00:00"));

		assertThat(resolved.branchId()).isEqualTo(BRANCH_A);
		assertThat(resolved.resolution()).isEqualTo(Resolution.EXACT);
		assertThat(resolved.assignmentId()).isEqualTo(1L);
	}

	@Test
	void aWallClockPunchUsesTheZoneInForceThenNotTheZoneInForceNow() {
		// 08:00 wall clock on 2 June, while the device was in CAIRO (+03 in
		// June) -- 05:00Z. Converting with the CURRENT zone (London, +01) would
		// have produced 07:00Z: two hours wrong, in the column everything else
		// now orders and compares by.
		Resolved resolved = twoConfigurations().forWallClock(at("2025-06-02 08:00:00"));

		assertThat(resolved.instantUtc()).isEqualTo(at("2025-06-02 05:00:00"));
		assertThat(resolved.branchId()).isEqualTo(BRANCH_A);
		assertThat(resolved.resolution()).isEqualTo(Resolution.EXACT);
	}

	@Test
	void anEpochPunchAroundTheSameChangeResolvesDirectlyByItsInstant() {
		assertThat(twoConfigurations().forInstant(at("2025-06-03 08:59:59")).branchId()).isEqualTo(BRANCH_A);
		assertThat(twoConfigurations().forInstant(at("2025-06-03 09:00:00")).branchId()).isEqualTo(BRANCH_B);
		assertThat(twoConfigurations().forInstant(at("2025-06-03 09:00:00")).resolution())
				.as("the boundary instant belongs to the row that starts at it")
				.isEqualTo(Resolution.EXACT);
	}

	@Test
	void aWallClockThatFitsTwoConfigurationsIsUnresolvedRatherThanGuessed() {
		// Cairo (+03) until 10:00Z on 3 June, then a FIXED +02 zone. The wall
		// clock 12:30 maps to 09:30Z under Cairo -- inside the first interval --
		// and to 10:30Z under +02 -- inside the second. Both are plausible.
		DeviceAssignmentTimeline timeline = new DeviceAssignmentTimeline(List.of(
				new Assignment(1, BRANCH_A, CAIRO, at("2025-06-01 00:00:00")),
				new Assignment(2, BRANCH_B, ZoneId.of("+02:00"), at("2025-06-03 10:00:00"))));

		Resolved resolved = timeline.forWallClock(at("2025-06-03 12:30:00"));

		assertThat(resolved.resolution()).isEqualTo(Resolution.UNRESOLVED);
		assertThat(resolved.instantUtc()).as("no instant is asserted").isNull();
		assertThat(resolved.branchId()).as("and no branch").isNull();
	}

	@Test
	void aDstOverlapWallClockWithTwoPlausibleInstantsIsNotCollapsedToOne() {
		// London's autumn fold: 01:30 on 26 October 2025 happens twice, at
		// 00:30Z (BST) and 01:30Z (GMT). atZone() would silently return the
		// first; both are real, so neither may be asserted.
		DeviceAssignmentTimeline timeline = new DeviceAssignmentTimeline(List.of(
				new Assignment(1, BRANCH_A, LONDON, at("2025-01-01 00:00:00"))));

		Resolved resolved = timeline.forWallClock(at("2025-10-26 01:30:00"));

		assertThat(resolved.resolution())
				.as("two valid offsets for one wall clock is ambiguity, not a default")
				.isEqualTo(Resolution.UNRESOLVED);
		assertThat(resolved.instantUtc()).isNull();
	}

	@Test
	void aPunchPredatingAllHistoryIsVisiblyInferredNeverExact() {
		Resolved resolved = twoConfigurations().forInstant(at("2025-05-30 06:00:00"));

		assertThat(resolved.resolution()).isEqualTo(Resolution.INFERRED_EARLIEST);
		assertThat(resolved.branchId()).as("the earliest branch is offered").isEqualTo(BRANCH_A);
		assertThat(resolved.resolution()).isNotEqualTo(Resolution.EXACT);
	}

	@Test
	void anOrdinaryCurrentPunchIsExact() {
		Resolved resolved = twoConfigurations().forInstant(at("2025-06-04 12:00:00"));

		assertThat(resolved.resolution()).isEqualTo(Resolution.EXACT);
		assertThat(resolved.branchId()).isEqualTo(BRANCH_B);
	}

	@Test
	void twoRowsSharingAnInstantAreOrderedDeterministicallyById() {
		DeviceAssignmentTimeline timeline = new DeviceAssignmentTimeline(List.of(
				new Assignment(9, BRANCH_B, CAIRO, at("2025-06-01 00:00:00")),
				new Assignment(4, BRANCH_A, CAIRO, at("2025-06-01 00:00:00"))));

		assertThat(timeline.forInstant(at("2025-06-02 00:00:00")).assignmentId())
				.as("the higher id wins the tie, and does so every time")
				.isEqualTo(9L);
	}

	@Test
	void aDeviceWithNoHistoryResolvesNothing() {
		DeviceAssignmentTimeline empty = new DeviceAssignmentTimeline(List.of());

		assertThat(empty.forInstant(at("2025-06-02 06:00:00")).resolution()).isEqualTo(Resolution.UNRESOLVED);
		assertThat(empty.forWallClock(at("2025-06-02 08:00:00")).resolution()).isEqualTo(Resolution.UNRESOLVED);
	}
}
