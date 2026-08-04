package com.workin.spike.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

/**
 * Holds the authenticated caller's company_id for the duration of one
 * HTTP request -- populated by JwtAuthenticationFilter after verifying
 * the access token, read by both RlsBranchService and GuardBranchService.
 * Request-scoped (not a ThreadLocal managed by hand) so it cannot leak
 * across requests on a pooled thread.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CurrentTenant {

    private Long companyId;

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}
