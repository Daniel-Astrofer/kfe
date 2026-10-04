package com.kerosene.kfe.paymentexecution.adapters.in.http;

import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

final class AuthenticatedUserResolver {

    private AuthenticatedUserResolver() {
    }

    static long userId(Authentication authentication) {
        if (authentication == null
                || authentication.getName() == null
                || "anonymousUser".equals(authentication.getName())) {
            throw unauthenticated();
        }
        try {
            long userId = Long.parseLong(authentication.getName());
            if (userId <= 0L) {
                throw unauthenticated();
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw unauthenticated();
        }
    }

    private static StructuredPlatformException unauthenticated() {
        return new StructuredPlatformException(
                "Usuario autenticado e obrigatorio para operacoes KFE.",
                HttpStatus.UNAUTHORIZED,
                ErrorCodes.AUTH_INVALID_CREDENTIALS,
                null);
    }
}
