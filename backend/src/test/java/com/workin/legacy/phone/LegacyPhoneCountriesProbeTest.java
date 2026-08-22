package com.workin.legacy.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * The {@code phone_countries_table_exists()} probe: what it catches, and how
 * long it remembers.
 *
 * <p>Both properties are parity, not implementation taste. PHP catches
 * {@code Throwable} and caches the answer in a function-local {@code static},
 * which lives for one request -- so a transient failure costs that request its
 * table lookup and nothing more. A JVM-wide cache would instead pin the whole
 * process to the fallback definitions after one bad moment.
 */
class LegacyPhoneCountriesProbeTest {

	@Test
	void aFailingProbeChoosesTheFallbackDefinitions() {
		LegacyPhoneCountries countries = new LegacyPhoneCountries(
				new ThrowingDataSource(new java.sql.SQLException("information_schema is unavailable")));

		assertThat(countries.tableExists()).isFalse();
		assertThat(countries.allActive()).isEqualTo(LegacyPhoneCountries.fallbackRows());
		assertThat(countries.dialCodes()).containsExactly("+20", "+966", "+971", "+218");
		assertThat(countries.defaultCode()).isEqualTo("+20");
	}

	@Test
	void aThrowableOutsideRuntimeExceptionAlsoChoosesTheFallback() {
		// catch (Throwable $e) -- not just the exception types a database driver
		// would normally raise. An Error must not escape the probe either.
		LegacyPhoneCountries countries = new LegacyPhoneCountries(
				new ThrowingDataSource(new NoClassDefFoundError("driver class went missing")));

		assertThatCode(countries::tableExists).doesNotThrowAnyException();
		assertThat(countries.tableExists()).isFalse();
		assertThat(countries.find("+966")).isPresent();
		assertThat(new LegacyPhoneNumbers(countries).isValidLocal("+966", "0512345678")).isTrue();
	}

	@Test
	void theProbeRunsOncePerInstanceAndIsThenReused() {
		ThrowingDataSource dataSource = new ThrowingDataSource(new java.sql.SQLException("down"));
		LegacyPhoneCountries countries = new LegacyPhoneCountries(dataSource);

		countries.tableExists();
		countries.tableExists();
		countries.allActive();
		countries.find("+20");
		countries.dialCodes();

		// PHP's static $exists: one probe, then the cached answer for the rest
		// of the request, however many helpers ask.
		assertThat(dataSource.connectionAttempts()).hasValue(1);
	}

	@Test
	void aNewInstanceProbesAgainRatherThanInheritingTheFailure() {
		// The request-scope property, at the level it is implemented: the cache
		// belongs to the instance, so the next request's instance starts over.
		// A singleton would have made the first failure permanent.
		ThrowingDataSource dataSource = new ThrowingDataSource(new java.sql.SQLException("down"));
		LegacyPhoneCountries first = new LegacyPhoneCountries(dataSource);
		first.tableExists();
		first.tableExists();
		assertThat(dataSource.connectionAttempts()).hasValue(1);

		LegacyPhoneCountries second = new LegacyPhoneCountries(dataSource);
		second.tableExists();
		assertThat(dataSource.connectionAttempts()).hasValue(2);
	}

	@Test
	void theComponentIsRequestScopedSoTheCacheCannotOutliveARequest() {
		// The annotation is the mechanism the test above describes; asserting it
		// keeps a later refactor from silently making this a singleton again.
		assertThat(LegacyPhoneCountries.class.getAnnotation(
				org.springframework.web.context.annotation.RequestScope.class)).isNotNull();
	}

	/** A DataSource that never connects, so the probe always fails. */
	private static final class ThrowingDataSource extends AbstractDataSource {

		private final Throwable failure;
		private final AtomicInteger connectionAttempts = new AtomicInteger();

		private ThrowingDataSource(Throwable failure) {
			this.failure = failure;
		}

		AtomicInteger connectionAttempts() {
			return connectionAttempts;
		}

		@Override
		public Connection getConnection() throws java.sql.SQLException {
			connectionAttempts.incrementAndGet();
			if (failure instanceof java.sql.SQLException sqlException) {
				throw sqlException;
			}
			if (failure instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(failure);
		}

		@Override
		public Connection getConnection(String username, String password) throws java.sql.SQLException {
			return getConnection();
		}

	}

}
