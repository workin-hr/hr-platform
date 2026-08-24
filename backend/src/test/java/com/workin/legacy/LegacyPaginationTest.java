package com.workin.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link LegacyPagination} against {@code helpers/pagination.php}.
 *
 * <p>These are the rules every paginating legacy module shares, and the reason
 * the helper is shared rather than copied: each one is easy to reimplement
 * <em>almost</em> correctly, and an almost-correct copy diverges from PHP only
 * for particular query strings.
 */
class LegacyPaginationTest {

	private static LegacyQueryParameters query(String queryString) {
		return LegacyQueryParameters.parse(queryString);
	}

	@Nested
	@DisplayName("search_query_param")
	class Search {

		@Test
		void aMissingOrBlankSearchIsNull() {
			// `trim(...) === '' ? null : $v` -- so ?search= filters nothing at
			// all rather than matching LIKE '%%'.
			assertThat(LegacyPagination.searchQueryParam(query(null))).isNull();
			assertThat(LegacyPagination.searchQueryParam(query("page=2"))).isNull();
			assertThat(LegacyPagination.searchQueryParam(query("search="))).isNull();
			assertThat(LegacyPagination.searchQueryParam(query("search=%20%20"))).isNull();
		}

		@ParameterizedTest(name = "{0} trims to Nour")
		@CsvSource({
			"'search=Nour'",
			"'search=%20Nour%20'",
			"'search=%09Nour%09'",
			"'search=%0ANour%0A'",
			"'search=%0DNour%0D'",
			"'search=%0BNour%0B'",
		})
		void stripsExactlyThePhpCharlist(String queryString) {
			// space, \t, \n, \r and \x0B are all in PHP's default charlist.
			assertThat(LegacyPagination.searchQueryParam(query(queryString))).isEqualTo("Nour");
		}

		/**
		 * The one that separates PHP's {@code trim()} from Java's. Form feed is
		 * {@code U+000C}, which {@link String#trim} removes -- it strips every
		 * character at or below {@code U+0020} -- and which PHP's default
		 * charlist does not contain. A needle keeping its form feed matches
		 * nothing; one that lost it matches the plain term, which is a
		 * different result for the same request.
		 */
		@Test
		void keepsAFormFeedThatJavasOwnTrimWouldRemove() {
			assertThat(LegacyPagination.searchQueryParam(query("search=%0CNour"))).isEqualTo("\fNour");
			assertThat(LegacyPagination.searchQueryParam(query("search=Nour%0C"))).isEqualTo("Nour\f");

			// stated as the divergence, not just the outcome
			assertThat("\fNour".trim()).isEqualTo("Nour");
			assertThat(LegacyPagination.searchQueryParam(query("search=%0CNour"))).isNotEqualTo("Nour");
		}

		@Test
		void innerWhitespaceIsUntouched() {
			assertThat(LegacyPagination.searchQueryParam(query("search=%20Nour%20Adel%20")))
					.isEqualTo("Nour Adel");
		}

	}

	@Nested
	@DisplayName("pagination_params")
	class Params {

		@Test
		void defaultsWhenNothingIsSupplied() {
			LegacyPagination.Params params = LegacyPagination.params(query(null));
			assertThat(params.page()).isEqualTo(1);
			assertThat(params.limit()).isEqualTo(20);
			assertThat(params.offset()).isZero();
		}

		/** {@code $raw ?: $defaultLimit} -- a limit that casts to 0 is the default, not 1. */
		@ParameterizedTest(name = "{0} falls back to the default limit of 20")
		@ValueSource(strings = { "limit=0", "limit=abc", "limit=", "limit=0.4" })
		void aLimitThatCastsToZeroBecomesTheDefaultNotOne(String queryString) {
			assertThat(LegacyPagination.params(query(queryString)).limit()).isEqualTo(20);
		}

		@Test
		void theLimitIsCappedAndThePageFloored() {
			assertThat(LegacyPagination.params(query("limit=1000")).limit()).isEqualTo(100);
			assertThat(LegacyPagination.params(query("limit=1")).limit()).isEqualTo(1);
			assertThat(LegacyPagination.params(query("page=0")).page()).isEqualTo(1);
			assertThat(LegacyPagination.params(query("page=-5")).page()).isEqualTo(1);
		}

		@Test
		void limitWinsOverPerPageAsANullCoalesceNotAnEmptyCheck() {
			assertThat(LegacyPagination.params(query("limit=5&per_page=50")).limit()).isEqualTo(5);
			assertThat(LegacyPagination.params(query("per_page=50")).limit()).isEqualTo(50);
			// `limit=` is present, so `??` takes it, and it then casts to 0 --
			// which is the default. per_page is never consulted.
			assertThat(LegacyPagination.params(query("limit=&per_page=50")).limit()).isEqualTo(20);
		}

		@Test
		void theOffsetFollowsThePageAndLimit() {
			assertThat(LegacyPagination.params(query("page=3&limit=10")).offset()).isEqualTo(20);
		}

	}

	@Nested
	@DisplayName("pagination_meta")
	class Meta {

		@Test
		void keyOrderIsPartOfTheContract() {
			assertThat(LegacyPagination.meta(0, LegacyPagination.params(query(null))).keySet())
					.containsExactly("page", "limit", "total", "total_pages", "has_next", "has_previous");
		}

		@Test
		void pagesRoundUpAndTheFlagsFollowThem() {
			LegacyPagination.Params first = LegacyPagination.params(query("page=1&limit=10"));
			assertThat(LegacyPagination.meta(25, first)).containsEntry("total_pages", 3L)
					.containsEntry("has_next", true).containsEntry("has_previous", false);

			LegacyPagination.Params last = LegacyPagination.params(query("page=3&limit=10"));
			assertThat(LegacyPagination.meta(25, last))
					.containsEntry("has_next", false).containsEntry("has_previous", true);
		}

		@Test
		void anEmptyResultHasNoPagesAndNoNext() {
			assertThat(LegacyPagination.meta(0, LegacyPagination.params(query(null))))
					.containsEntry("total_pages", 0L).containsEntry("has_next", false);
		}

	}

}
