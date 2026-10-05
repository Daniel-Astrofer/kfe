package com.kerosene.kfe.security.workload;

import com.kerosene.common.security.workload.InternalServiceAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkloadIdentityConfigurationTest {

    @Test
    void protectsExactKfeRouteAndLeavesUnrelatedPublicRouteAlone() throws Exception {
        WorkloadIdentityConfiguration configuration = new WorkloadIdentityConfiguration();
        WorkloadIdentityProperties properties = new WorkloadIdentityProperties();
        InternalServiceAuthenticationFilter filter = configuration
                .internalServiceAuthenticationFilter(properties, "credential")
                .getFilter();

        MockHttpServletResponse protectedResponse = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/kfe"),
                protectedResponse,
                new MockFilterChain());

        MockHttpServletResponse unrelatedResponse = new MockHttpServletResponse();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/health/ready"),
                unrelatedResponse,
                new MockFilterChain());

        assertEquals(401, protectedResponse.getStatus());
        assertEquals(200, unrelatedResponse.getStatus());
    }
}
