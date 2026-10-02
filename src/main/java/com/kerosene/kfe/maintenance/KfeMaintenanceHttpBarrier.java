package com.kerosene.kfe.maintenance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Partial HTTP root admission, installed explicitly inside each applicable security chain.
 * Never register this filter as a servlet filter or component. Authentication and
 * URL authorization must precede it; controller/method authorization still applies.
 * HTTP completion cannot certify remote, queued, after-commit or asynchronous work.
 * Public KFE roots retain their existing policy pending service-level admission.
 */
public final class KfeMaintenanceHttpBarrier extends GenericFilterBean {
    private static final String INTERNAL_ROOT = "/internal/kfe";
    private static final String ADMIN_ROOT = "/api/admin/kfe";
    private static final String MAINTENANCE_ROOT = ADMIN_ROOT + "/maintenance";
    private static final Set<String> READ_CONTROLS = Set.of(
            MAINTENANCE_ROOT + "/status",
            ADMIN_ROOT + "/audit/latest",
            ADMIN_ROOT + "/audit/events",
            ADMIN_ROOT + "/reserves/overview",
            ADMIN_ROOT + "/reserves/psbts",
            ADMIN_ROOT + "/channels",
            ADMIN_ROOT + "/channels/rebalance/jobs",
            ADMIN_ROOT + "/channels/capacity/jobs",
            ADMIN_ROOT + "/channels/capacity/signals",
            INTERNAL_ROOT + "/audit-integrity/root",
            INTERNAL_ROOT + "/rail-health/custody-provider",
            INTERNAL_ROOT + "/rail-health/external-providers");
    private static final String UUID_PATH =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern READ_CONTROL_DETAIL = Pattern.compile(
            "(?:/api/admin/kfe/audit/transactions/|/api/admin/kfe/reserves/psbts/)" + UUID_PATH);

    private final KfeMaintenanceGuard guard;
    private final byte[] internalCredential;

    /** Internal requests fail closed unless the coordinator supplies the actual shared secret. */
    public KfeMaintenanceHttpBarrier(KfeMaintenanceGuard guard) {
        this(guard, "");
    }

