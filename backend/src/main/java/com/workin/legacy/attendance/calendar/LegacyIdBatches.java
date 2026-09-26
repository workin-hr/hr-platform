package com.workin.legacy.attendance.calendar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The employee ids a set-based report read binds into {@code IN (...)}, in
 * batches (D-292).
 *
 * <p>A report reads its whole roster at once rather than one employee at a
 * time, so the id list is as long as the roster. A thousand ids per statement
 * keeps the statement count constant for a roster of up to a thousand while
 * bounding the size of any one statement: past a thousand the count grows by
 * one per thousand employees, not by one per employee.
 */
public final class LegacyIdBatches {

	/** Ids per statement. */
	public static final int SIZE = 1000;

	private LegacyIdBatches() {
	}

	/** Positive, distinct, in first-seen order: the ids a batch read may bind. */
	public static List<Long> usable(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return ids.stream().filter(id -> id != null && id > 0).distinct().toList();
	}

	/** {@code ids} cut into consecutive batches of at most {@link #SIZE}. */
	public static List<List<Long>> of(List<Long> ids) {
		List<List<Long>> batches = new ArrayList<>();
		for (int start = 0; start < ids.size(); start += SIZE) {
			batches.add(ids.subList(start, Math.min(ids.size(), start + SIZE)));
		}
		return batches;
	}

	/** {@code ?,?,...} for one batch. */
	public static String placeholders(int count) {
		return String.join(",", Collections.nCopies(count, "?"));
	}
}
