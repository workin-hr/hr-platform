package com.workin.backend.platformadmin.org;

import java.util.function.LongFunction;

/**
 * Which company an org write actually affected, for the audit row.
 *
 * <p>{@code assertWritable} resolves the company a write is made *against*. For
 * an unscoped administrator -- every session these pages build -- that is the
 * posted {@code company_id}, or the dashboard filter when none is posted, and it
 * is checked against nothing. The dashboard's own forms post the row's company,
 * so the two differ only on a crafted request; R-047 records that mismatch, and
 * the owner ruled on 2026-09-23 to leave the write as it is and fix the record
 * (D-281). Before this the record named the company the request named, which
 * reads as authoritative and is not necessarily whose row changed.
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
	 * @param madeAgainst   the company {@code assertWritable} resolved the write
	 *                      against
	 * @param ownerOf       the store's own {@code companyOf}. Every caller runs
	 *                      after its own write inside one transaction, and a write
	 *                      that matched no row has already been refused, so the
	 *                      row is there; a null means the row has no owning
	 *                      company. That is possible only for a shift --
	 *                      {@code shifts.company_id} is nullable in the vendored
	 *                      schema, the other three tables' are not -- and the
	 *                      entry says so rather than naming a company as the
	 *                      owner that is not one.
	 */
	static String affected(long rowId, long madeAgainst, LongFunction<Long> ownerOf) {
		Long owner = ownerOf.apply(rowId);
		if (owner == null) {
			return madeAgainst + " (the row has no owning company)";
		}
		if (owner == madeAgainst) {
			return String.valueOf(madeAgainst);
		}
		return owner + " (made against company " + madeAgainst + ")";
	}

}
