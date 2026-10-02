package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.runtime.KfeJwtAuthenticationFilter;
import com.kerosene.kfe.runtime.KfeJwtVerifier;
import io.jsonwebtoken.Claims;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeMaintenanceHttpBarrierTest {
    private static final String INTERNAL_SECRET = "test-only-internal-credential";
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceHttpBarrier barrier = new KfeMaintenanceHttpBarrier(guard, INTERNAL_SECRET);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);

    @BeforeEach
    void activeAdmin() {
        when(store.admit(anyString())).thenReturn(admission);
        authenticate("42", "ROLE_ADMIN");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @CsvSource({
            "POST,/kfe/wallets", "PATCH,/kfe/wallets/123", "POST,/kfe/transactions",
            "GET,/kfe/payment-requests", "HEAD,/kfe/payment-requests/123",
            "POST,/api/admin/kfe/channels/capacity/scan",
            "POST,/api/admin/kfe/reserves/psbts/123/broadcast",
            "GET,/kfe/callbacks/new-provider", "DELETE,/kfe/future-business-route",
            "OPTIONS,/kfe/wallets"
    })
    void allBusinessMethodsAndFutureCallbacksHaveAnUncertainDurableRoot(String method, String path)
            throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        barrier.doFilter(request(method, path), new MockHttpServletResponse(),
                (req, res) -> called.set(true));
        assertThat(called).isTrue();
        verify(store).admit("http.financial-root");
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({"POST,/kfe/wallets", "GET,/kfe/payment-requests",
            "GET,/kfe/callbacks/settlement"})
    void drainingRejectsBeforeEnteringBusinessCode(String method, String path) throws Exception {
        draining();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request(method, path), response, chain);
        assertRejection(response, 503, "MAINTENANCE_ADMISSION_REJECTED");
        assertThat(response.getContentAsString()).doesNotContain("secret", "sql", path);
        verifyNoInteractions(chain);
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void storeFailureIsSanitizedAndCannotEnterTheChain() throws Exception {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("private database details"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request("POST", "/kfe/wallets"), response, chain);
        assertRejection(response, 503, "MAINTENANCE_ADMISSION_REJECTED");
        assertThat(response.getContentAsString()).doesNotContain("private database details");
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anonymousUser", "operator", "0", "-1", "9223372036854775808"})
    void unsupportedIdentitiesCannotObtainAdmission(String identity) throws Exception {
        authenticate(identity, "ROLE_ADMIN");
        assertUnauthenticated();
    }

    @Test
    void absentAnonymousAndUnverifiedAuthenticationCannotObtainAdmission() throws Exception {
        SecurityContextHolder.clearContext();
        assertUnauthenticated();
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous", "42", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        assertUnauthenticated();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("42", "unused"));
        assertUnauthenticated();
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/admin/kfe/maintenance/status", "POST,/api/admin/kfe/maintenance/drain",
            "GET,/api/admin/kfe/reserves/overview", "POST,/api/admin/kfe/channels/open"})
    void adminRolesAreRequiredEvenWhenCalledOutsideTheSecurityChain(String method, String path)
            throws Exception {
        authenticate("42", "ROLE_USER");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request(method, path), response, chain);
        assertRejection(response, 403, "ADMIN_REQUIRED");
        verifyNoInteractions(chain);
        verify(store, never()).admit(anyString());
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/admin/kfe/maintenance/status", "HEAD,/api/admin/kfe/maintenance/status",
            "POST,/api/admin/kfe/maintenance/drain", "POST,/api/admin/kfe/maintenance/resume",
            "GET,/api/admin/kfe/audit/latest", "GET,/api/admin/kfe/audit/events",
            "GET,/api/admin/kfe/audit/transactions/00000000-0000-0000-0000-000000000001",
            "POST,/api/admin/kfe/audit/root", "GET,/api/admin/kfe/reserves/overview",
            "GET,/api/admin/kfe/reserves/psbts",
            "GET,/api/admin/kfe/reserves/psbts/00000000-0000-0000-0000-000000000001",
            "GET,/api/admin/kfe/channels", "GET,/api/admin/kfe/channels/rebalance/jobs",
            "GET,/api/admin/kfe/channels/capacity/jobs", "GET,/api/admin/kfe/channels/capacity/signals"
    })
    void exactAuthenticatedControlRequestsRemainAvailableDuringDrain(String method, String path)
            throws Exception {
        draining();
        AtomicBoolean called = new AtomicBoolean();
        barrier.doFilter(request(method, path), new MockHttpServletResponse(),
                (req, res) -> called.set(true));
        assertThat(called).isTrue();
        verify(store, never()).admit(anyString());
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/admin/kfe/maintenance/resume", "POST,/api/admin/kfe/maintenance/status",
            "GET,/api/admin/kfe/maintenance/status/", "GET,/api/admin/kfe/maintenance/status/callback",
            "GET,/api/admin/kfe/maintenance/status;callback=1", "GET,/api/admin/kfe/maintenance/%73tatus",
            "GET,/api/admin/kfe/reserves/psbts/00000000-0000-0000-0000-000000000001/broadcast"})
    void exemptionsDoNotCoverWrongVerbsAliasesOrSuffixes(String method, String path) throws Exception {
        draining();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        barrier.doFilter(request(method, path), response, chain);
        assertRejection(response, 503, "MAINTENANCE_ADMISSION_REJECTED");
        verifyNoInteractions(chain);
    }

    @Test
    void decodedContainerMappingsAreGuardedAndDoNotExpandControlExemptions() throws Exception {
        draining();
        MockHttpServletRequest request = request("GET", "/%6bfe/payment-requests");
        request.setServletPath("/kfe/payment-requests");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request, response, chain);
        assertRejection(response, 400, "AMBIGUOUS_KFE_PATH");
        verifyNoInteractions(chain);
        request = request("GET", "/api/admin/kfe/maintenance/status");
        request.setServletPath("/api/admin/kfe/maintenance/status/callback");
        MockHttpServletResponse ambiguousControl = new MockHttpServletResponse();
        barrier.doFilter(request, ambiguousControl, chain);
        assertRejection(ambiguousControl, 400, "AMBIGUOUS_KFE_PATH");
        verifyNoInteractions(chain);
        verify(store, never()).admit(anyString());
    }

    @Test
    void contextPathQueryAndExactRouteBoundariesAreHandled() throws Exception {
        draining();
        MockHttpServletRequest control = request("GET", "/cell/api/admin/kfe/maintenance/status");
        control.setContextPath("/cell");
        control.setServletPath("/api/admin/kfe/maintenance/status");
        control.setQueryString("changeId=secret&callback=1");
        AtomicBoolean called = new AtomicBoolean();
        barrier.doFilter(control, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(called).isTrue();
        MockHttpServletRequest business = request("GET", "/cell/kfe/payment-requests");
        business.setContextPath("/cell");
        business.setServletPath("/kfe/payment-requests");
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(business, response, mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(503);
        verify(store, times(1)).admit(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/healthz", "/health/live", "/health/ready", "/health/dependencies",
            "/actuator/health", "/actuator/health/liveness", "/error", "/kfe-other"})
    void nonFinancialRoutesRetainTheirExistingSecurityPolicy(String path) throws Exception {
        SecurityContextHolder.clearContext();
        draining();
        AtomicBoolean called = new AtomicBoolean();
        barrier.doFilter(request("GET", path), new MockHttpServletResponse(),
                (req, res) -> called.set(true));
        assertThat(called).isTrue();
        verify(store, never()).admit(anyString());
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/public/kfe/payment-requests/public-id",
            "GET,/api/public/kfe/payment-requests/lookup",
            "HEAD,/api/public/kfe/payment-requests/public-id",
            "POST,/api/public/kfe/future-callback"})
    void publicRoutesRetainTheirExistingPolicyWithoutHttpAdmissionInActiveAndDrainingModes(
            String method, String path) throws Exception {
        // This is deliberately an outstanding service-level coverage gap. The
        // existing security/handler policy must still authenticate future callbacks.
        FilterChain chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
        barrier.doFilter(request(method, path), new MockHttpServletResponse(), chain);
        authenticate("42", "ROLE_USER");
        barrier.doFilter(request(method, path), new MockHttpServletResponse(), chain);
        draining();
        barrier.doFilter(request(method, path), new MockHttpServletResponse(), chain);
        SecurityContextHolder.clearContext();
        barrier.doFilter(request(method, path), new MockHttpServletResponse(), chain);
        verify(chain, times(4)).doFilter(any(), any());
        verify(store, never()).admit(anyString());
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void internalProvisioningAuthenticatesTheActualHeaderBeforeAdmission() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = request("POST", "/internal/kfe/wallet-provisioning/primary");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse absent = new MockHttpServletResponse();
        barrier.doFilter(request, absent, chain);
        assertRejection(absent, 401, "AUTHENTICATION_REQUIRED");
        request.addHeader("X-KFE-Internal-Secret", "incorrect");
        MockHttpServletResponse invalid = new MockHttpServletResponse();
        barrier.doFilter(request, invalid, chain);
        assertRejection(invalid, 401, "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(chain);
        verify(store, never()).admit(anyString());
        request.removeHeader("X-KFE-Internal-Secret");
        request.addHeader("X-KFE-Internal-Secret", INTERNAL_SECRET);
        barrier.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(eq(request), any());
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/kfe/rail-health/custody-provider",
            "/internal/kfe/rail-health/external-providers", "/internal/kfe/audit-integrity/root"})
    void internallyAuthenticatedReadsStayAvailableButNeverSkipCredentialChecks(String path) throws Exception {
        SecurityContextHolder.clearContext();
        draining();
        MockHttpServletRequest request = request("GET", path);
        FilterChain chain = mock(FilterChain.class);
        barrier.doFilter(request, new MockHttpServletResponse(), chain);
        verifyNoInteractions(chain);
        request.addHeader("X-KFE-Internal-Secret", INTERNAL_SECRET);
        barrier.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(eq(request), any());
        verify(store, never()).admit(anyString());
    }

    @Test
    void internalCredentialCannotBeReplacedByAnAdminJwtAndMissingConfigurationFailsClosed() throws Exception {
        MockHttpServletRequest request = request("POST", "/internal/kfe/wallet-provisioning/primary");
        MockHttpServletResponse missing = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        barrier.doFilter(request, missing, chain);
        assertThat(missing.getStatus()).isEqualTo(401);
        request.addHeader("X-KFE-Internal-Secret", INTERNAL_SECRET);
        MockHttpServletResponse unavailable = new MockHttpServletResponse();
        new KfeMaintenanceHttpBarrier(guard).doFilter(request, unavailable, chain);
        assertRejection(unavailable, 503, "INTERNAL_AUTH_UNAVAILABLE");
        verifyNoInteractions(chain);
        verify(store, never()).admit(anyString());
    }

    @Test
    void authenticatedInternalProvisioningAlsoStopsDuringDrain() throws Exception {
        draining();
        MockHttpServletRequest request = request("POST", "/internal/kfe/wallet-provisioning/primary");
        request.addHeader("X-KFE-Internal-Secret", INTERNAL_SECRET);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(chain);
    }

    @Test
    void synchronousNestedWorkMayFinishAfterDrainButNextRequestCannotReuseItsAdmission() throws Exception {
        MockHttpServletRequest request = request("POST", "/kfe/wallets");
        AtomicBoolean nestedCalled = new AtomicBoolean();
        barrier.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            draining();
            guard.executeMutation("nested.wallet", () -> "finished");
            request.setDispatcherType(DispatcherType.FORWARD);
            request.setRequestURI("/kfe/callbacks/synchronous");
            request.setServletPath("/kfe/callbacks/synchronous");
            barrier.doFilter(request, res, (nestedReq, nestedRes) -> {
                nestedCalled.set(true);
                guard.executeMutation("nested.callback", () -> "finished");
            });
        });
        assertThat(nestedCalled).isTrue();
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertThat(request.getAttributeNames().hasMoreElements()).isFalse();
        FilterChain next = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request, response, next);
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(next);
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void checkedServletFailuresPreserveIdentityAndCleanGuardNesting() throws Exception {
        IOException io = new IOException("transport interrupted");
        assertThatThrownBy(() -> barrier.doFilter(request("POST", "/kfe/wallets"),
                new MockHttpServletResponse(), (req, res) -> { throw io; })).isSameAs(io);
        verify(store).resolve(admission.id(), false);
        ServletException servlet = new ServletException("render failed");
        assertThatThrownBy(() -> barrier.doFilter(request("POST", "/kfe/wallets"),
                new MockHttpServletResponse(), (req, res) -> { throw servlet; })).isSameAs(servlet);
        verify(store, times(2)).resolve(admission.id(), false);
        assertNextRequestIsRejected();
    }

    @Test
    void runtimeAndErrorFailuresAreNotRewrittenAndCleanGuardNesting() throws Exception {
        IllegalStateException runtime = new IllegalStateException("provider uncertainty");
        assertThatThrownBy(() -> barrier.doFilter(request("POST", "/kfe/transactions"),
                new MockHttpServletResponse(), (req, res) -> { throw runtime; })).isSameAs(runtime);
        AssertionError error = new AssertionError("abort");
        assertThatThrownBy(() -> barrier.doFilter(request("POST", "/kfe/transactions"),
                new MockHttpServletResponse(), (req, res) -> { throw error; })).isSameAs(error);
        verify(store, times(2)).resolve(admission.id(), false);
        assertNextRequestIsRejected();
    }

    @Test
    void downstreamMaintenanceExceptionCannotOverwriteAStreamOrMasqueradeAsAdmissionRejection()
            throws Exception {
        KfeMaintenanceGuard.MaintenanceException failure =
                new KfeMaintenanceGuard.MaintenanceException(409, "downstream conflict");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> barrier.doFilter(request("POST", "/kfe/transactions"), response,
                (req, res) -> {
                    response.getWriter().write("partial stream");
                    response.flushBuffer();
                    throw failure;
                })).isSameAs(failure);
        assertThat(response.getContentAsString()).isEqualTo("partial stream");
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 400, 500})
    void statusAndStreamCompletionNeverCertifyFinancialCompletion(int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request("GET", "/kfe/callbacks/stream"), response, (req, res) -> {
            response.setStatus(status);
            response.getOutputStream().write(new byte[]{1, 2, 3});
            response.flushBuffer();
        });
        assertThat(response.getStatus()).isEqualTo(status);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void startingAndCompletingServletAsyncNeverClearsTheDurableUncertainty() throws Exception {
        MockHttpServletRequest request = request("POST", "/kfe/transactions");
        request.setAsyncSupported(true);
        barrier.doFilter(request, new MockHttpServletResponse(), (req, res) -> request.startAsync(req, res));
        assertThat(request.isAsyncStarted()).isTrue();
        verify(store).resolve(admission.id(), false);
        request.getAsyncContext().complete();
        verify(store, never()).resolve(any(), eq(true));
        assertNextRequestIsRejected();
    }

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"ASYNC", "ERROR", "FORWARD", "INCLUDE"})
    void redispatchHasNoSavedBypassTokenAfterRootReturns(DispatcherType dispatcherType) throws Exception {
        MockHttpServletRequest request = request("GET", "/kfe/payment-requests");
        barrier.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        draining();
        request.setDispatcherType(dispatcherType);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(chain);
        verify(store, times(1)).resolve(admission.id(), false);
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void transactionCommitStillCannotClearUnknownCallbacksOrRemoteWork() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        barrier.doFilter(request("POST", "/kfe/transactions"), new MockHttpServletResponse(),
                (req, res) -> guard.executeMutation("nested.submission", () -> "provider acknowledged"));
        verify(store, never()).resolve(any(), anyBoolean());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void resolutionFailureLeavesTheResponseAndNextAdmissionPolicyIntact() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(store).resolve(admission.id(), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request("POST", "/kfe/wallets"), response,
                (req, res) -> response.getWriter().write("financial result"));
        assertThat(response.getContentAsString()).isEqualTo("financial result");
        assertNextRequestIsRejected();
    }

    @Test
    void coverageBlockersRemainNonzeroEvenWithNoOtherObservedWork() throws Exception {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "update", 1),
                Instant.now(), Map.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request("GET", "/api/admin/kfe/maintenance/status"), response, (req, res) -> {
            KfeMaintenanceGuard.Status status = guard.status();
            assertThat(status.safeToUpdate()).isFalse();
            assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                    .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
        });
        verify(store, never()).admit(anyString());
    }

    @Test
    void registrationHookInstallsOnlyAfterUrlAuthorization() {
        HttpSecurity http = mock(HttpSecurity.class);
        when(http.addFilterAfter(any(), eq(AuthorizationFilter.class))).thenReturn(http);
        assertThat(KfeMaintenanceHttpBarrier.register(http, guard, INTERNAL_SECRET)).isSameAs(http);
        verify(http).addFilterAfter(isA(KfeMaintenanceHttpBarrier.class), eq(AuthorizationFilter.class));
        verifyNoMoreInteractions(http);
    }

    @Test
    void realJwtAndSecurityFiltersAuthorizeBeforeAdmittingAndRetainPublicAndInternalChecks() throws Exception {
        SecurityContextHolder.clearContext();
        KfeJwtVerifier verifier = mock(KfeJwtVerifier.class);
        Claims user = mock(Claims.class);
        when(user.getId()).thenReturn("42");
        when(verifier.verify("valid-user")).thenReturn(user);
        when(verifier.roles(user)).thenReturn(List.of("USER"));
        Claims admin = mock(Claims.class);
        when(admin.getId()).thenReturn("43");
        when(verifier.verify("valid-admin")).thenReturn(admin);
        when(verifier.roles(admin)).thenReturn(List.of("ADMIN"));
        when(verifier.verify("invalid")).thenThrow(new IllegalArgumentException("invalid token"));
        AuthorizationFilter authorization = new AuthorizationFilter((authentication, request) -> {
            String path = request.getRequestURI();
            if (path.startsWith("/api/public/kfe/") || path.startsWith("/internal/kfe/")
                    || path.equals("/healthz")) {
                return new AuthorizationDecision(true);
            }
            boolean authenticated = authentication.get().isAuthenticated()
                    && !(authentication.get() instanceof AnonymousAuthenticationToken);
            boolean allowed = path.startsWith("/api/admin/kfe/")
                    ? authenticated && authentication.get().getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN")) : authenticated;
            return new AuthorizationDecision(allowed);
        });
        // The real JWT and authorization filters surround the actual barrier. The
        // external credential verifier and durable store are the mocked boundaries.
        FilterChainProxy pipeline = new FilterChainProxy(new DefaultSecurityFilterChain(request -> true,
                new SecurityContextHolderFilter(new NullSecurityContextRepository()),
                new KfeJwtAuthenticationFilter(verifier, true),
                new AnonymousAuthenticationFilter("test-anonymous"),
                new ExceptionTranslationFilter(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)),
                authorization, barrier));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse absent = new MockHttpServletResponse();
        pipeline.doFilter(request("POST", "/kfe/wallets"), absent, chain);
        assertThat(absent.getStatus()).isEqualTo(401);
        MockHttpServletRequest invalid = request("POST", "/kfe/wallets");
        invalid.addHeader("Authorization", "Bearer invalid");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        pipeline.doFilter(invalid, invalidResponse, chain);
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
        MockHttpServletRequest forbidden = request("POST", "/api/admin/kfe/channels/open");
        forbidden.addHeader("Authorization", "Bearer valid-user");
        MockHttpServletResponse forbiddenResponse = new MockHttpServletResponse();
        pipeline.doFilter(forbidden, forbiddenResponse, chain);
        assertThat(forbiddenResponse.getStatus()).isEqualTo(403);
        MockHttpServletResponse publicResponse = new MockHttpServletResponse();
        pipeline.doFilter(request("GET", "/api/public/kfe/payment-requests/public-id"), publicResponse, chain);
        assertThat(publicResponse.getStatus()).isEqualTo(200);
        MockHttpServletResponse internalResponse = new MockHttpServletResponse();
        pipeline.doFilter(request("POST", "/internal/kfe/wallet-provisioning/primary"), internalResponse, chain);
        assertRejection(internalResponse, 401, "AUTHENTICATION_REQUIRED");
        verify(chain, times(1)).doFilter(any(), any());
        verify(store, never()).admit(anyString());

        MockHttpServletRequest admitted = request("POST", "/kfe/wallets");
        admitted.addHeader("Authorization", "Bearer valid-user");
        pipeline.doFilter(admitted, new MockHttpServletResponse(), chain);
        verify(store).admit("http.financial-root");
        verify(store).resolve(admission.id(), false);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        draining();
        MockHttpServletRequest control = request("GET", "/api/admin/kfe/maintenance/status");
        control.addHeader("Authorization", "Bearer valid-admin");
        pipeline.doFilter(control, new MockHttpServletResponse(), chain);
        verify(chain, times(3)).doFilter(any(), any());
        MockHttpServletResponse drained = new MockHttpServletResponse();
        pipeline.doFilter(admitted, drained, chain);
        assertRejection(drained, 503, "MAINTENANCE_ADMISSION_REJECTED");
        verify(chain, times(3)).doFilter(any(), any());
    }

    private void assertUnauthenticated() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request("GET", "/kfe/payment-requests"), response, chain);
        assertRejection(response, 401, "AUTHENTICATION_REQUIRED");
        verifyNoInteractions(chain);
        verify(store, never()).admit(anyString());
    }

    private void assertNextRequestIsRejected() throws Exception {
        draining();
        FilterChain next = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        barrier.doFilter(request("POST", "/kfe/wallets"), response, next);
        assertThat(response.getStatus()).isEqualTo(503);
        verifyNoInteractions(next);
    }

    private void draining() {
        when(store.admit(anyString())).thenThrow(
                new KfeMaintenanceGuard.MaintenanceException(503, "secret sql \"details\"\n"));
    }

    private static void authenticate(String id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                id, null, AuthorityUtils.createAuthorityList(role)));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private static void assertRejection(MockHttpServletResponse response, int status, String code)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var envelope = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(envelope.get("schema").asText()).isEqualTo(KfeMaintenanceGuard.SCHEMA);
        assertThat(envelope.get("errorCode").asText()).isEqualTo(code);
    }
}
