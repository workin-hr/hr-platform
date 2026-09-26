package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@link LegacyLoginThrottle} against real MariaDB, without the HTTP layer:
 * here the client address and the raw phone value are whatever the test says,
 * which a request from the test JVM cannot vary (D-289).
 */
class LegacyLoginThrottleTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String GUESSER = "198.51.100.7";

	private static final String OWNER = "203.0.113.20";

	/** When set, every DELETE the throttle prepares fails as the database would. */
	private final AtomicBoolean failDeletes = new AtomicBoolean();

	private final LegacyLoginThrottle throttle = new LegacyLoginThrottle(new FailingDeletes(
			new DriverManagerDataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword()),
			this.failDeletes));

	@BeforeEach
	void emptyBudgets() throws SQLException {
		try (Connection connection = MARIADB.connect(); Statement statement = connection.createStatement()) {
			statement.execute("DELETE FROM platform_admin_login_attempts");
		}
	}

	@Test
	void theOwnerSignsInFromTheirOwnAddressAfterAGuesserSpentThePairBudget() {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("01012345678", GUESSER);
		}
		assertRefused("01012345678", GUESSER);

		assertThat(guard("01012345678", OWNER, () -> "signed in")).isEqualTo("signed in");
	}

	@Test
	void aGuesserRotatingAddressesMeetsThePhoneWideCeiling() {
		int addresses = LegacyLoginThrottle.MAX_PHONE_MISSES / LegacyLoginThrottle.MAX_PAIR_MISSES;
		for (int address = 0; address < addresses; address++) {
			for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
				miss("01012345678", "192.0.2." + address);
			}
		}

		// Past the ceiling the phone is refused from anywhere -- the owner too,
		// until the window passes. That is the tradeoff D-289 records.
		assertRefused("01012345678", "192.0.2.200");
		assertRefused("01012345678", OWNER);
		assertThat(guard("01099999999", "192.0.2.200", () -> "another phone"))
				.isEqualTo("another phone");
	}

	@Test
	void arabicIndicPersianFullwidthAndMixedDigitsAreOnePhone() {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("01012345678", GUESSER);
		}

		assertRefused("٠١٠١٢٣٤٥٦٧٨", GUESSER);
		assertRefused("۰۱۰۱۲۳۴۵۶۷۸", GUESSER);
		assertRefused("０１０１２３４５６７８", GUESSER);
		assertRefused("0101234567٨", GUESSER);
		assertRefused(" 010-1234-5678 ", GUESSER);
	}

	@Test
	void theLookupIsGivenTheFoldedPhoneTheKeyIsMadeFrom() {
		assertThat(LegacyLoginThrottle.bindablePhone("٠١٠١٢٣٤٥٦٧٨")).isEqualTo("01012345678");
		assertThat(LegacyLoginThrottle.bindablePhone("０１０-１２３４ ５６７８")).isEqualTo("010-1234 5678");
		assertThat(LegacyLoginThrottle.bindablePhone(" +2010 ")).isEqualTo(" +2010 ");
		assertThat(LegacyLoginThrottle.bindablePhone("(010) 1234.5678")).isEqualTo("(010) 1234.5678");
		assertThat(LegacyLoginThrottle.bindablePhone("+20/10-1234")).isEqualTo("+20/10-1234");
		String bound = this.throttle.guard("۰۱۰۱۲۳۴۵۶۷۸", OWNER, UNKNOWN, phone -> phone);
		assertThat(bound).isEqualTo("01012345678");
	}

	@Test
	void aPhoneTheCollationCouldFoldButNoDigitCategoryDoesNeverReachesTheLookup() {
		// Circled, dingbat and Hangzhou digits compare equal to ASCII digits in
		// utf8mb4_unicode_ci but are not category Nd: refused, not keyed.
		for (String phone : new String[] {"⓿➀⓿➀➁➂➃➄➅➆➇", "〇〡〇〡〢〣〤〥〦〧〨", "0101234567➇", "01012345678x",
				"010\u200B12345678", "0+1012345678", "+-+2010"}) {
			assertThat(LegacyLoginThrottle.bindablePhone(phone)).as(phone).isNull();
			assertThatThrownBy(() -> this.throttle.guard(phone, GUESSER, UNKNOWN, bound -> {
				throw new AssertionError("the lookup ran for " + phone);
			})).isInstanceOfSatisfying(LegacyApiException.class,
					ex -> assertThat(ex.getMessage()).isEqualTo("user_not_found"));
		}
		// Charged to the address alone: the phone's own budget from this very
		// address is untouched, so eight real misses still come before a 429.
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("01012345678", GUESSER);
		}
		assertRefused("01012345678", GUESSER);
	}

	@Test
	void theAddressBudgetCountsRefusedPhonesAsMisses() {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_ADDRESS_MISSES; miss++) {
			assertThatThrownBy(() -> guard("➀➁➂", GUESSER, () -> "never"))
					.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(401));
		}
		assertRefused("01012345678", GUESSER);
	}

	@Test
	void aJsonNumberIsKeyedOnTheStringTheLookupBinds() {
		// PHP's (string) cast of this float is "1012345678", which is what
		// login_company binds; String.valueOf gave digits of its own each time.
		assertThat(LegacyLoginThrottle.bindablePhone(1.01234567800001E9)).isEqualTo("1012345678");
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("1012345678", GUESSER);
		}

		assertRefused(1.01234567800001E9, GUESSER);
		assertRefused(1012345678L, GUESSER);
	}

	@Test
	void aPhoneWithNoDigitsIsChargedToTheAddressAloneRatherThanToASharedBucket() {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES + 1; miss++) {
			miss(" - ", GUESSER);
		}

		assertThat(guard("--", GUESSER, () -> "not locked")).isEqualTo("not locked");
		assertThat(guard("", OWNER, () -> "not locked")).isEqualTo("not locked");
	}

	@Test
	void aFailedReleaseAfterASuccessDoesNotUndoTheLogin() {
		this.failDeletes.set(true);

		assertThat(guard("01012345678", OWNER, () -> "token")).isEqualTo("token");
	}

	@Test
	void aFailedReleaseDoesNotReplaceTheLoginsOwnAnswer() {
		this.failDeletes.set(true);

		assertThatThrownBy(() -> guard("01012345678", OWNER, () -> {
			throw new LegacyApiException(403, "company_inactive");
		})).isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));
	}

	private static final java.util.function.Supplier<LegacyApiException> UNKNOWN =
			() -> new LegacyApiException(401, "user_not_found");

	/** A login that ignores the bound phone and answers what {@code login} does. */
	private <T> T guard(Object phone, String address, Supplier<T> login) {
		return this.throttle.guard(phone, address, UNKNOWN, bound -> login.get());
	}

	private void miss(Object phone, String address) {
		Supplier<String> wrongPassword = () -> {
			throw new LegacyApiException(401, "incorrect_password");
		};
		assertThatThrownBy(() -> guard(phone, address, wrongPassword))
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(401));
	}

	private void assertRefused(Object phone, String address) {
		assertThatThrownBy(() -> guard(phone, address, () -> "reached the password"))
				.as("%s from %s", phone, address)
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(429));
	}

	/** A data source whose connections refuse to prepare a DELETE while {@code fail} is set. */
	private static final class FailingDeletes extends DelegatingDataSource {

		private final AtomicBoolean fail;

		FailingDeletes(DriverManagerDataSource target, AtomicBoolean fail) {
			super(target);
			this.fail = fail;
		}

		@Override
		public Connection getConnection() throws SQLException {
			Connection real = super.getConnection();
			return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
					new Class<?>[] {Connection.class}, (proxy, method, args) -> {
						if (this.fail.get() && "prepareStatement".equals(method.getName())
								&& String.valueOf(args[0]).startsWith("DELETE")) {
							throw new SQLException("Deadlock found when trying to get lock", "40001", 1213);
						}
						try {
							return method.invoke(real, args);
						} catch (java.lang.reflect.InvocationTargetException ex) {
							throw ex.getCause();
						}
					});
		}
	}
}
