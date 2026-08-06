package com.workin.backend.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ADR-0002's Decision (condition 1) treats the non-superuser application
 * database role as a hard architectural constraint, "ideally enforced by
 * a startup-time check that fails loudly if the application's runtime
 * DataSource ever connects as a superuser." This is that check
 * (docs/migration/consolidated-task-matrix.md F-22).
 *
 * <p>Why this matters: the H2 spike's central finding is that Postgres
 * RLS is silently, completely bypassed for a superuser connection --
 * with no error, no warning, and every manual test appearing to "work"
 * (because a developer's own session is very likely a superuser too).
 * Failing startup here converts a silent, dangerous misconfiguration
 * into an immediate, loud one.
 *
 * <p>This check only covers {@code applicationDataSource} (the
 * {@code @Primary} bean). It intentionally does not, and cannot, cover
 * {@code flywayDataSource}: that DataSource is superuser by design (it
 * runs migrations/DDL), and
 * {@link com.workin.backend.tenancy.IdentityMembershipIndexService} also
 * deliberately queries through it at runtime for the one lookup RLS
 * cannot serve. That is a documented, narrow exception to "the runtime
 * never touches a superuser connection" -- see that class's Javadoc and
 * its regression test,
 * {@code IdentityMembershipIndexServiceTest}, for the safety invariant
 * this check cannot enforce for it.
 */
@Component
public class SuperuserStartupCheck implements ApplicationRunner {

	private final DataSource applicationDataSource;

	public SuperuserStartupCheck(DataSource applicationDataSource) {
		this.applicationDataSource = applicationDataSource;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		try (Connection connection = applicationDataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT usesuper FROM pg_user WHERE usename = current_user")) {
			if (resultSet.next() && resultSet.getBoolean("usesuper")) {
				throw new IllegalStateException(
						"Application runtime DataSource is connected as a Postgres superuser ("
								+ "current_user reports usesuper = true). Postgres Row-Level "
								+ "Security is always bypassed for superusers regardless of "
								+ "FORCE ROW LEVEL SECURITY -- every tenant-isolation guarantee "
								+ "this application depends on would silently provide zero "
								+ "protection. Refusing to start. See "
								+ "docs/adr/ADR-0002-modular-monolith-baseline.md Decision, "
								+ "condition 1.");
			}
		}
	}

}
