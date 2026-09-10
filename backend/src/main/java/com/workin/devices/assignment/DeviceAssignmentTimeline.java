package com.workin.devices.assignment;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One device's configuration history, and the rule for deciding which row
 * applied to a punch.
 *
 * <p>Loaded once per delivery and resolved in memory: a reconnect can carry
 * thousands of buffered punches, and a query each would turn one upload into
 * thousands of round trips.
 *
 * <p>The rule is asymmetric on purpose. An <b>instant</b> identifies its
 * configuration directly. A <b>wall clock</b> does not: the same reading maps
 * into a different instant under each historical zone, and around a DST fold it
 * maps to two instants under one zone. Where more than one of those lands
 * inside its own row's interval, the punch is genuinely ambiguous and this says
 * so rather than picking.
 */
public final class DeviceAssignmentTimeline {

	/** One configuration, in force from {@code effectiveFromUtc} until the next row. */
	public record Assignment(long id, long branchId, ZoneId zone, LocalDateTime effectiveFromUtc) {
	}

	public enum Resolution {
		/** One historical configuration, established rather than assumed. */
		EXACT,
		/** Predates all history: the earliest configuration, acknowledged as a guess. */
		INFERRED_EARLIEST,
		/** More than one configuration is plausible, or none is. Not attributable. */
		UNRESOLVED
	}

	/**
	 * @param instantUtc null when unresolved -- there is no instant we are
	 *        entitled to assert, and a placeholder would read like a measurement
	 * @param branchId null for the same reason
	 */
	public record Resolved(Long assignmentId, Long branchId, LocalDateTime instantUtc, Resolution resolution) {

		static final Resolved UNRESOLVED = new Resolved(null, null, null, Resolution.UNRESOLVED);
	}

	private final List<Assignment> ordered;

	public DeviceAssignmentTimeline(List<Assignment> rows) {
		List<Assignment> copy = new ArrayList<>(rows);
		// effective_from then id: two rows can share an instant, and "whichever
		// the database happened to return" is not a rule.
		copy.sort(Comparator.comparing(Assignment::effectiveFromUtc).thenComparingLong(Assignment::id));
		this.ordered = List.copyOf(copy);
	}

	public boolean isEmpty() {
		return ordered.isEmpty();
	}

	/** A punch that reported an instant: it names its own configuration. */
	public Resolved forInstant(LocalDateTime instantUtc) {
		if (ordered.isEmpty()) {
			return Resolved.UNRESOLVED;
		}
		Assignment found = null;
		Assignment previous = null;
		for (Assignment candidate : ordered) {
			if (!candidate.effectiveFromUtc().isAfter(instantUtc)) {
				previous = found;
				found = candidate;
			} else {
				break;
			}
		}
		if (found == null) {
			Assignment earliest = ordered.get(0);
			return new Resolved(earliest.id(), earliest.branchId(), instantUtc, Resolution.INFERRED_EARLIEST);
		}
		// A punch sharing its SECOND with the change that created this
		// assignment is not attributable. Both the ATTLOG timestamp and
		// effective_from_utc have second precision, so a punch that physically
		// preceded the claim or reassignment inside that second is
		// indistinguishable from one that followed it -- and the inclusive
		// comparison above chose the new row and called it EXACT, which
		// attributed the punch to a branch or zone it did not happen under.
		//
		// Only when the two candidates actually DISAGREE. A history row that
		// changed neither branch nor zone leaves both answers identical, and
		// quarantining a punch over a distinction without a difference would
		// cost real attendance for nothing.
		if (previous != null && found.effectiveFromUtc().equals(instantUtc)
				&& (previous.branchId() != found.branchId()
						|| !previous.zone().equals(found.zone()))) {
			return new Resolved(found.id(), found.branchId(), instantUtc, Resolution.UNRESOLVED);
		}
		return new Resolved(found.id(), found.branchId(), instantUtc, Resolution.EXACT);
	}

	/**
	 * A punch that reported a wall clock. Every historical zone is applied to
	 * it, and a candidate counts only if the instant it produces falls inside
	 * the interval of the row whose zone produced it.
	 */
	public Resolved forWallClock(LocalDateTime wallClock) {
		if (ordered.isEmpty()) {
			return Resolved.UNRESOLVED;
		}
		List<Resolved> candidates = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			Assignment row = ordered.get(i);
			LocalDateTime end = i + 1 < ordered.size() ? ordered.get(i + 1).effectiveFromUtc() : null;
			// getValidOffsets, not atZone: a DST fold gives a wall clock TWO
			// offsets, and atZone silently returns the earlier. Both are real.
			for (ZoneOffset offset : row.zone().getRules().getValidOffsets(wallClock)) {
				LocalDateTime instant = wallClock.minusSeconds(offset.getTotalSeconds());
				boolean insideOwnInterval = !instant.isBefore(row.effectiveFromUtc())
						&& (end == null || instant.isBefore(end));
				if (insideOwnInterval) {
					candidates.add(new Resolved(row.id(), row.branchId(), instant, Resolution.EXACT));
				}
			}
		}

		List<LocalDateTime> distinctInstants = candidates.stream()
				.map(Resolved::instantUtc).distinct().toList();
		if (distinctInstants.size() == 1 && candidates.size() == 1) {
			return candidates.get(0);
		}
		if (!candidates.isEmpty()) {
			// Two plausible readings. Choosing the earlier offset, or whichever
			// configuration is current, would be a coin toss recorded as fact.
			return Resolved.UNRESOLVED;
		}

		// Nothing fits. If the wall clock lands before the first configuration
		// under that configuration's own zone, this is a pre-claim buffered
		// punch: attributable to the earliest row, but as a guess.
		Assignment earliest = ordered.get(0);
		for (ZoneOffset offset : earliest.zone().getRules().getValidOffsets(wallClock)) {
			LocalDateTime instant = wallClock.minusSeconds(offset.getTotalSeconds());
			if (instant.isBefore(earliest.effectiveFromUtc())) {
				return new Resolved(earliest.id(), earliest.branchId(), instant,
						Resolution.INFERRED_EARLIEST);
			}
		}
		return Resolved.UNRESOLVED;
	}
}
