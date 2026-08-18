package com.workin.backend.tenancy;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/**
 * Resolves which tenant(s) an already-authenticated identity belongs to
 * -- the one legitimate operation that must run <em>before</em> a tenant
 * context exists, since login/registration cannot know
 * {@code app.current_company_id} in advance
 * (docs/adr/ADR-0010-authorization-model.md Dimension 1: "an identity
 * may have memberships in multiple tenants").
 *
 * <p>This deliberately queries through the migration (superuser)
 * DataSource, not the RLS-scoped {@code app_runtime} one -- RLS filters
 * strictly by {@code company_id}, so an unscoped-by-design,
 * identity-scoped lookup (find every membership belonging to <em>this
 * exact, already-authenticated identity</em>) can never succeed through
 * it. This is safe because the caller has already been authenticated as
 * that specific identity by the time this runs, and the query is always
 * parameterized by that identity's own id -- it can never be used to
 * enumerate another identity's memberships. It is not a general escape
 * hatch: every other tenant-data query in this application goes through
 * the RLS-scoped DataSource. A longer-term cleaner alternative (a
 * SECURITY DEFINER function scoped narrowly to this one lookup) is
 * worth revisiting once more of the authorization model is built out.
 */
@Profile("!phase1-mysql")
@Service
public class IdentityMembershipIndexService {

	private final JdbcTemplate jdbcTemplate;

	public IdentityMembershipIndexService(@Qualifier("flywayDataSource") DataSource flywayDataSource) {
		this.jdbcTemplate = new JdbcTemplate(flywayDataSource);
	}

	public List<MembershipSummary> findMembershipsForIdentity(Long identityId) {
		return jdbcTemplate.query(
				"SELECT id, company_id FROM tenant_memberships "
						+ "WHERE identity_id = ? AND status = 'ACTIVE' "
						+ "ORDER BY company_id, id",
				(rs, rowNum) -> new MembershipSummary(rs.getLong("id"), rs.getLong("company_id")),
				identityId);
	}

	public record MembershipSummary(Long membershipId, Long companyId) {
	}

}
