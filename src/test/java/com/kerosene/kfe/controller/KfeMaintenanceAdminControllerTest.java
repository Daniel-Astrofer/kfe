package com.kerosene.kfe.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.kfe.exception.KfeExceptionHandler;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KfeMaintenanceAdminControllerTest {
    private final KfeMaintenanceGuard guard = mock(KfeMaintenanceGuard.class);
    private final KfeMaintenanceAdminController controller = new KfeMaintenanceAdminController(guard);
    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("42", "unused", "ROLE_ADMIN");
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new KfeExceptionHandler(), new KfeMaintenanceAdminController.MaintenanceErrors())
                .build();
    }

    @Test
    void derivesOperatorFromAuthenticationAndReturnsFrozenStatus() throws Exception {
        KfeMaintenanceGuard.Command command = new KfeMaintenanceGuard.Command("update-1", "upgrade", 0);
        when(guard.requestDrain(command, 42)).thenReturn(new KfeMaintenanceGuard.Status(
                KfeMaintenanceGuard.SCHEMA, KfeMaintenanceGuard.Mode.DRAINING, "update-1", 1,
                Instant.parse("2026-10-01T12:00:00Z"), false, Map.of("outboxInFlight", 1L)));
        mvc.perform(post("/api/admin/kfe/maintenance/drain").principal(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeId\":\"update-1\",\"reason\":\"upgrade\",\"expectedRevision\":0,\"operatorId\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("schema").value(KfeMaintenanceGuard.SCHEMA))
                .andExpect(jsonPath("mode").value("DRAINING"))
                .andExpect(jsonPath("changeId").value("update-1"))
                .andExpect(jsonPath("revision").value(1))
                .andExpect(jsonPath("observedAt").exists())
                .andExpect(jsonPath("safeToUpdate").value(false))
                .andExpect(jsonPath("blockers.outboxInFlight").value(1));
        verify(guard).requestDrain(command, 42);
    }

    @Test
    void missingAuthenticationAndNonAdminCannotReadOrChangeState() throws Exception {
        mvc.perform(get("/api/admin/kfe/maintenance/status")).andExpect(status().isUnauthorized());
        TestingAuthenticationToken user = new TestingAuthenticationToken("42", "unused", "ROLE_USER");
        mvc.perform(get("/api/admin/kfe/maintenance/status").principal(user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/kfe/maintenance/drain").principal(user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeId\":\"update\",\"reason\":\"upgrade\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(guard);
    }

    @Test
    void missingRevisionAndInvalidCommandAreBadRequests() throws Exception {
        mvc.perform(post("/api/admin/kfe/maintenance/drain").principal(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"changeId\":\"update\",\"reason\":\"upgrade\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/kfe/maintenance/drain").principal(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeId\":\"update\",\"reason\":\"\",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(guard);
    }

    @Test
    void staleRevisionReturnsConflict() throws Exception {
        when(guard.resume(any(), eq(42L))).thenThrow(new KfeMaintenanceGuard.MaintenanceException(409, "stale revision"));
        mvc.perform(post("/api/admin/kfe/maintenance/resume").principal(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeId\":\"update\",\"reason\":\"resume\",\"expectedRevision\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unauthenticatedTokenIsRejectedEvenWithAdminAuthority() {
        admin.setAuthenticated(false);
        assertThatThrownBy(() -> controller.status(admin)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(guard);
    }
}