    public KfeMaintenanceHttpBarrier(KfeMaintenanceGuard guard, String internalSharedSecret) {
        this.guard = Objects.requireNonNull(guard);
        this.internalCredential = internalSharedSecret == null || internalSharedSecret.isBlank()
                ? new byte[0] : internalSharedSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Coordinator hook: call before http.build(), once in every chain serving KFE.
     * Use the same kfe.internal.shared-secret value as the existing internal controllers.
     * Creating the instance here avoids Boot's automatic servlet-filter registration.
     */
    public static HttpSecurity register(HttpSecurity http, KfeMaintenanceGuard guard,
                                        String internalSharedSecret) {
        return http.addFilterAfter(new KfeMaintenanceHttpBarrier(guard, internalSharedSecret),
                AuthorizationFilter.class);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            throw new ServletException("KFE maintenance barrier requires HTTP.");
        }
        String uri = httpRequest.getRequestURI();
        String context = httpRequest.getContextPath();
        String path = uri.startsWith(context) ? uri.substring(context.length()) : uri;
        String mappedPath = httpRequest.getServletPath()
                + (httpRequest.getPathInfo() == null ? "" : httpRequest.getPathInfo());
        // Include the container's decoded mapping as well as the raw URI. Exemptions
        // require agreement, so encoded/matrix/alternate paths cannot widen them.
        if (!financialPath(path) && !financialPath(mappedPath)) {
            chain.doFilter(request, response);
            return;
        }
        if (!mappedPath.isEmpty() && !mappedPath.equals(path)) {
            reject(httpResponse, 400, "AMBIGUOUS_KFE_PATH", "KFE request path is ambiguous.");
            return;
        }
        boolean internal = beneath(path, INTERNAL_ROOT) || beneath(mappedPath, INTERNAL_ROOT);
        if (internal) {
            String supplied = httpRequest.getHeader("X-KFE-Internal-Secret");
            if (internalCredential.length == 0) {
                reject(httpResponse, 503, "INTERNAL_AUTH_UNAVAILABLE",
                        "KFE internal authentication is unavailable.");
                return;
            }
            if (supplied == null || supplied.isBlank() || !MessageDigest.isEqual(
                    internalCredential, supplied.getBytes(StandardCharsets.UTF_8))) {
                reject(httpResponse, 401, "AUTHENTICATION_REQUIRED", "KFE authentication is required.");
                return;
            }
        } else {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!authenticatedUser(authentication)) {
                reject(httpResponse, 401, "AUTHENTICATION_REQUIRED", "KFE authentication is required.");
                return;
            }
            if ((beneath(path, ADMIN_ROOT) || beneath(mappedPath, ADMIN_ROOT))
                    && authentication.getAuthorities().stream()
                    .noneMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
                reject(httpResponse, 403, "ADMIN_REQUIRED", "ROLE_ADMIN is required.");
                return;
            }
        }
        if (controlRequest(httpRequest.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }

        // Invocation-local state only: no request attribute or thread-local token can
        // leak to another request/dispatch. Synchronous nesting belongs to the guard.
        boolean[] entered = {false};
        try {
            guard.executeMutation("http.financial-root", () -> {
                entered[0] = true;
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException failure) {
                    // The guard must see a failure before resolving its durable root.
                    throw new CheckedChainFailure(failure);
                }
                return Boolean.FALSE;
            }, ignored -> false);
        } catch (CheckedChainFailure failure) {
            if (failure.getCause() instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw (ServletException) failure.getCause();
        } catch (KfeMaintenanceGuard.MaintenanceException rejection) {
            // Downstream failures are not admission rejections and may follow effects
            // or a committed/streamed response. Preserve their original propagation.
            if (entered[0] || httpResponse.isCommitted()) {
                throw rejection;
            }
            reject(httpResponse, 503, "MAINTENANCE_ADMISSION_REJECTED",
                    "KFE financial admission is unavailable or draining.");
        }
    }

    private static boolean authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null) {
            return false;
        }
        try {
            return Long.parseLong(authentication.getName()) > 0;
        } catch (NumberFormatException invalidIdentity) {
            return false;
        }
    }

    private static boolean financialPath(String path) {
        // Public payment-request display/lookup intentionally supports anonymous
        // callers. Its expiry-on-read needs a service/capability admission boundary;
        // retain the coverage blockers rather than changing that public contract.
        return beneath(path, "/kfe") || beneath(path, ADMIN_ROOT)
                || beneath(path, INTERNAL_ROOT);
    }

    private static boolean beneath(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static boolean controlRequest(String method, String path) {
        if ("POST".equals(method)) {
            return path.equals(MAINTENANCE_ROOT + "/drain")
                    || path.equals(MAINTENANCE_ROOT + "/resume")
                    // Despite its verb, this controller computes a read-only Merkle root.
                    || path.equals(ADMIN_ROOT + "/audit/root");
        }
        return ("GET".equals(method) || "HEAD".equals(method))
                && (READ_CONTROLS.contains(path) || READ_CONTROL_DETAIL.matcher(path).matches());
    }

    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException, ServletException {
        if (response.isCommitted()) {
            throw new ServletException("KFE maintenance rejection cannot replace a committed response.");
        }
        response.resetBuffer();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        // All values are fixed constants: never interpolate paths, credentials or
        // exception text (which can contain SQL, provider details or invalid JSON).
        String body = "{\"schema\":\"" + KfeMaintenanceGuard.SCHEMA
                + "\",\"errorCode\":\"" + code + "\",\"error\":\"" + message + "\"}";
        response.setContentLength(body.getBytes(StandardCharsets.UTF_8).length);
        response.getWriter().write(body);
    }

    private static final class CheckedChainFailure extends RuntimeException {
        private CheckedChainFailure(Exception cause) {
            super(cause);
        }
    }
}
