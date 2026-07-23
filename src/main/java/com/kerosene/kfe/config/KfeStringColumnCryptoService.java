package com.kerosene.kfe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import source.common.security.StringColumnCryptoPort;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM column crypto for KFE standalone (xpub/descriptor at rest).
 *
 * <p>The main auth server uses {@code CosignerSecretService} + Vault. KFE is a
 * separate process and only scans {@code com.kerosene.kfe}, so it needs its own port
 * or {@link source.common.persistence.StringCryptoConverter} fails with
 * "crypto port is not initialized".
 *
 * <p>Key resolution (first match):
 * <ol>
 *   <li>{@code kfe.column-crypto.key-base64} — raw 32-byte AES key, base64</li>
 *   <li>SHA-256 of {@code KFE_INTERNAL_SHARED_SECRET} / {@code kfe.internal.shared-secret}</li>
 * </ol>
 */
@Service
public class KfeStringColumnCryptoService implements StringColumnCryptoPort {

    private static final Logger log = LoggerFactory.getLogger(KfeStringColumnCryptoService.class);
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey masterKey;
    private final byte[] masterKeyBytes;
    private final SecureRandom secureRandom = new SecureRandom();

    public KfeStringColumnCryptoService(
            @Value("${kfe.column-crypto.key-base64:}") String keyBase64,
            @Value("${kfe.internal.shared-secret:}") String sharedSecret) {
        this.masterKeyBytes = resolveKeyBytes(keyBase64, sharedSecret);
        this.masterKey = new SecretKeySpec(masterKeyBytes, "AES");
        log.info(
                "[KfeStringColumnCrypto] Initialized AES-256-GCM column crypto (key source: {}).",
                (keyBase64 != null && !keyBase64.isBlank()) ? "kfe.column-crypto.key-base64" : "shared-secret-digest");
    }

    private static byte[] resolveKeyBytes(String keyBase64, String sharedSecret) {
        if (keyBase64 != null && !keyBase64.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(keyBase64.trim());
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "kfe.column-crypto.key-base64 must decode to exactly 32 bytes (AES-256).");
            }
            return decoded;
        }
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalStateException(
                    "KFE column crypto requires kfe.column-crypto.key-base64 or KFE_INTERNAL_SHARED_SECRET.");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(sharedSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive KFE column crypto key", e);
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
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedValue);
            if (combined.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("KFE column decrypt failed", e);
        }
    }

    @Override
    public byte[] getMasterKeyBytes() {
        return Arrays.copyOf(masterKeyBytes, masterKeyBytes.length);
    }
}
