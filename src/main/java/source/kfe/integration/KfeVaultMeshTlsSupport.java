package source.kfe.integration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Optional mTLS materials for {@link KfeVaultMeshSettlementClient}.
 * Supports PKCS12 keystore/truststore paths or PEM cert/key/CA paths ({@code kfe.vaultmesh.tls.*}).
 */
final class KfeVaultMeshTlsSupport {

    private KfeVaultMeshTlsSupport() {}

    static boolean tlsConfigured(
            boolean enabled,
            String certPath,
            String keyPath,
            String caPath,
            String keystorePath,
            String truststorePath) {
        if (!enabled) {
            return false;
        }
        boolean pem = isPresent(certPath) && isPresent(keyPath) && isPresent(caPath);
        boolean stores = isPresent(keystorePath) && isPresent(truststorePath);
        if (!pem && !stores) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.tls.enabled=true requires PEM paths "
                            + "(cert-path, key-path, ca-path) or keystore/truststore paths");
        }
        return true;
    }

    static SSLContext buildSslContext(
            String certPath,
            String keyPath,
            String caPath,
            String keystorePath,
            String keystorePassword,
            String keystoreType,
            String truststorePath,
            String truststorePassword,
            String truststoreType) {
        try {
            KeyManagerFactory kmf;
            TrustManagerFactory tmf;
            if (isPresent(keystorePath) && isPresent(truststorePath)) {
                kmf = keyManagersFromKeystore(keystorePath, keystorePassword, keystoreType);
                tmf = trustManagersFromTruststore(truststorePath, truststorePassword, truststoreType);
            } else {
                kmf = keyManagersFromPem(certPath, keyPath);
                tmf = trustManagersFromPem(caPath);
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build vault-mesh mTLS SSLContext: " + ex.getMessage(), ex);
        }
    }

    static SimpleClientHttpRequestFactory requestFactory(
            SSLContext sslContext,
            boolean hostnameVerification,
            int connectTimeoutMs,
            int readTimeoutMs) {
        HostnameVerifier verifier = hostnameVerification
                ? HttpsURLConnection.getDefaultHostnameVerifier()
                : (String hostname, SSLSession session) -> true;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                if (connection instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(sslContext.getSocketFactory());
                    https.setHostnameVerifier(verifier);
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    static KeyManagerFactory keyManagersFromPem(String certPath, String keyPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();
        try (InputStream in = Files.newInputStream(Path.of(certPath))) {
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            chain.addAll(certs);
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException("kfe.vaultmesh.tls.cert-path contains no certificates: " + certPath);
        }
        PrivateKey privateKey = loadPrivateKey(Path.of(keyPath));
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        char[] password = "vault-mesh".toCharArray();
        keyStore.setKeyEntry("kfe-client", privateKey, password, chain.toArray(Certificate[]::new));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        return kmf;
    }

    static TrustManagerFactory trustManagersFromPem(String caPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int i = 0;
        try (InputStream in = Files.newInputStream(Path.of(caPath))) {
            for (Certificate cert : cf.generateCertificates(in)) {
                trustStore.setCertificateEntry("vault-mesh-ca-" + (i++), cert);
            }
        }
        if (i == 0) {
            throw new IllegalStateException("kfe.vaultmesh.tls.ca-path contains no certificates: " + caPath);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }

    private static KeyManagerFactory keyManagersFromKeystore(
            String path, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(blankToDefault(type, "PKCS12"));
        char[] pass = nullToEmpty(password).toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            keyStore.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pass);
        return kmf;
    }

    private static TrustManagerFactory trustManagersFromTruststore(
            String path, String password, String type) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(blankToDefault(type, "PKCS12"));
        char[] pass = nullToEmpty(password).toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            trustStore.load(in, pass);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }

    static PrivateKey loadPrivateKey(Path keyPath) throws Exception {
        String pem = Files.readString(keyPath, StandardCharsets.US_ASCII);
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            byte[] pkcs1 = decodePemBlock(pem, "RSA PRIVATE KEY");
            byte[] pkcs8 = wrapPkcs1RsaInPkcs8(pkcs1);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
        if (pem.contains("BEGIN PRIVATE KEY")) {
            byte[] pkcs8 = decodePemBlock(pem, "PRIVATE KEY");
            // Try RSA first (lab scripts); EC would need a different factory.
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
        // Raw DER PKCS#8
        byte[] der = Files.readAllBytes(keyPath);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Minimal PKCS#1 RSA → PKCS#8 wrap (no BouncyCastle). */
    static byte[] wrapPkcs1RsaInPkcs8(byte[] pkcs1) {
        byte[] rsaOid = new byte[] {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };
        byte[] lenOctets = encodeDerLength(pkcs1.length);
        byte[] oct = new byte[1 + lenOctets.length + pkcs1.length];
        oct[0] = 0x04;
        System.arraycopy(lenOctets, 0, oct, 1, lenOctets.length);
        System.arraycopy(pkcs1, 0, oct, 1 + lenOctets.length, pkcs1.length);

        byte[] version = new byte[] {0x02, 0x01, 0x00};
        int innerLen = version.length + rsaOid.length + oct.length;
        byte[] innerLenOctets = encodeDerLength(innerLen);
        byte[] out = new byte[1 + innerLenOctets.length + innerLen];
        out[0] = 0x30;
        System.arraycopy(innerLenOctets, 0, out, 1, innerLenOctets.length);
        int pos = 1 + innerLenOctets.length;
        System.arraycopy(version, 0, out, pos, version.length);
        pos += version.length;
        System.arraycopy(rsaOid, 0, out, pos, rsaOid.length);
        pos += rsaOid.length;
        System.arraycopy(oct, 0, out, pos, oct.length);
        return out;
    }

    private static byte[] encodeDerLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        if (length < 0x100) {
            return new byte[] {(byte) 0x81, (byte) length};
        }
        if (length < 0x10000) {
            return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        throw new IllegalArgumentException("DER length too large: " + length);
    }

    private static byte[] decodePemBlock(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0 || stop <= start) {
            throw new IllegalStateException("PEM block missing: " + label);
        }
        String b64 = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return isPresent(value) ? value.trim() : fallback;
    }

    /** Exposed for tests — verify a PEM cert path parses as X.509. */
    static X509Certificate readFirstCert(Path path) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(path)) {
            return (X509Certificate) cf.generateCertificate(in);
        }
    }
}
