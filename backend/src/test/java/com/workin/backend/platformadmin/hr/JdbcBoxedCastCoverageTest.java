package com.workin.backend.platformadmin.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No row mapper may cast a {@code getObject()} result to a box type.
 *
 * <p>The defect this exists for: {@code EmployeeStore} read
 * {@code contract_duration_months} as {@code (Integer) rs.getObject(...)}. The
 * column is {@code int(10) unsigned}, whose range does not fit a signed
 * {@code int}, so MariaDB Connector/J boxes it as a {@code Long} and the cast
 * threw {@code ClassCastException} -- a 500 on the whole employees page, for
 * every row where the column is not null.
 *
 * <p><b>Why the suite did not catch it.</b> Not one fixture in
 * {@code AdminEmployeesEndToEndTest} ever set that column, so the cast never
 * ran. 1,448 of the development seed's 3,783 employees carry a value, and the
 * page failed on the first real data it was shown. A unit test proves a mapper
 * handles the values it was handed; it says nothing about the ones it was not.
 *
 * <p>So this is the rule rather than the instance: the box type the driver
 * chooses is a property of the <em>column</em> -- its width and its signedness
 * -- and no reader of Java source can see it. Read the value with the typed
 * getter that cannot be wrong ({@code getLong}, {@code getInt},
 * {@code getBigDecimal}, {@code getString}) and use {@code wasNull()} where
 * NULL and zero must be told apart, exactly as
 * {@link com.workin.legacy.LegacyJdbcValues} does for the legacy surface.
 *
 * <p>Probing for null with an untyped {@code Object x = rs.getObject(...)} and
 * then reading through a typed getter is fine, and is what
 * {@code AttendanceStore} already does; the cast is the thing that cannot be
 * verified from the source.
 */
class JdbcBoxedCastCoverageTest {

	private static final Path MAIN = Path.of("src/main/java/com/workin");

	/** {@code (Integer) rs.getObject(...)}, and every box type it could be. */
	private static final Pattern BOXED_CAST = Pattern.compile(
			"\\(\\s*(Integer|Long|Short|Byte|Double|Float|Boolean|Character|Number)\\s*\\)"
					+ "\\s*(?:\\(\\s*Object\\s*\\)\\s*)?[A-Za-z_][A-Za-z0-9_]*\\.getObject\\s*\\(");

	@Test
	@DisplayName("no source casts a getObject() result to a box type")
	void noBoxedCastOnGetObject() {
		List<String> offenders = new ArrayList<>();

		for (Path file : javaSources()) {
			// Comments blanked rather than removed, so a reported line number is
			// still the line in the file. Scanning raw source made this test
			// fail on its own javadoc, which quotes the defect -- the same
			// mistake check_client_endpoints_drift.py already fixed once, where
			// a commented-out constant counted as a live endpoint.
			String source = withoutComments(read(file));
			Matcher matcher = BOXED_CAST.matcher(source);
			while (matcher.find()) {
				int line = (int) source.substring(0, matcher.start()).chars()
						.filter(character -> character == '\n').count() + 1;
				offenders.add(MAIN.relativize(file) + ":" + line + " " + matcher.group().trim());
			}
		}

		assertThat(offenders)
				.describedAs("a getObject() result cast to a box type. The driver picks the box "
						+ "from the column's width and signedness, which is not visible here: "
						+ "int(10) unsigned comes back as a Long and the cast throws at runtime, "
						+ "on real data, having passed every test whose fixtures left the column "
						+ "null. Read it with getLong/getInt/getBigDecimal/getString and use "
						+ "wasNull() where NULL and zero differ")
				.isEmpty();
	}

	@Test
	@DisplayName("the rule is proven against the shape it exists to catch")
	void theRuleMatchesTheDefectItWasWrittenFor() {
		// Guards the guard: a regex that matched nothing would pass the case
		// above for the wrong reason, forever.
		assertThat(BOXED_CAST.matcher("(Integer) rs.getObject(\"contract_duration_months\"),").find())
				.as("the original defect").isTrue();
		assertThat(BOXED_CAST.matcher("(Long) resultSet.getObject(1)").find())
				.as("any box type, any ResultSet variable name").isTrue();
		assertThat(BOXED_CAST.matcher("(Integer) (Object) rs.getObject(\"x\")").find())
				.as("and the double cast that silences the compiler").isTrue();

		assertThat(BOXED_CAST.matcher("Object id = rs.getObject(\"exception_type_id\");").find())
				.as("an untyped probe is the correct idiom and must not be flagged").isFalse();
		assertThat(BOXED_CAST.matcher("rs.getObject(\"min_total\") == null ? null : rs.getInt(\"x\")")
				.find())
				.as("nor a probe used as a null test").isFalse();

		// And why the scan blanks comments first: this file quotes the defect
		// in its own javadoc, and the first run flagged itself. Offsets are
		// preserved so a reported line number still points at the right line.
		String commented = "a(); // (Integer) rs.getObject(\"x\")\nb();";
		assertThat(withoutComments(commented))
				.as("a line comment is blanked, and nothing shifts")
				.hasSameSizeAs(commented)
				.startsWith("a(); ")
				.endsWith("\nb();")
				.doesNotContain("getObject");

		String inString = "String u = \"http://x\"; // note";
		assertThat(withoutComments(inString))
				.as("a // inside a string literal is not a comment")
				.hasSameSizeAs(inString)
				.startsWith("String u = \"http://x\";")
				.doesNotContain("note");

		String block = "/* (Long) rs.getObject(1) */ c();";
		assertThat(withoutComments(block))
				.as("a block comment is blanked")
				.hasSameSizeAs(block)
				.endsWith("c();")
				.doesNotContain("getObject");
	}

	/**
	 * Replaces every comment with spaces, preserving newlines and string
	 * literals -- so a {@code //} inside {@code "http://..."} is not a comment,
	 * and a quote inside a comment does not open a string.
	 */
	static String withoutComments(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int at = 0;
		while (at < source.length()) {
			char character = source.charAt(at);
			if (character == '"' || character == '\'') {
				int end = at + 1;
				while (end < source.length()) {
					if (source.charAt(end) == '\\') {
						end += 2;
						continue;
					}
					if (source.charAt(end) == character) {
						end++;
						break;
					}
					end++;
				}
				end = Math.min(end, source.length());
				out.append(source, at, end);
				at = end;
				continue;
			}
			if (character == '/' && at + 1 < source.length() && source.charAt(at + 1) == '/') {
				int end = source.indexOf('\n', at);
				end = end < 0 ? source.length() : end;
				out.append(" ".repeat(end - at));
				at = end;
				continue;
			}
			if (character == '/' && at + 1 < source.length() && source.charAt(at + 1) == '*') {
				int end = source.indexOf("*/", at + 2);
				end = end < 0 ? source.length() : end + 2;
				for (int scan = at; scan < end; scan++) {
					out.append(source.charAt(scan) == '\n' ? '\n' : ' ');
				}
				at = end;
				continue;
			}
			out.append(character);
			at++;
		}
		return out.toString();
	}

	private static List<Path> javaSources() {
		try (Stream<Path> tree = Files.walk(MAIN)) {
			return tree.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".java"))
					.sorted()
					.toList();
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + MAIN.toAbsolutePath(), ex);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + file, ex);
		}
	}

}
