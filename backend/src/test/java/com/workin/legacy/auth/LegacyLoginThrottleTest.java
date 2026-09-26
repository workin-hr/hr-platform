package com.workin.legacy.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.workin.legacy.LegacyMariaDb;
import com.workin.legacy.phone.LegacyPhoneCountries;
import com.workin.legacy.phone.LegacyPhoneNumbers;
import com.workin.legacy.phone.PhoneLookup;
import com.workin.legacy.wire.LegacyApiException;

/**
 * {@link LegacyLoginThrottle} against real MariaDB, without the HTTP layer:
 * here the client address and the raw phone value are whatever the test says,
 * which a request from the test JVM cannot vary (D-289). The phone budgets are
 * keyed on the canonical E.164 number (D-291), so every spelling of one number
 * is one budget.
 */
class LegacyLoginThrottleTest {

	private static final LegacyMariaDb.Handle MARIADB = LegacyMariaDb.freshDatabase();

	private static final String GUESSER = "198.51.100.7";

	private static final String OWNER = "203.0.113.20";

	/** When set, every DELETE the throttle prepares fails as the database would. */
	private final AtomicBoolean failDeletes = new AtomicBoolean();

	private final DriverManagerDataSource dataSource =
			new DriverManagerDataSource(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());

	private final LegacyLoginThrottle throttle = new LegacyLoginThrottle(
			new FailingDeletes(this.dataSource, this.failDeletes),
			new LegacyPhoneNumbers(new LegacyPhoneCountries(this.dataSource)));

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
	void missesSpreadAcrossSpellingsOfOneNumberSpendOneBudget() {
		// The bypass D-291 closes: keyed on the digits the client typed, each
		// of these was a budget of its own, so eight spellings bought eight
		// times the guesses. Keyed on E.164 they are one number.
		List<String> spellings = List.of("01012345678", "+201012345678", "1012345678", "0020 10 1234 5678",
				"(010) 1234-5678", "201012345678", "٠١٠١٢٣٤٥٦٧٨", "010.1234.5678");
		assertThat(spellings).hasSize(LegacyLoginThrottle.MAX_PAIR_MISSES);
		for (String spelling : spellings) {
			miss(spelling, GUESSER);
		}

		for (String spelling : spellings) {
			assertRefused(spelling, GUESSER);
		}
		assertRefused("+20 10 1234 5678", GUESSER);
	}

	@Test
	void thePhoneWideCeilingIsOneBudgetAcrossSpellingsAndAddresses() {
		List<String> spellings = List.of("01012345678", "+201012345678", "1012345678", "00201012345678", "201012345678");
		int addresses = LegacyLoginThrottle.MAX_PHONE_MISSES / LegacyLoginThrottle.MAX_PAIR_MISSES;
		for (int address = 0; address < addresses; address++) {
			for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
				miss(spellings.get((address + miss) % spellings.size()), "192.0.2." + address);
			}
		}

		assertRefused("(010) 1234-5678", "192.0.2.200");
		assertRefused("+20 10 1234 5678", OWNER);
	}

	@Test
	void aNationalNumberIsChargedUnderEveryNumberItCanReach() {
		// 0501234567 reads as a Saudi mobile (and an Egyptian landline, and an
		// Emirati mobile), and the lookup can reach the Saudi account stored
		// that way -- so a miss typed nationally is a miss against +966 too.
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("+966 50 123 4567", GUESSER);
		}

		assertRefused("0501234567", GUESSER);
		assertRefused("501234567", GUESSER);
		// The Egyptian reading alone is not the Saudi number's budget.
		assertThat(guard("+20 50 1234567", GUESSER, () -> "the landline")).isEqualTo("the landline");
	}

	@Test
	void theLookupIsGivenTheCanonicalNumberTheKeyIsMadeFrom() {
		PhoneLookup lookup = this.throttle.guard("۰۱۰-۱۲۳۴-۵۶۷۸", OWNER, UNKNOWN, phone -> phone);

		assertThat(lookup.e164s()).containsExactly("+201012345678");
		assertThat(lookup.clause("phone").binds())
				.containsExactly("01012345678", "1012345678", "201012345678", "+201012345678", "00201012345678");
	}

	@Test
	void inputThatIsNotAPhoneNumberNeverReachesTheLookup() {
		// Circled, dingbat and Hangzhou digits compare equal to ASCII digits in
		// utf8mb4_unicode_ci; letters, an extension and an invalid number are
		// not a phone at all. All refused as an unknown phone, none keyed.
		for (String phone : new String[] {"⓿➀⓿➀➁➂➃➄➅➆➇", "〇〡〇〡〢〣〤〥〦〧〨", "0101234567➇", "01012345678x",
				"0+1012345678", "+-+2010", "01012345678 ext 9", "01312345678", "010ABCDEFGH"}) {
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
	void aJsonNumberIsKeyedOnTheNumberItIs() {
		// PHP's (string) cast of this float is "1012345678": the Egyptian
		// mobile without its trunk zero, which is the same number as 010...
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("1012345678", GUESSER);
		}

		assertRefused(1.01234567800001E9, GUESSER);
		assertRefused(1012345678L, GUESSER);
		assertRefused("01012345678", GUESSER);
	}

	@Test
	void aPhoneWithNoDigitsIsChargedToTheAddressAloneRatherThanToASharedBucket() {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES + 1; miss++) {
			assertThatThrownBy(() -> guard(" - ", GUESSER, () -> "never"))
					.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(401));
		}

		// Nothing was keyed on the phone, so a real number from the same
		// address still has its whole budget.
		assertThat(guard("01012345678", GUESSER, () -> "not locked")).isEqualTo("not locked");
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
