package com.workin.legacy;

/**
 * A legacy value the Phase 1 contract does not know how to read.
 *
 * <p>Deliberately loud. Phase 1 tolerates the malformed legacy values
 * that are known to exist, and nulls them where null is what the column
 * means -- but an unrecognised one is schema drift or an unknown data
 * defect, and turning that into a silent null produces a confidently
 * wrong answer instead of a visible failure.
 */
public class LegacyValueException extends RuntimeException {

	public LegacyValueException(String message) {
		super(message);
	}

}
