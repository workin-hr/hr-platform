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
	 * A value that opens with two separators names another origin without naming a scheme, so
	 * the schemeless branch must not pass it through as a path on this host. A browser reads a
	 * backslash as a slash in that position, so all four pairs are the same attack, and #292's
	 * review round 2 defeated a plain {@code //} prefix test with the other three. Each pair is
	 * written as the two characters the rule reads; round 3 found two of them written three
	 * characters long, which left the {@code \/} pair unasserted. The three-character forms stay
	 * as their own cases.
	 */
	@Test
	void aValueOpeningWithTwoSeparatorsIsNotLinkedThoughItNamesNoScheme() {
		for (String opening : new String[] {"//", "\\\\", "/\\", "\\/", "/\\\\", "\\\\/"}) {
			assertThat(StoredUrl.href(opening + "evil.example/reg.pdf")).as(opening).isNull();
			assertThat(StoredUrl.href("  " + opening + "evil.example/reg.pdf")).as("%s after whitespace", opening).isNull();
		}
		assertThat(StoredUrl.href("/uploads/a.pdf")).as("one slash is still a path here").isEqualTo("/uploads/a.pdf");
		assertThat(StoredUrl.href("uploads/docs/a.pdf")).isEqualTo("uploads/docs/a.pdf");
	}

	/**
	 * A browser drops some of these before it parses, so the text checked here would not be the
	 * URL it follows: {@code /\u0009/evil.example} arrives as {@code //evil.example}. It keeps
	 * others -- {@code U+007F} is percent-encoded, and the address is followed as written -- and
	 * those are refused anyway, because no value this system writes carries one. Round 2 read
	 * five such values out of the running page.
	 */
	@Test
	void aValueCarryingAControlCharacterIsNotLinkedAtAll() {
		assertThat(StoredUrl.href("/\u0009/evil.example/reg.pdf")).as("a tab between the separators").isNull();
		assertThat(StoredUrl.href("/\n/evil.example/reg.pdf")).as("a newline").isNull();
		assertThat(StoredUrl.href("/\r/evil.example/reg.pdf")).as("a carriage return").isNull();
		assertThat(StoredUrl.href("\u0001//evil.example/reg.pdf")).as("a leading control character").isNull();
		assertThat(StoredUrl.href("https://files.example.com/a\u007fb.pdf")).as("and one inside a web address").isNull();
	}

}
