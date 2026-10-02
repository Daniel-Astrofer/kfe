package com.kerosene.kfe.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/kfe/maintenance")
@PreAuthorize("hasRole('ADMIN')")
public class KfeMaintenanceAdminController {
    private final KfeMaintenanceGuard guard;

    public KfeMaintenanceAdminController(KfeMaintenanceGuard guard) { this.guard = guard; }

    @PostMapping("/drain")
    public KfeMaintenanceGuard.Status drain(@RequestBody Request command,
                                            Authentication authentication) {
        long operatorId = operator(authentication);
        return guard.requestDrain(command.toCommand(), operatorId);
    }

    @PostMapping("/resume")
    public KfeMaintenanceGuard.Status resume(@RequestBody Request command,
                                             Authentication authentication) {
        long operatorId = operator(authentication);
        return guard.resume(command.toCommand(), operatorId);
    }

    @GetMapping("/status")
    public KfeMaintenanceGuard.Status status(Authentication authentication) {
        operator(authentication);
        return guard.status();
    }

    private long operator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An authenticated operator is required.");
        }
        if (authentication.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROLE_ADMIN is required.");
        }
        long id = KfeAuthenticationSupport.authenticatedUserId(authentication);
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An authenticated operator is required.");
        }
        return id;
    }

    public record Request(String changeId, String reason, Long expectedRevision) {
        KfeMaintenanceGuard.Command toCommand() {
            if (expectedRevision == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedRevision is required.");
            }
            try {
                return new KfeMaintenanceGuard.Command(changeId, reason, expectedRevision);
            } catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
            }
        }
    }

    /** Narrow exception mapping shared by the owned guarded controllers. */
    @RestControllerAdvice(basePackages = "com.kerosene.kfe")
    public static class MaintenanceErrors {
        @ExceptionHandler(KfeMaintenanceGuard.MaintenanceException.class)
        public ResponseEntity<Map<String, Object>> maintenance(KfeMaintenanceGuard.MaintenanceException error) {
            return ResponseEntity.status(error.httpStatus()).body(Map.of(
                    "schema", KfeMaintenanceGuard.SCHEMA, "error", error.getMessage()));
        }
    }
}
