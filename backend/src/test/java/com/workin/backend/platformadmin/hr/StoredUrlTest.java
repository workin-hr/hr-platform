package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** D-262's rule for turning a stored file URL into a link, shared by the detail pages and the companies list. */
class StoredUrlTest {

	@Test
	void anHttpOrSchemelessUrlIsLinkedAsStored() {
		assertThat(StoredUrl.href("https://files.example.com/docs/a.pdf")).isEqualTo("https://files.example.com/docs/a.pdf");
		assertThat(StoredUrl.href("HTTP://files.example.com/a.pdf")).isEqualTo("HTTP://files.example.com/a.pdf");
		assertThat(StoredUrl.href(" /uploads/docs/a.pdf ")).isEqualTo("/uploads/docs/a.pdf");
		assertThat(StoredUrl.href("uploads/docs/a.pdf")).isEqualTo("uploads/docs/a.pdf");
		assertThat(StoredUrl.href("uploads/a:b.pdf")).as("a colon after the path starts is not a scheme")
				.isEqualTo("uploads/a:b.pdf");
	}

	@Test
	void anyOtherSchemeIsNotLinkedHoweverABrowserWouldStillReadIt() {
		assertThat(StoredUrl.href("javascript:alert(1)")).isNull();
		assertThat(StoredUrl.href(" JavaScript:alert(1)")).as("mixed case, after a space").isNull();
		assertThat(StoredUrl.href("javascript:alert(1)")).as("a browser strips a leading control character").isNull();
		assertThat(StoredUrl.href("java\tscript:alert(1)")).as("a browser drops the tab").isNull();
		assertThat(StoredUrl.href("java\nscript:alert(1)")).as("and the newline").isNull();
		assertThat(StoredUrl.href("data:text/html,<script>alert(1)</script>")).isNull();
		assertThat(StoredUrl.href("vbscript:msgbox(1)")).isNull();
		assertThat(StoredUrl.href("")).isNull();
		assertThat(StoredUrl.href(null)).isNull();
	}

	/**
	 * A protocol-relative value names another origin without naming a scheme, so the
	 * schemeless branch must not pass it through as a path on this host.
	 */
	@Test
	void aProtocolRelativeUrlIsNotLinkedEitherThoughItNamesNoScheme() {
		assertThat(StoredUrl.href("//evil.example/reg.pdf")).isNull();
		assertThat(StoredUrl.href("  //evil.example/reg.pdf")).as("after whitespace").isNull();
		assertThat(StoredUrl.href("\\\\evil.example\\reg.pdf")).as("the backslash form a browser reads the same way").isNull();
		assertThat(StoredUrl.href("/uploads/a.pdf")).as("one slash is still a path here").isEqualTo("/uploads/a.pdf");
	}

}
