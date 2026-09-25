package com.workin.backend.platformadmin.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A row action returns to the list the administrator was on (#347), and the
 * referrer it reads that from cannot steer the redirect anywhere else.
 */
class AdminReturnToTest {

	private static final String PATH = "/admin/employees";

	private static MockHttpServletRequest from(String referer) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
		if (referer != null) {
			request.addHeader("Referer", referer);
		}
		return request;
	}

	@Test
	void theListsPageAndFiltersComeBack() {
		assertThat(AdminReturnTo.query(from("https://admin.example/admin/employees?page=3&per_page=50&search=aya"),
				PATH, 0L))
				.isEqualTo("?page=3&per_page=50&search=aya");
	}

	@Test
	void withoutAReferrerItIsTheBarePathAsBefore() {
		assertThat(AdminReturnTo.query(from(null), PATH, 0L)).isEmpty();
		assertThat(AdminReturnTo.query(from(""), PATH, 0L)).isEmpty();
		assertThat(AdminReturnTo.queryWithError(from(null), PATH, "employee_refused"))
				.isEqualTo("?error=employee_refused");
	}

	@Test
	void anotherPagesQueryIsNeverCarried() {
		assertThat(AdminReturnTo.query(from("https://admin.example/admin/branches?page=4"), PATH, 0L))
				.as("a different path").isEmpty();
		assertThat(AdminReturnTo.query(from("https://admin.example/admin/employees/x?page=4"), PATH, 0L))
				.as("a path that merely starts the same").isEmpty();
		assertThat(AdminReturnTo.query(from("not a url at all %%"), PATH, 0L))
				.as("a malformed referrer").isEmpty();
	}

	@Test
	void nothingInTheReferrerReachesTheLocationUnescaped() {
		String hostile = "https://admin.example/admin/employees?search=a%0D%0ASet-Cookie:%20x=1"
				+ "&page=2%26admin=1&%3Cscript%3E=1&Bad-Key=1";
		String query = AdminReturnTo.query(from(hostile), PATH, 0L);
		assertThat(query)
				.as("CR/LF and & in a value are re-encoded; a key that is not a dashboard key is dropped")
				.isEqualTo("?search=a%0D%0ASet-Cookie%3A+x%3D1&page=2%26admin%3D1")
				.doesNotContain("\r").doesNotContain("\n").doesNotContain("<");
	}

	@Test
	void thePreviousMessageAndTheFormItOpenedAreDropped() {
		assertThat(AdminReturnTo.queryWithError(
				from("https://admin.example/admin/employees?page=2&error=old&action=edit&id=9"),
				PATH, "new_error"))
				.isEqualTo("?page=2&error=new_error");
	}

	@Test
	void aCompanyFilterTheWriteLeftIsDroppedWithItsPages() {
		String referer = "https://admin.example/admin/employees?company_id=7&page=3&dev_page=2&search=aya";
		assertThat(AdminReturnTo.query(from(referer), PATH, 9L))
				.as("the row was written to company 9; rememberAfterWrite's filter must apply")
				.isEqualTo("?search=aya");
		assertThat(AdminReturnTo.query(from(referer), PATH, 7L))
				.as("the same company: everything stays")
				.isEqualTo("?company_id=7&page=3&dev_page=2&search=aya");
		assertThat(AdminReturnTo.query(from(referer), PATH, 0L))
				.as("no company known: nothing to compare, everything stays")
				.isEqualTo("?company_id=7&page=3&dev_page=2&search=aya");
	}

	@Test
	void aListParameterKeepsItsBrackets() {
		assertThat(AdminReturnTo.query(from("https://admin.example/admin/employees?status%5B%5D=a&status%5B%5D=b"),
				PATH, 0L))
				.isEqualTo("?status%5B%5D=a&status%5B%5D=b");
	}

	@Test
	void theNumberOfCarriedParametersIsBounded() {
		StringBuilder referer = new StringBuilder("https://admin.example/admin/employees?");
		for (int index = 0; index < 100; index++) {
			referer.append("k").append(index).append("=1&");
		}
		assertThat(AdminReturnTo.query(from(referer.toString()), PATH, 0L).split("&")).hasSize(40);
	}
}
