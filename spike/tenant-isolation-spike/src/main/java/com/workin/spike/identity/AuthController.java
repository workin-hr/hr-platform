package com.workin.spike.identity;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal company identity for the H2 spike -- register + login only.
 * Deliberately excludes OTP/WhatsApp verification (orthogonal to what
 * this spike tests) per docs/migration/technical-spike-plan.md's
 * Vertical Slice Scope.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(CompanyRepository companyRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        Company company = new Company(request.name(), request.phone(), passwordEncoder.encode(request.password()));
        Company saved = companyRepository.save(company);
        String token = jwtService.issueAccessToken(saved.getId());
        return ResponseEntity.ok(new AuthResponse(saved.getId(), token));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        Company company = companyRepository.findByPhone(request.phone())
                .filter(c -> passwordEncoder.matches(request.password(), c.getPasswordHash()))
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Invalid phone or password"));
        String token = jwtService.issueAccessToken(company.getId());
        return ResponseEntity.ok(new AuthResponse(company.getId(), token));
    }
}
