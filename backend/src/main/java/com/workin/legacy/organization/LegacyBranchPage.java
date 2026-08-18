package com.workin.legacy.organization;

import java.util.List;

/** {@code list.php}'s {@code pagination_meta()} companion -- same flat shape as {@code LegacyExceptionTypePage} (Wave 12.1 precedent). */
public record LegacyBranchPage(
		List<LegacyBranchListItem> data,
		int page,
		int limit,
		long total,
		int totalPages,
		boolean hasNext,
		boolean hasPrevious) {
}
