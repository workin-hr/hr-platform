package com.workin.spike.security;

import com.workin.spike.identity.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CurrentTenant currentTenant;

    public JwtAuthenticationFilter(JwtService jwtService, CurrentTenant currentTenant) {
        this.jwtService = jwtService;
        this.currentTenant = currentTenant;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            try {
                Long companyId = jwtService.extractCompanyId(token);
                currentTenant.setCompanyId(companyId);
                var authentication = new UsernamePasswordAuthenticationToken(
                        companyId, null, List.of(new SimpleGrantedAuthority("ROLE_COMPANY_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                // Invalid/expired token: leave the SecurityContext unauthenticated;
                // SecurityConfig's authorizeHttpRequests then rejects the request.
            }
        }
        chain.doFilter(request, response);
    }
}
