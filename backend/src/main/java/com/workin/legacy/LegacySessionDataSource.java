package com.workin.legacy;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.ZoneOffset;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * {@code getDB()}'s session setup, on every connection checkout (D-099).
 *
 * <p>{@code config/pdo.php:21-38} resolves {@code configs.is_daylight_saving}
 * and issues {@code SET time_zone} each time it builds a PDO connection. That
 * decides what {@code NOW()}, {@code CURDATE()} and {@code CURRENT_TIMESTAMP}
 * return, and how every {@code TIMESTAMP} column is converted on read and
 * write. {@link LegacyClock} reproduced only the application-side half of it;
 * this is the other half, and D-083 is closed by the pair.
 *
 * <h2>Why a checkout wrapper and not {@code connectionInitSql}</h2>
 * <p>Hikari runs {@code connectionInitSql} once per <b>physical</b> connection,
 * before the connection is pooled. A pooled connection outlives the request
 * that created it -- often the whole process -- whereas legacy resolves the
 * offset every time it opens PDO. Initialising only physical connections would
 * therefore leave a pool pinned to whatever {@code is_daylight_saving} said at
 * startup, and a flip of that flag would not reach the application until it was
 * restarted. Setting it per checkout is what actually matches PHP, and
 * {@code LegacySessionDataSourceTest} fails a physical-only implementation by
 * flipping the config between two borrows of the same pool.
 *
 * <p>The static init responsibility stays where it was:
 * {@code app.legacy-db.connection-init-sql} still owns non-strict
 * {@code sql_mode}, which is a fixed property of the contract and has no reason
 * to be re-evaluated per request.
 *
 * <h2>The two failures are not the same failure</h2>
 * <p>PHP wraps <b>only</b> the config lookup in
 * {@code catch (Throwable $ignored)}; the {@code SET time_zone} statement sits
 * outside it. So:
 *
 * <ul>
 *   <li>a config lookup that throws -- missing table, missing row, unreadable
 *       column -- is swallowed and the default +02:00 is used;</li>
 *   <li>a {@code SET time_zone} that throws is <b>not</b> swallowed. Connection
 *       acquisition fails rather than silently handing back a session in the
 *       wrong zone, because a wrong zone is a wrong {@code NOW()} and a wrong
 *       {@code NOW()} is wrong attendance data.</li>
 * </ul>
 *
 * <p>If either step fails after the delegate connection was borrowed, that
 * connection is closed before the failure propagates, so a failing pool does
 * not leak.
 */
public class LegacySessionDataSource implements DataSource {

	/** {@code SELECT config_value FROM configs WHERE config_key = ? LIMIT 1}. */
	private static final String CONFIG_QUERY =
			"SELECT config_value FROM configs WHERE config_key = ? LIMIT 1";

	private static final String CONFIG_KEY = "is_daylight_saving";

	private final DataSource delegate;

	public LegacySessionDataSource(DataSource delegate) {
		this.delegate = delegate;
	}

	/** The pool this wraps, for callers that need Hikari's own configuration. */
	public DataSource delegate() {
		return delegate;
	}

	@Override
	public Connection getConnection() throws SQLException {
		return prepare(delegate.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return prepare(delegate.getConnection(username, password));
	}

	/**
	 * Resolve the offset on this same connection and apply it, in PHP's order.
	 *
	 * <p>The lookup deliberately uses the connection being handed out rather
	 * than a second one from the pool: PHP asks the connection it just opened,
	 * and borrowing a second connection here would recurse into this method.
	 */
	private Connection prepare(Connection connection) throws SQLException {
		try {
			ZoneOffset offset = resolveOffset(connection);
			applyTimeZone(connection, offset);
			return connection;
		} catch (SQLException | RuntimeException | Error ex) {
			closeQuietly(connection, ex);
			throw ex;
		}
	}

	/**
	 * The inner {@code try}: everything here is swallowed, and the default
	 * stands.
	 */
	private static ZoneOffset resolveOffset(Connection connection) {
		try (PreparedStatement statement = connection.prepareStatement(CONFIG_QUERY)) {
			statement.setString(1, CONFIG_KEY);
			try (ResultSet rows = statement.executeQuery()) {
				// `if ($value !== false)`: no row leaves the default in place.
				return rows.next() ? LegacyRuntimeOffset.of(rows.getString(1)) : LegacyRuntimeOffset.DEFAULT;
			}
		} catch (Throwable ignored) { // NOPMD - catch (Throwable $ignored), as PHP does
			return LegacyRuntimeOffset.DEFAULT;
		}
	}

	/** Outside the inner catch, so a failure here fails the checkout. */
	private static void applyTimeZone(Connection connection, ZoneOffset offset) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SET time_zone = ?")) {
			statement.setString(1, LegacyRuntimeOffset.sqlLiteral(offset));
			statement.execute();
		}
	}

	private static void closeQuietly(Connection connection, Throwable failure) {
		try {
			connection.close();
		} catch (Throwable closing) { // NOPMD - the original failure is what matters
			failure.addSuppressed(closing);
		}
	}

	// ---------------------------------------------------------------- delegation --

	@Override
	public PrintWriter getLogWriter() throws SQLException {
		return delegate.getLogWriter();
	}

	@Override
	public void setLogWriter(PrintWriter out) throws SQLException {
		delegate.setLogWriter(out);
	}

	@Override
	public void setLoginTimeout(int seconds) throws SQLException {
		delegate.setLoginTimeout(seconds);
	}

	@Override
	public int getLoginTimeout() throws SQLException {
		return delegate.getLoginTimeout();
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		return delegate.getParentLogger();
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return iface.isInstance(this) || delegate.isWrapperFor(iface);
	}

}
