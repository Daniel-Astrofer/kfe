package com.kerosene.kfe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.common.security.StringColumnCryptoPort;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * AES-256-GCM column crypto for KFE standalone (xpub/descriptor at rest).
 *
 * <p>The main auth server uses {@code CosignerSecretService} + Vault. KFE is a
 * separate process and only scans {@code com.kerosene.kfe}, so it needs its own port
 * or {@link com.kerosene.common.persistence.StringCryptoConverter} fails with
 * "crypto port is not initialized".
 *
 * <p>Key resolution (first match):
 * <ol>
 *   <li>{@code KFE_COLUMN_CRYPTO_KEY_BASE64} — primary 32-byte AES key, base64</li>
 *   <li>SHA-256 of {@code KFE_INTERNAL_SHARED_SECRET} — only if
 *       {@code kfe.crypto.allow-shared-secret-derivation=true} (off by default)</li>
 * </ol>
 *
 * <p>Key rotation: set {@code KFE_COLUMN_CRYPTO_KEY_BASE64_V2} to a new key.
 * Decrypt tries v1 then v2; encrypt always uses the highest available version.
 */
@Service
public class KfeStringColumnCryptoService implements StringColumnCryptoPort {

    private static final Logger log = LoggerFactory.getLogger(KfeStringColumnCryptoService.class);
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey masterKey;
    private final List<SecretKey> decryptKeys;
    private final byte[] masterKeyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    public KfeStringColumnCryptoService(
            @Value("${kfe.column-crypto.key-base64:}") String keyBase64,
            @Value("${kfe.column-crypto.key-base64-v2:}") String keyBase64V2,
            @Value("${kfe.internal.shared-secret:}") String sharedSecret,
            @Value("${kfe.crypto.allow-shared-secret-derivation:false}") boolean allowSharedSecretDerivation) {
        KeyResolutionResult resolution = resolveKeys(keyBase64, keyBase64V2, sharedSecret,
                allowSharedSecretDerivation);
        this.masterKey = resolution.primaryKey;
        this.decryptKeys = resolution.decryptKeys;
        this.masterKeyBytes = resolution.primaryKeyBytes;
        log.info("[KfeStringColumnCrypto] Initialized AES-256-GCM column crypto (key source: {}).",
                resolution.sourceDescription);
    }

    private static KeyResolutionResult resolveKeys(String keyBase64, String keyBase64V2,
                                                    String sharedSecret, boolean allowSharedSecretDerivation) {
        byte[] primaryBytes = null;
        byte[] v2Bytes = null;
        String sourceDesc;
        boolean fallback = false;

        if (keyBase64 != null && !keyBase64.isBlank()) {
            primaryBytes = decodeKey(keyBase64, "KFE_COLUMN_CRYPTO_KEY_BASE64");
            sourceDesc = "KFE_COLUMN_CRYPTO_KEY_BASE64";
        } else if (allowSharedSecretDerivation) {
            fallback = true;
            if (sharedSecret == null || sharedSecret.isBlank()) {
                throw new IllegalStateException(
                        "KFE column crypto: shared-secret derivation enabled but "
                                + "KFE_INTERNAL_SHARED_SECRET is not set.");
            }
            primaryBytes = deriveFromSharedSecret(sharedSecret);
            sourceDesc = "SHA-256(KFE_INTERNAL_SHARED_SECRET) [fallback]";
        } else {
            throw new IllegalStateException(
                    "KFE column crypto key (KFE_COLUMN_CRYPTO_KEY_BASE64) is not set and "
                            + "shared-secret derivation is disabled. "
                            + "Set KFE_COLUMN_CRYPTO_KEY_BASE64 or enable kfe.crypto.allow-shared-secret-derivation.");
        }

        if (fallback) {
            log.warn("KFE column crypto is deriving AES key from KFE_INTERNAL_SHARED_SECRET. "
                    + "This mixes service auth with data encryption. "
                    + "Set KFE_COLUMN_CRYPTO_KEY_BASE64 to a dedicated 32-byte base64 key.");
        }

        // Key versioning: V2 for rotation
        if (keyBase64V2 != null && !keyBase64V2.isBlank()) {
            v2Bytes = decodeKey(keyBase64V2, "KFE_COLUMN_CRYPTO_KEY_BASE64_V2");
            sourceDesc += " + V2 rotation key";
        }

        List<SecretKey> decryptKeyList = new ArrayList<>();
        decryptKeyList.add(new SecretKeySpec(primaryBytes, "AES"));
        if (v2Bytes != null) {
            decryptKeyList.add(new SecretKeySpec(v2Bytes, "AES"));
        }

        // Encrypt with latest (V2 if available, else V1)
        SecretKey encryptKey;
        if (v2Bytes != null) {
            encryptKey = new SecretKeySpec(v2Bytes, "AES");
        } else {
            encryptKey = new SecretKeySpec(primaryBytes, "AES");
        }

        return new KeyResolutionResult(encryptKey, decryptKeyList, encryptKey.getEncoded(), sourceDesc);
    }

    private static byte[] decodeKey(String base64, String envVar) {
        byte[] decoded = Base64.getDecoder().decode(base64.trim());
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    envVar + " must decode to exactly 32 bytes (AES-256), got " + decoded.length);
        }
        return decoded;
    }

    private static byte[] deriveFromSharedSecret(String sharedSecret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(sharedSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive KFE column crypto key from shared secret", e);
        }
    }

    @Override
    public String encrypt(byte[] plainBytes) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainBytes);
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("KFE column encrypt failed", e);
        }
    }

    @Override
    public byte[] decrypt(String encryptedValue) {
        byte[] combined = Base64.getDecoder().decode(encryptedValue);
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

        // Try each key for rotation support (v1 first, then v2)
        for (int i = 0; i < decryptKeys.size(); i++) {
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, decryptKeys.get(i),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                return cipher.doFinal(cipherText);
            } catch (Exception e) {
                if (i == decryptKeys.size() - 1) {
                    throw new IllegalStateException(
                            "KFE column decrypt failed with all available keys", e);
                }
                // Try next key
            }
        }
        throw new IllegalStateException("KFE column decrypt failed: no keys available");
    }

    @Override
    public byte[] getMasterKeyBytes() {
        return Arrays.copyOf(masterKeyBytes, masterKeyBytes.length);
    }

    private record KeyResolutionResult(
            SecretKey primaryKey,
            List<SecretKey> decryptKeys,
            byte[] primaryKeyBytes,
            String sourceDescription) {
    }
}
