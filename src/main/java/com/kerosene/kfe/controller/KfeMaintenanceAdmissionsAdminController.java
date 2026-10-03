package com.kerosene.kfe.controller;

import jakarta.servlet.http.HttpServletRequest;

import com.kerosene.kfe.maintenance.KfeMaintenanceAdmissionQuery;
import com.kerosene.kfe.maintenance.KfeMaintenanceAdmissionQuery.Page;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Read-only diagnostics; admission identifiers never authorize resolution. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class KfeMaintenanceAdmissionsAdminController {
    private static final String ERROR_SCHEMA = KfeMaintenanceAdmissionQuery.SCHEMA;
    private final KfeMaintenanceAdmissionQuery query;

    public KfeMaintenanceAdmissionsAdminController(KfeMaintenanceAdmissionQuery query) {
        this.query = query;
    }

    @GetMapping("/api/admin/kfe/maintenance/admissions")
    public ResponseEntity<Page> admissions(@RequestParam(name = "limit", defaultValue = "50") int limit,
                                           @RequestParam(name = "cursor", required = false) String cursor,
                                           Authentication authentication, HttpServletRequest request) {
        requireOperator(authentication);
        Page page = query.page(limit, cursor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body("HEAD".equals(request.getMethod()) ? null : page);
    }

    private void requireOperator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || "anonymousUser".equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An authenticated operator is required.");
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROLE_ADMIN is required.");
        }
        if (KfeAuthenticationSupport.authenticatedUserId(authentication) <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An authenticated operator is required.");
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalidRequest(Exception ignored, HttpServletRequest request) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(
                "HEAD".equals(request.getMethod()) ? null : Map.of(
                        "schema", ERROR_SCHEMA, "error", "Invalid maintenance admissions request."));
    }

    @ExceptionHandler(KfeMaintenanceGuard.MaintenanceException.class)
    public ResponseEntity<Map<String, String>> unavailable(KfeMaintenanceGuard.MaintenanceException ignored,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(
                "HEAD".equals(request.getMethod()) ? null : Map.of(
                        "schema", ERROR_SCHEMA, "error", "Maintenance admissions are unavailable."));
    }
}
