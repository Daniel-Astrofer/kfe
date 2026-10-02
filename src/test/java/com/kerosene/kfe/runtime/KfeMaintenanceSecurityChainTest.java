package com.kerosene.kfe.runtime;

import com.kerosene.kfe.maintenance.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(KfeMaintenanceSecurityChainTest.Config.class)
@TestPropertySource(properties = {
        "kfe.standalone=true", "api.secret.token.secret=synthetic-ci-session-signing-key-at-least-32-bytes",
        "kfe.security.jwt.revocation-check-enabled=false", "kfe.internal.shared-secret=synthetic-internal-secret",
        "kfe.auth.jwt.issuer=synthetic-test", "kfe.auth.jwt.audience=synthetic-kfe"})
class KfeMaintenanceSecurityChainTest {
    private static final String SECRET = "synthetic-ci-session-signing-key-at-least-32-bytes";
    @Autowired private WebApplicationContext context;
    @Autowired private KfeMaintenanceStore store;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(store);
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void actualJwtAuthenticatedChainRejectsFinancialWorkAfterAuthorization() throws Exception {
        mvc.perform(post("/kfe/transactions").header("Authorization", token("USER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("errorCode").value("MAINTENANCE_ADMISSION_REJECTED"));
        verify(store).admit("http.financial-root");
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void unauthorizedAndNonAdminRequestsCannotCreateAdmissions() throws Exception {
        mvc.perform(post("/kfe/transactions")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/kfe/channels/open").header("Authorization", token("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void healthAdminControlsAndPublicLookupRetainTheirActualPolicies() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk());
        mvc.perform(get("/api/public/kfe/payment-requests/test-id")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/kfe/maintenance/status").header("Authorization", token("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/kfe/maintenance/resume").header("Authorization", token("ADMIN")))
                .andExpect(status().isOk());
        verifyNoInteractions(store);
    }

    @Test
    void internalCredentialPrecedesAdmissionAndJwtIsNotASubstitute() throws Exception {
        mvc.perform(post("/internal/kfe/wallet-provisioning/primary").header("Authorization", token("ADMIN")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
        mvc.perform(post("/internal/kfe/wallet-provisioning/primary")
                        .header("X-KFE-Internal-Secret", "synthetic-internal-secret"))
                .andExpect(status().isServiceUnavailable());
        verify(store).admit("http.financial-root");
    }

    @Test
    void filterIsInstalledOnceAndSuccessfulHttpReturnStillCannotCertifyFinanceCompletion() throws Exception {
        var admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
        doReturn(admission).when(store).admit("http.financial-root");
        mvc.perform(post("/kfe/transactions").header("Authorization", token("USER")))
                .andExpect(status().isOk());
        verify(store, times(1)).admit("http.financial-root");
        verify(store).resolve(admission.id(), false);
    }

    private String token(String role) {
        return "Bearer " + Jwts.builder().id("42").issuer("synthetic-test")
                .audience().add("synthetic-kfe").and().expiration(Date.from(Instant.now().plusSeconds(60)))
                .claim("roles", List.of(role))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Configuration
    @EnableWebMvc
    @Import(KfeStandaloneSecurityConfiguration.class)
    static class Config {
        @Bean KfeMaintenanceStore store() { return mock(KfeMaintenanceStore.class); }
        @Bean KfeMaintenanceGuard guard(KfeMaintenanceStore store) { return new KfeMaintenanceService(store); }
        @Bean Handlers handlers() { return new Handlers(); }
    }

    @RestController
    static class Handlers {
        @GetMapping({"/healthz", "/api/public/kfe/payment-requests/test-id", "/api/admin/kfe/maintenance/status"})
        String reads() { return "ok"; }
        @PostMapping({"/kfe/transactions", "/api/admin/kfe/channels/open", "/api/admin/kfe/maintenance/resume",
                "/internal/kfe/wallet-provisioning/primary"})
        String writes() { return "ok"; }
    }
}
