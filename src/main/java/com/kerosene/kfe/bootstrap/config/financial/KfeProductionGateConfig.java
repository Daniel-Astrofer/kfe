package com.kerosene.kfe.bootstrap.config.financial;

import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

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

    /** Logger for safe startup gate outcomes; secret values are never written to logs. */
    private static final Logger log = LoggerFactory.getLogger(KfeProductionGateConfig.class);
    /** Known insecure bundled secret rejected to prevent deployments from retaining sample credentials. */
    private static final String KNOWN_DEFAULT_SECRET = "super_secret_jwt_key_that_is_long_enough_for_hs256_123!";

    /** Active JWT signing secret validated against absence and the bundled insecure value. */
    private final String jwtSecret;
    /** Required JWT issuer claim configured for issued/authenticated tokens. */
    private final String jwtIssuer;
    /** Required JWT audience claim configured for issued/authenticated tokens. */
    private final String jwtAudience;
    /** Optional previous JWT signing secret retained during a bounded key-rotation window. */
    private final String previousJwtSecret;
    /** ISO-8601 expiry for the previous JWT signing secret. */
    private final String previousJwtSecretExpiresAt;
    /** Whether production-only fail-closed requirements are enforced. */
    private final boolean productionMode;
    /** Whether revocation infrastructure must be available in production. */
    private final boolean revocationRequired;
    /** Whether JWT verification performs revocation lookups. */
    private final boolean revocationCheckEnabled;
    /** Optional Redis client used to probe production JWT revocation infrastructure. */
    private final StringRedisTemplate redisTemplate;
    /** Base64-encoded dedicated AES key used for encrypted database columns. */
    private final String columnCryptoKeyBase64;
    /** Explicit opt-in permitting column-key derivation from the shared internal secret. */
    private final boolean allowSharedSecretDerivation;
    /** Internal service authentication secret, required to be strong in production. */
    private final String internalSharedSecret;
    /** Bitcoin deposit finality settings inspected by the production confirmation gate. */
    private final KfeBitcoinFinalityPolicy finalityPolicy;

    /**
     * Captures normalized security and finality properties plus an optional Redis revocation client.
     *
     * @param jwtSecret active JWT signing secret
     * @param jwtIssuer required JWT issuer claim
     * @param jwtAudience required JWT audience claim
     * @param previousJwtSecret optional prior signing key for rotation
     * @param previousJwtSecretExpiresAt ISO-8601 expiry for the prior key
     * @param productionMode enables production-only fail-closed checks
     * @param revocationRequired whether revocation must remain required in production
     * @param revocationCheckEnabled whether JWT verification performs revocation checks
     * @param columnCryptoKeyBase64 dedicated Base64-encoded column encryption key
     * @param allowSharedSecretDerivation explicit fallback key derivation switch
     * @param internalSharedSecret inter-service authentication secret
     * @param finalityPolicy Bitcoin deposit confirmation policy
     * @param redisTemplateProvider optional Redis client for the startup probe
     */
    public KfeProductionGateConfig(
            @Value("${api.secret.token.secret:}") String jwtSecret,
            @Value("${kfe.auth.jwt.issuer:}") String jwtIssuer,
            @Value("${kfe.auth.jwt.audience:}") String jwtAudience,
            @Value("${api.secret.token.previous:}") String previousJwtSecret,
            @Value("${api.secret.token.previous.expires-at:}") String previousJwtSecretExpiresAt,
            @Value("${kfe.auth.production-mode:false}") boolean productionMode,
            @Value("${kfe.auth.revocation.required:true}") boolean revocationRequired,
            @Value("${kfe.security.jwt.revocation-check-enabled:true}") boolean revocationCheckEnabled,
            @Value("${kfe.column-crypto.key-base64:}") String columnCryptoKeyBase64,
            @Value("${kfe.crypto.allow-shared-secret-derivation:false}") boolean allowSharedSecretDerivation,
            @Value("${kfe.internal.shared-secret:}") String internalSharedSecret,
            KfeBitcoinFinalityPolicy finalityPolicy,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this.jwtSecret = blankToEmpty(jwtSecret);
        this.jwtIssuer = blankToEmpty(jwtIssuer);
        this.jwtAudience = blankToEmpty(jwtAudience);
        this.previousJwtSecret = blankToEmpty(previousJwtSecret);
        this.previousJwtSecretExpiresAt = blankToEmpty(previousJwtSecretExpiresAt);
        this.productionMode = productionMode;
        this.revocationRequired = revocationRequired;
        this.revocationCheckEnabled = revocationCheckEnabled;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.columnCryptoKeyBase64 = blankToEmpty(columnCryptoKeyBase64);
        this.allowSharedSecretDerivation = allowSharedSecretDerivation;
        this.internalSharedSecret = blankToEmpty(internalSharedSecret);
        this.finalityPolicy = finalityPolicy;
    }

    /**
     * Runs all security gates before the application begins serving requests.
     * Production mode additionally enforces revocation availability, deposit confirmations,
     * and a dedicated strong internal authentication secret.
     *
     * @param args parsed Spring Boot application arguments
     * @throws IllegalStateException when any required gate fails
     */
    @Override
    public void run(ApplicationArguments args) {
        checkJwtSecret();
        checkJwtClaims();
        checkJwtKeyRotation();
        checkRevocationRedis();
        checkColumnCryptoKey();
        checkDepositMinConfirmations();
        checkInternalAuthenticationSecret();

        if (productionMode) {
            log.warn("KFE AUTH PRODUCTION-MODE: JWT claims enforced, revocation fail-closed, "
                    + "crypto key required. All security gates active.");
        }
    }

    /**
     * Requires a configured JWT key and rejects the known sample secret.
     *
     * @throws IllegalStateException when the key is missing or equals the bundled default
     */
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

    /**
     * Requires both issuer and audience so token validation has explicit intended scope.
     *
     * @throws IllegalStateException when either claim value is missing
     */
    private void checkJwtClaims() {
        if (jwtIssuer.isEmpty()) {
            throw new IllegalStateException(
                    "JWT issuer (KFE_AUTH_JWT_ISSUER / kfe.auth.jwt.issuer) is required.");
        }
        if (jwtAudience.isEmpty()) {
            throw new IllegalStateException(
                    "JWT audience (KFE_AUTH_JWT_AUDIENCE / kfe.auth.jwt.audience) is required.");
        }
    }

    /**
     * Validates that an optional previous JWT key has sufficient length and a future ISO-8601 expiry.
     *
     * @throws IllegalStateException when rotation properties are incomplete, malformed, or expired
     */
    private void checkJwtKeyRotation() {
        if (previousJwtSecret.isEmpty() && previousJwtSecretExpiresAt.isEmpty()) {
            return;
        }
        if (previousJwtSecret.length() < 32 || previousJwtSecretExpiresAt.isEmpty()) {
            throw new IllegalStateException(
                    "JWT previous secret rotation requires a 32+ character key and an ISO-8601 expiry.");
        }
        final Instant expiresAt;
        try {
            expiresAt = Instant.parse(previousJwtSecretExpiresAt);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "api.secret.token.previous.expires-at must be a valid ISO-8601 instant.", exception);
        }
        if (!expiresAt.isAfter(Instant.now())) {
            throw new IllegalStateException(
                    "JWT previous secret rotation window has expired; remove the previous secret.");
        }
    }

    /**
     * In production, requires revocation to be enabled/required and verifies Redis is reachable.
     * Nonproduction mode skips this production-only dependency check.
     *
     * @throws IllegalStateException when production revocation policy is disabled or Redis is unavailable
     */
    private void checkRevocationRedis() {
        if (!productionMode) {
            return;
        }
        if (!revocationCheckEnabled || !revocationRequired) {
            throw new IllegalStateException(
                    "JWT revocation must be enabled and required in production mode.");
        }
        if (redisTemplate == null) {
            throw new IllegalStateException(
                    "kfe.auth.revocation.required=true but Redis is not available. "
                            + "Revocation is fail-closed; refusing boot.");
        }
        try {
            redisTemplate.hasKey("kfe:startup:revocation-probe");
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "JWT revocation Redis is unavailable; refusing boot.", exception);
        }
        log.info("JWT revocation is required and Redis connectivity confirmed.");
    }

    /**
     * Requires a dedicated Base64 column-crypto key unless shared-secret derivation was explicitly enabled.
     * The fallback is logged as a reduced-separation mode and must be consciously configured.
     *
     * @throws IllegalStateException when neither a dedicated key nor the fallback opt-in exists
     */
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

    /**
     * Rejects zero-confirmation deposit credit in production while allowing it in nonproduction.
     *
     * @throws IllegalStateException when production credit threshold is below one confirmation
     */
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

    /**
     * Requires a dedicated internal service secret of at least 32 characters in production mode.
     *
     * @throws IllegalStateException when production secret is too short
     */
    private void checkInternalAuthenticationSecret() {
        if (productionMode && internalSharedSecret.length() < 32) {
            throw new IllegalStateException(
                    "kfe.internal.shared-secret must be a dedicated random secret of at least 32 characters.");
        }
    }

    /**
     * Normalizes optional configuration text by trimming whitespace and mapping null to empty text.
     *
     * @param value property value
     * @return trimmed value or empty string when absent
     */
    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
