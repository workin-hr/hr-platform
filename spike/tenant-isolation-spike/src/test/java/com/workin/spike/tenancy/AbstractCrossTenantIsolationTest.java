package com.workin.spike.tenancy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared setup for both H2 spike arms -- a real Postgres via
 * Testcontainers (not H2/mocks, so a pass here means "works against real
 * Postgres," not "works against a fake DB, fails against real
 * Postgres"), two registered companies, and the exact cross-tenant
 * attack shape confirmed in hr-legacy#2/#3/#5/#6: company A's
 * authenticated caller attempting to read company B's branch by ID.
 * Concrete subclasses set the active isolation profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
abstract class AbstractCrossTenantIsolationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected Long companyAId;
    protected String companyAToken;
    protected Long companyBId;
    protected String companyBToken;
    protected Long companyBBranchId;

    @BeforeEach
    void registerTwoCompaniesAndOneBranch() throws Exception {
        // The @Container Postgres instance is a shared static field for
        // the whole test class (by design, for speed) -- phone numbers
        // must be unique per test method, not just per test class, or
        // the second test's registration collides with the first's data
        // under companies.phone's real UNIQUE constraint.
        String suffix = String.valueOf(System.nanoTime()).substring(7);
        companyAToken = register("Company A", "+2010000" + suffix);
        companyAId = extractCompanyId(companyAToken);

        companyBToken = register("Company B", "+2020000" + suffix);
        companyBId = extractCompanyId(companyBToken);

        // Company B creates a branch. This is the record company A must
        // never be able to read or write -- the exact shape of
        // hr-legacy's confirmed cross-tenant IDOR findings.
        String createResponse = mockMvc
                .perform(post("/api/branches")
                        .header("Authorization", "Bearer " + companyBToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Company B HQ\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        companyBBranchId = objectMapper.readTree(createResponse).get("id").asLong();
    }

    private String register(String name, String phone) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterPayload(name, phone, "password123"));
        String response = mockMvc
                .perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

    private Long extractCompanyId(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        try {
            return objectMapper.readTree(payload).get("company_id").asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record RegisterPayload(String name, String phone, String password) {
    }
}
