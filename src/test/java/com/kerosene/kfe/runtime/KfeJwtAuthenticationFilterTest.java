package com.kerosene.kfe.runtime;

import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KfeJwtAuthenticationFilterTest {
    private final KfeJwtVerifier verifier = mock(KfeJwtVerifier.class);
    private final KfeJwtAuthenticationFilter filter = new KfeJwtAuthenticationFilter(verifier, true);

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void invalidSessionRejectsBeforeBusinessWork() throws Exception {
        when(verifier.verify("synthetic-token")).thenThrow(new IllegalArgumentException("private verifier detail"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_SESSION").doesNotContain("private verifier detail");
        verifyNoInteractions(chain);
    }

    @Test
    void financialRuntimeFailureNeverBecomesAnInvalidSessionOrReplacesCommittedResponse() throws Exception {
        validSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException("financial failure");
        assertThatThrownBy(() -> filter.doFilter(request(), response, (request, output) -> {
            response.getWriter().write("business-response");
            response.flushBuffer();
            throw failure;
        })).isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("business-response");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("42");
    }

    @Test
    void checkedBusinessFailureIsAlsoPreserved() throws Exception {
        validSession();
        IOException failure = new IOException("connection closed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request(), response, (request, output) -> { throw failure; }))
                .isSameAs(failure);
        assertThat(response.getContentAsString()).isEmpty();
    }

    private void validSession() {
        var claims = Jwts.claims().id("42").build();
        when(verifier.verify("synthetic-token")).thenReturn(claims);
        when(verifier.roles(claims)).thenReturn(List.of("ADMIN"));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/kfe/transactions");
        request.addHeader("Authorization", "Bearer synthetic-token");
        return request;
    }
}
