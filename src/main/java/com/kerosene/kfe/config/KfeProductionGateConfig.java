package com.kerosene.kfe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Production safety gate that refuses boot on unsafe KFE configurations.
 *
 * <p>Checks:
 * <ol>
 *   <li>JWT secret is present and not the known hardcoded default</li>
 *   <li>JWT issuer and audience are configured</li>
 *   <li>In production mode, revocation.required=true refuses boot if Redis unavailable</li>
 *   <li>Column crypto key is set or fallback is explicitly enabled</li>
 *   <li>In production mode, deposit min-confirmations must be &ge; 1 (mempool-only credit is unsafe)</li>
 * </ol>
 *
 * <p>Fail-closed: all missing required configs cause a boot-time {@link IllegalStateException}.
 */
@Component
public class KfeProductionGateConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KfeProductionGateConfig.class);
    private static final String KNOWN_DEFAULT_SECRET = "super_secret_jwt_key_that_is_long_enough_for_hs256_123!";

    private final String jwtSecret;
    private final String jwtIssuer;
    private final String jwtAudience;
    private final boolean productionMode;
    private final boolean revocationRequired;
    private final StringRedisTemplate redisTemplate;
    private final String columnCryptoKeyBase64;
    private final boolean allowSharedSecretDerivation;
    private final KfeBitcoinFinalityPolicy finalityPolicy;

    public KfeProductionGateConfig(
            @Value("${api.secret.token.secret:}") String jwtSecret,
            @Value("${kfe.auth.jwt.issuer:}") String jwtIssuer,
            @Value("${kfe.auth.jwt.audience:}") String jwtAudience,
            @Value("${kfe.auth.production-mode:false}") boolean productionMode,
            @Value("${kfe.auth.revocation.required:false}") boolean revocationRequired,
            @Value("${kfe.column-crypto.key-base64:}") String columnCryptoKeyBase64,
            @Value("${kfe.crypto.allow-shared-secret-derivation:false}") boolean allowSharedSecretDerivation,
            KfeBitcoinFinalityPolicy finalityPolicy,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.jwtSecret = blankToEmpty(jwtSecret);
        this.jwtIssuer = blankToEmpty(jwtIssuer);
        this.jwtAudience = blankToEmpty(jwtAudience);
        this.productionMode = productionMode;
        this.revocationRequired = revocationRequired;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.columnCryptoKeyBase64 = blankToEmpty(columnCryptoKeyBase64);
        this.allowSharedSecretDerivation = allowSharedSecretDerivation;
        this.finalityPolicy = finalityPolicy;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkJwtSecret();
        checkJwtClaims();
        checkRevocationRedis();
        checkColumnCryptoKey();
        checkDepositMinConfirmations();

        if (productionMode) {
            log.warn("KFE AUTH PRODUCTION-MODE: JWT claims enforced, revocation fail-closed, "
                    + "crypto key required. All security gates active.");
        }
    }

    private void checkJwtSecret() {
        if (jwtSecret.isEmpty()) {
            throw new IllegalStateException(
                    "JWT secret (JWT_SECRET / api.secret.token.secret) is not configured. "
                            + "Production must provide a strong HS256 key via environment variable.");
        }
        if (KNOWN_DEFAULT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "JWT secret matches the known hardcoded default. "
                            + "Production must set JWT_SECRET to a strong unique value.");
        }
    }

    private void checkJwtClaims() {
        if (jwtIssuer.isEmpty()) {
            log.error("JWT issuer (kfe.auth.jwt.issuer) is not configured. "
                    + "Set KFE_AUTH_JWT_ISSUER in production.");
        }
        if (jwtAudience.isEmpty()) {
            log.error("JWT audience (kfe.auth.jwt.audience) is not configured. "
                    + "Set KFE_AUTH_JWT_AUDIENCE in production.");
        }
    }

    private void checkRevocationRedis() {
        if (!revocationRequired) {
            return;
        }
        if (redisTemplate == null) {
            throw new IllegalStateException(
                    "kfe.auth.revocation.required=true but Redis is not available. "
                            + "Revocation is fail-closed; refusing boot.");
        }
        log.info("JWT revocation is required and Redis connectivity confirmed.");
    }

    private void checkColumnCryptoKey() {
        if (!columnCryptoKeyBase64.isEmpty()) {
            return;
        }
        if (allowSharedSecretDerivation) {
            log.warn("KFE column crypto is deriving AES key from KFE_INTERNAL_SHARED_SECRET "
                    + "(kfe.crypto.allow-shared-secret-derivation=true). "
                    + "Set KFE_COLUMN_CRYPTO_KEY_BASE64 for production-grade separation.");
            return;
        }
        throw new IllegalStateException(
                "KFE column crypto key (KFE_COLUMN_CRYPTO_KEY_BASE64) is not set and "
                        + "shared-secret derivation is disabled (kfe.crypto.allow-shared-secret-derivation=false). "
                        + "Set KFE_COLUMN_CRYPTO_KEY_BASE64 to a base64-encoded 32-byte AES key.");
    }

    private void checkDepositMinConfirmations() {
        if (!productionMode) {
            // Non-production: mempool-only credit is acceptable for dev/test.
            log.info("Deposit min-confirmations gate: production-mode=false, "
                    + "mempool-only deposit credit is allowed.");
            return;
        }
        int creditConfirmations = finalityPolicy.getCreditConfirmations();
        if (creditConfirmations < 1) {
            throw new IllegalStateException(
                    "bitcoin.credit-confirmations is "
                            + creditConfirmations
                            + " but production mode requires at least 1. "
                            + "Mempool-only deposit crediting (minConfirmations=0) is unsafe "
                            + "in production — a 0-conf tx can be double-spent. "
                            + "Set bitcoin.credit-confirmations to 1 or higher "
                            + "(default is 3) or set kfe.auth.production-mode=false for testing.");
        }
        log.info("Deposit credit-confirmations gate: {} (production, >=1 OK)", creditConfirmations);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
