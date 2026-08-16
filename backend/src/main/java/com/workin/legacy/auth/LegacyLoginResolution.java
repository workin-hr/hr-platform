package com.workin.legacy.auth;

/**
 * The result of the legacy login decision: an outcome, and the row it
 * authenticated when there is one.
 *
 * @param authenticated null unless {@code outcome} is
 *        {@link LegacyLoginOutcome#SUCCESS}
 */
public record LegacyLoginResolution(
		LegacyLoginOutcome outcome, LegacyLoginCandidate authenticated) {

	static LegacyLoginResolution rejected(LegacyLoginOutcome outcome) {
		return new LegacyLoginResolution(outcome, null);
	}

	static LegacyLoginResolution success(LegacyLoginCandidate candidate) {
		return new LegacyLoginResolution(LegacyLoginOutcome.SUCCESS, candidate);
	}

}
