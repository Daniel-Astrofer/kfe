package com.kerosene.kfe.controller;

import com.kerosene.kfe.exception.KfeExceptionHandler;
import com.kerosene.kfe.maintenance.KfeMaintenanceAdmissionQuery;
import com.kerosene.kfe.maintenance.KfeMaintenanceAdmissionQuery.Entry;
import com.kerosene.kfe.maintenance.KfeMaintenanceAdmissionQuery.Page;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KfeMaintenanceAdmissionsAdminControllerTest {
    private static final String PATH = "/api/admin/kfe/maintenance/admissions";
    private static final String SQL_DETAILS = "SELECT secret_token FROM kfe_maintenance_admission; password=secret";
    private final KfeMaintenanceAdmissionQuery query = mock(KfeMaintenanceAdmissionQuery.class);
    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("42", "unused", "ROLE_ADMIN");
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new KfeMaintenanceAdmissionsAdminController(query))
                .setControllerAdvice(new KfeExceptionHandler(), new KfeMaintenanceAdminController.MaintenanceErrors())
                .build();
    }

    @Test
    void declaresAdminMethodSecurity() {
        assertThat(KfeMaintenanceAdmissionsAdminController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
    }

    @Test
    void actualBarrierAndMvcAllowOnlyAuthenticatedReadControlWithoutAdmission() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(new KfeMaintenanceAdmissionsAdminController(query))
                .addFilters(new com.kerosene.kfe.maintenance.KfeMaintenanceHttpBarrier(KfeMaintenanceGuard.unavailable()))
                .setControllerAdvice(new KfeExceptionHandler(), new KfeMaintenanceAdminController.MaintenanceErrors())
                .build();
        when(query.page(50, null)).thenReturn(page());
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
            verifyNoInteractions(query);
            context.setAuthentication(new TestingAuthenticationToken("42", "unused", "ROLE_USER"));
            mvc.perform(get(PATH).principal(admin)).andExpect(status().isForbidden());
            verifyNoInteractions(query);
            context.setAuthentication(admin);
            mvc.perform(get(PATH).principal(admin)).andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            mvc.perform(head(PATH).principal(admin)).andExpect(status().isOk())
                    .andExpect(content().string(""));
            mvc.perform(post(PATH).principal(admin)).andExpect(status().isServiceUnavailable());
            verify(query, times(2)).page(50, null);
            verifyNoMoreInteractions(query);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test
    void defaultsLimitAndReturnsDiagnosticPageWithoutCaching() throws Exception {
        when(query.page(50, null)).thenReturn(page());
        mvc.perform(get(PATH).principal(admin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("schema").value(KfeMaintenanceAdmissionQuery.SCHEMA))
                .andExpect(jsonPath("observedAt").exists())
                .andExpect(jsonPath("mode").value("DRAINING"))
                .andExpect(jsonPath("changeId").value("update-1"))
                .andExpect(jsonPath("revision").value(7))
                .andExpect(jsonPath("diagnosticOnly").value(true))
                .andExpect(jsonPath("entries.length()").value(1))
                .andExpect(jsonPath("entries[0].id").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("entries[0].operation").value("execution.submit"))
                .andExpect(jsonPath("entries[0].admittedRevision").value(6))
                .andExpect(jsonPath("entries[0].state").value("UNCERTAIN"))
                .andExpect(jsonPath("entries[0].admittedAt").exists())
                .andExpect(jsonPath("entries[0].parentAdmissionId").value("00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("nextCursor").value("opaque-next"));
        verify(query).page(50, null);
        verifyNoMoreInteractions(query);
    }

    @Test
    void forwardsLimitAndOpaqueCursorUnchanged() throws Exception {
        when(query.page(100, "opaque+/=cursor")).thenReturn(page());
        mvc.perform(get(PATH).principal(admin).param("limit", "100").param("cursor", "opaque+/=cursor"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        verify(query).page(100, "opaque+/=cursor");
    }

    @Test
    void headQueriesTheSamePageAndReturnsNoBody() throws Exception {
        when(query.page(50, null)).thenReturn(page());
        mvc.perform(head(PATH).principal(admin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
        verify(query).page(50, null);
    }

    @Test
    void missingAuthenticationAndNonAdminsCannotQueryForGetOrHead() throws Exception {
        Authentication user = new TestingAuthenticationToken("42", "unused", "ROLE_USER");
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(head(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).principal(user)).andExpect(status().isForbidden());
        mvc.perform(head(PATH).principal(user)).andExpect(status().isForbidden());
        verifyNoInteractions(query);
    }

    @Test
    void invalidIdentitiesCannotQueryEvenWithAdminAuthority() throws Exception {
        for (String name : List.of("0", "-1", "not-an-id", "9223372036854775808", "anonymousUser")) {
            Authentication invalid = new TestingAuthenticationToken(name, "unused", "ROLE_ADMIN");
            mvc.perform(get(PATH).principal(invalid)).andExpect(status().isUnauthorized());
            mvc.perform(head(PATH).principal(invalid)).andExpect(status().isUnauthorized());
        }
        admin.setAuthenticated(false);
        mvc.perform(get(PATH).principal(admin)).andExpect(status().isUnauthorized());
        mvc.perform(head(PATH).principal(admin)).andExpect(status().isUnauthorized());
        Authentication anonymous = new AnonymousAuthenticationToken("test-key", "42",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        mvc.perform(get(PATH).principal(anonymous)).andExpect(status().isUnauthorized());
        mvc.perform(head(PATH).principal(anonymous)).andExpect(status().isUnauthorized());
        verifyNoInteractions(query);
    }

    @Test
    void queryRejectsInvalidLimitsWithFixedErrorAndNoDetails() throws Exception {
        for (int limit : List.of(-1, 0, 101)) {
            when(query.page(limit, null)).thenThrow(new IllegalArgumentException(SQL_DETAILS));
            mvc.perform(get(PATH).principal(admin).param("limit", Integer.toString(limit)))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(content().json("{\"schema\":\"kerosene.kfe-maintenance-admissions/v1\","
                            + "\"error\":\"Invalid maintenance admissions request.\"}", true));
            verify(query).page(limit, null);
        }
    }

    @Test
    void invalidCursorHasFixedErrorWithoutSqlDetails() throws Exception {
        when(query.page(50, "invalid")).thenThrow(new IllegalArgumentException(SQL_DETAILS));
        mvc.perform(get(PATH).principal(admin).param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("{\"schema\":\"kerosene.kfe-maintenance-admissions/v1\","
                        + "\"error\":\"Invalid maintenance admissions request.\"}", true));
        verify(query).page(50, "invalid");
    }

    @Test
    void malformedLimitHasFixedErrorBeforeQuery() throws Exception {
        for (String limit : List.of("not-a-number", "2147483648")) {
            mvc.perform(get(PATH).principal(admin).param("limit", limit))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(content().json("{\"schema\":\"kerosene.kfe-maintenance-admissions/v1\","
                            + "\"error\":\"Invalid maintenance admissions request.\"}", true));
        }
        verifyNoInteractions(query);
    }

    @Test
    void storeFailureAlwaysReturnsFixed503WithoutSqlDetails() throws Exception {
        // The diagnostic surface never forwards the exception's status or message.
        when(query.page(50, null)).thenThrow(new KfeMaintenanceGuard.MaintenanceException(409, SQL_DETAILS));
        mvc.perform(get(PATH).principal(admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("{\"schema\":\"kerosene.kfe-maintenance-admissions/v1\","
                        + "\"error\":\"Maintenance admissions are unavailable.\"}", true));
        mvc.perform(head(PATH).principal(admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
    }

    @Test
    void headAlsoReturns400ForQueryValidationFailureWithoutBody() throws Exception {
        when(query.page(0, null)).thenThrow(new IllegalArgumentException(SQL_DETAILS));
        mvc.perform(head(PATH).principal(admin).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""));
    }

    @Test
    void arbitraryRuntimeFailureIsNotMisclassifiedAsBadRequest() {
        RuntimeException unexpected = new RuntimeException("unexpected query failure");
        when(query.page(50, null)).thenThrow(unexpected);
        assertThatThrownBy(() -> mvc.perform(get(PATH).principal(admin))).hasRootCause(unexpected);
        verify(query).page(50, null);
    }

    @Test
    void mutationMethodsAreNotMapped() throws Exception {
        mvc.perform(post(PATH).principal(admin)).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete(PATH).principal(admin)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(query);
    }

    private Page page() {
        Instant observedAt = Instant.parse("2026-10-03T12:00:00Z");
        Entry entry = new Entry(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "execution.submit", 6, "UNCERTAIN", observedAt.minusSeconds(60),
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        return new Page(KfeMaintenanceAdmissionQuery.SCHEMA, observedAt, KfeMaintenanceGuard.Mode.DRAINING,
                "update-1", 7, true, List.of(entry), "opaque-next");
    }
}
