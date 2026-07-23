package source.kfe.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import source.common.exception.ErrorCodes;
import source.common.exception.StructuredPlatformException;

final class KfeAuthenticationSupport {

    private KfeAuthenticationSupport() {
    }

    static Long authenticatedUserId(Authentication authentication) {
        if (authentication == null
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw unauthenticated();
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException exception) {
            throw unauthenticated();
        }
    }

    static StructuredPlatformException unauthenticated() {
        return new StructuredPlatformException(
                "Usuario autenticado e obrigatorio para operacoes KFE.",
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                null);
    }
}
