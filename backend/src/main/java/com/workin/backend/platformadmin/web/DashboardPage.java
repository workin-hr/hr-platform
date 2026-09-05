package com.workin.backend.platformadmin.web;

import java.util.List;

/**
 * {@code dbPaginate()}'s return shape ({@code dashboard/includes/query.php}),
 * for the dashboard's list pages.
 *
 * <p>Every field is what the pager template reads, and two of them are not
 * what a fresh implementation would produce.
 *
 * <h2>The page number is normalised after the offset is taken</h2>
 * <p>PHP computes {@code $offset} from the requested page, then clamps
 * {@code $page} to the last real page, then queries with the <em>unclamped</em>
 * offset. So asking for page 99 of a three-page list returns <b>no rows</b>
 * while reporting {@code page: 3} -- the pager highlights the last page and
 * the table beneath it is empty. That is visible behaviour, so it is
 * reproduced rather than corrected; correcting it here would make the port
 * disagree with the system it is replacing on a URL a user can type.
 *
 * <p>{@code from} and {@code to} come from the same unclamped offset, so they
 * are equally out of range on that request.
 *
 * @param data    the rows for this page
 * @param total   rows matching the filters, ignoring pagination
 * @param page    the clamped page number, never below 1
 * @param perPage the clamped page size
 * @param pages   total pages, {@code 0} when there are no rows at all
 * @param from    1-based index of the first row shown, {@code 0} when empty
 * @param to      1-based index of the last row shown
 */
public record DashboardPage<T>(
		List<T> data, int total, int page, int perPage, int pages, int from, int to) {

	/** {@code PAGE_SIZE_DEFAULT}. */
	public static final int SIZE_DEFAULT = 10;

	/** {@code PAGE_SIZE_MAX}. */
	public static final int SIZE_MAX = 200;

	/**
	 * Assembles the shape from a count and the rows already fetched, applying
	 * the clamps in {@code dbPaginate()}'s own order.
	 *
	 * @param requestedPage    the page as asked for, before clamping
	 * @param requestedPerPage the size as asked for, before clamping
	 */
	public static <T> DashboardPage<T> of(
			List<T> rows, int total, int requestedPage, int requestedPerPage) {
		int perPage = Math.max(1, Math.min(requestedPerPage, SIZE_MAX));
		int page = Math.max(1, requestedPage);
		int offset = (page - 1) * perPage;
		int pages = total > 0 ? (int) Math.ceil((double) total / perPage) : 0;
		// The clamp lands here, after the offset above was already taken.
		int clampedPage = Math.min(page, Math.max(1, pages));
		return new DashboardPage<>(
				List.copyOf(rows), total, clampedPage, perPage, pages,
				total > 0 ? offset + 1 : 0,
				Math.min(offset + perPage, total));
	}

	/** The {@code LIMIT ? OFFSET ?} arguments, from the <em>requested</em> page. */
	public static int offsetFor(int requestedPage, int perPage) {
		return (Math.max(1, requestedPage) - 1) * Math.max(1, Math.min(perPage, SIZE_MAX));
	}

	public boolean isEmpty() {
		return this.data.isEmpty();
	}

}
