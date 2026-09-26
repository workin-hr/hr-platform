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

		assertThat(this.throttle.guard("01012345678", OWNER, () -> "signed in")).isEqualTo("signed in");
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
		assertThat(this.throttle.guard("01099999999", "192.0.2.200", () -> "another phone"))
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
	void aJsonNumberIsKeyedOnTheStringTheLookupBinds() {
		// PHP's (string) cast of this float is "1012345678", which is what
		// login_company binds; String.valueOf gave digits of its own each time.
		assertThat(LegacyLoginThrottle.phoneDigits(1.01234567800001E9)).isEqualTo("1012345678");
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES; miss++) {
			miss("1012345678", GUESSER);
		}

		assertRefused(1.01234567800001E9, GUESSER);
		assertRefused(1012345678L, GUESSER);
	}

	@Test
	void aPhoneWithNoDigitsIsChargedToTheAddressAloneRatherThanToASharedBucket() {
		for (int miss = 0; miss < LegacyLoginThrottle.MAX_PAIR_MISSES + 1; miss++) {
			miss("no digits here", GUESSER);
		}

		assertThat(this.throttle.guard("nor here", GUESSER, () -> "not locked")).isEqualTo("not locked");
		assertThat(this.throttle.guard("", OWNER, () -> "not locked")).isEqualTo("not locked");
	}

	@Test
	void aFailedReleaseAfterASuccessDoesNotUndoTheLogin() {
		this.failDeletes.set(true);

		assertThat(this.throttle.guard("01012345678", OWNER, () -> "token")).isEqualTo("token");
	}

	@Test
	void aFailedReleaseDoesNotReplaceTheLoginsOwnAnswer() {
		this.failDeletes.set(true);

		assertThatThrownBy(() -> this.throttle.guard("01012345678", OWNER, () -> {
			throw new LegacyApiException(403, "company_inactive");
		})).isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(403));
	}

	private void miss(Object phone, String address) {
		Supplier<String> wrongPassword = () -> {
			throw new LegacyApiException(401, "incorrect_password");
		};
		assertThatThrownBy(() -> this.throttle.guard(phone, address, wrongPassword))
				.isInstanceOfSatisfying(LegacyApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(401));
	}

	private void assertRefused(Object phone, String address) {
		assertThatThrownBy(() -> this.throttle.guard(phone, address, () -> "reached the password"))
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
