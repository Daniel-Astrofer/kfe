package com.kerosene.kfe.integration;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class Bip340Verifier {

    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    private static final BigInteger FIELD_P =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    private static final BigInteger ORDER_N = CURVE.getN();

    private Bip340Verifier() {
    }

    static boolean verify(byte[] message32, byte[] publicKey, byte[] signature64) {
        if (message32 == null || message32.length != 32
                || signature64 == null || signature64.length != 64) {
            return false;
        }
        byte[] xOnly = normalizeXOnly(publicKey);
        if (xOnly == null) {
            return false;
        }
        BigInteger px = new BigInteger(1, xOnly);
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature64, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature64, 32, 64));
        if (px.compareTo(FIELD_P) >= 0 || r.compareTo(FIELD_P) >= 0 || s.compareTo(ORDER_N) >= 0) {
            return false;
        }
        try {
            byte[] compressed = new byte[33];
            compressed[0] = 0x02;
            System.arraycopy(xOnly, 0, compressed, 1, 32);
            ECPoint point = CURVE.getCurve().decodePoint(compressed).normalize();
            byte[] challengeInput = new byte[96];
            System.arraycopy(signature64, 0, challengeInput, 0, 32);
            System.arraycopy(xOnly, 0, challengeInput, 32, 32);
            System.arraycopy(message32, 0, challengeInput, 64, 32);
            BigInteger challenge = new BigInteger(1, taggedHash("BIP0340/challenge", challengeInput))
                    .mod(ORDER_N);
            ECPoint reconstructed = CURVE.getG().multiply(s)
                    .subtract(point.multiply(challenge))
                    .normalize();
            return !reconstructed.isInfinity()
                    && !reconstructed.getAffineYCoord().toBigInteger().testBit(0)
                    && reconstructed.getAffineXCoord().toBigInteger().equals(r);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] normalizeXOnly(byte[] key) {
        if (key == null) {
            return null;
        }
        if (key.length == 32) {
            return key.clone();
        }
        if (key.length == 33 && (key[0] == 0x02 || key[0] == 0x03)) {
            return Arrays.copyOfRange(key, 1, 33);
        }
        return null;
    }

    private static byte[] taggedHash(String tag, byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] tagHash = digest.digest(tag.getBytes(StandardCharsets.US_ASCII));
            digest.reset();
            digest.update(tagHash);
            digest.update(tagHash);
            return digest.digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
