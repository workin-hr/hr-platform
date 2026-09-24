package com.workin.backend.platformadmin.org;

import java.util.function.LongFunction;

/**
 * Which company an org write actually affected, for the audit row.
 *
 * <p>{@code assertWritable} resolves the company a write is made *against*, and
 * for an unscoped administrator that is the posted form field, checked against
 * nothing: R-061 records this as ruled-on parity, because a platform
 * administrator is cross-company by design. But it means the posted value is
 * where the operator was standing, not necessarily whose row they edited -- and
 * an audit entry naming the wrong company is worse than one naming none,
 * because it reads as authoritative.
 *
 * <p>So the entry names the row's own owner, and when the two differ it says
 * both. That is the case an auditor most needs to see, and it was previously the
 * one the record hid.
 *
 * <p>One copy, not one per page. The four org services are deliberately
 * parallel -- each mirrors its own PHP page -- but this sentence is not page
 * behaviour, it is the wording of a record somebody reads later, and four copies
 * of a sentence drift. Two of the six defective call sites are one line apart in
 * different files; a reader comparing two audit rows must not have to wonder
 * whether the difference is real.
 *
 * <p>Not needed everywhere: {@code DepartmentAdminService.saveEdit} and
 * {@code JobTitleAdminService.saveEdit} already resolve {@code ownerOf(id)}
 * themselves and pass that, so for those two the posted value never reaches the
 * record and a lookup here would be a second query for an answer already in
 * hand. The six methods that use this are the ones that audit
 * {@code assertWritable}'s value directly.
 */
final class OrgAuditDetail {

	private OrgAuditDetail() {
	}

	/**
	 * @param rowId         the row the write touched
	 * @param postedAgainst the company the write was made against, which for an
	 *                      administrator is the posted field
	 * @param ownerOf       the store's own {@code companyOf}, which returns null
	 *                      when the row is gone -- a delete that matched nothing
	 *                      has already been refused before this is called, so a
	 *                      null here means the row vanished between the write and
	 *                      the record, and the posted value is then the only
	 *                      thing left to say
	 */
	static String affected(long rowId, long postedAgainst, LongFunction<Long> ownerOf) {
		Long owner = ownerOf.apply(rowId);
		if (owner == null || owner == postedAgainst) {
			return String.valueOf(postedAgainst);
		}
		return owner + " (the administrator posted company " + postedAgainst + ")";
	}

}
