package com.workin.backend.perf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/**
 * Counts the SQL statements one operation actually issues.
 *
 * <p>This codebase's performance problem is round trips, not CPU. The shape has
 * already been found once here -- an enrichment loop driving a per-employee,
 * per-day lookup, "hundreds of avoidable round trips per HTTP request" -- and a
 * profiler shows that as time in JDBC, which is true and useless. The number
 * that matters is how many statements a request makes, and whether that number
 * grows with the size of the result.
 *
 * <p>Deliberately not a library. datasource-proxy and p6spy both do this and
 * more, and neither earns a dependency for {@code prepareStatement} on a
 * counter: this is a JDK proxy over {@link DataSource} and {@link Connection},
 * used only from tests.
 *
 * <p><b>Not the production round-trip count.</b> It counts
 * {@code prepareStatement}/{@code prepareCall} only, so a
 * {@code createStatement().execute(...)} is invisible, and the budget tests
 * wrap a plain {@code DriverManagerDataSource} rather than
 * {@code LegacySessionDataSource}, which issues a {@code SET time_zone} on
 * every checkout (D-099). The figures here are self-consistent run to run,
 * which is what a ratchet needs; they are not a number to quote as production
 * cost.
 *
 * <p>Not thread-safe by design, and it does not need to be: a query budget is
 * asserted around a single operation on the calling thread. Counting across a
 * concurrent load run is what the Prometheus scrape is for.
 */
public final class QueryCounter {

	private final List<String> statements = new ArrayList<>();
	private final AtomicBoolean recording = new AtomicBoolean();

	/** Wraps a DataSource so every statement prepared through it is recorded. */
	public DataSource wrap(DataSource delegate) {
		return (DataSource) Proxy.newProxyInstance(
				DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class },
				(proxy, method, args) -> {
					Object result = invoke(delegate, method, args);
					return result instanceof Connection connection ? wrapConnection(connection) : result;
				});
	}

	private Connection wrapConnection(Connection delegate) {
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class },
				(InvocationHandler) (proxy, method, args) -> {
					if (recording.get() && isStatement(method) && args != null && args.length > 0
							&& args[0] instanceof String sql) {
						statements.add(sql);
					}
					return invoke(delegate, method, args);
				});
	}

	private static boolean isStatement(Method method) {
		String name = method.getName();
		return "prepareStatement".equals(name) || "prepareCall".equals(name);
	}

	private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			throw ex.getCause();
		}
	}

	/** Runs the operation and returns the statements it issued. */
	public List<String> measure(Runnable operation) {
		statements.clear();
		recording.set(true);
		try {
			operation.run();
		} finally {
			recording.set(false);
		}
		return List.copyOf(statements);
	}

	/**
	 * How many times the busiest single statement was issued.
	 *
	 * <p>The total alone hides the thing worth finding: twelve different
	 * statements is a rich page, while one statement twelve times is a loop
	 * that will be one statement two thousand times on real data.
	 */
	public static long busiestRepeat(List<String> issued) {
		return issued.stream()
				.collect(java.util.stream.Collectors.groupingBy(sql -> sql,
						java.util.stream.Collectors.counting()))
				.values().stream().mapToLong(Long::longValue).max().orElse(0);
	}
}
