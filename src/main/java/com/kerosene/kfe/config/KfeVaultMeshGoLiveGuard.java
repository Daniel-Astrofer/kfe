package com.kerosene.kfe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Enforces the only supported KFE runtime: production controls, Bitcoin
 * testnet3, 2-of-3 Vault mesh, Tor and mutual TLS. There is no bypass profile.
 */
@Component
public class KfeVaultMeshGoLiveGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshGoLiveGuard.class);

    private final boolean meshOnly;
    private final boolean vaultMeshEnabled;
    private final boolean mpcSigningEnabled;
    private final boolean requireMtls;
    private final boolean tlsEnabled;
    private final String apiToken;
    private final String tlsCertPath;
    private final String tlsKeyPath;
    private final String tlsCaPath;
    private final String tlsKeystorePath;
    private final String tlsTruststorePath;
    private final String vaultMeshTransport;
    private final boolean tlsHostnameVerification;
    private final String bitcoinNetwork;
    private final boolean localCoreSignerEnabled;
    private final int memberCount;
    private final int threshold;
    private final String constitutionHash;

    public KfeVaultMeshGoLiveGuard(
            @Value("${kfe.vaultmesh.mesh-only:true}") boolean meshOnly,
            @Value("${kfe.vaultmesh.enabled:true}") boolean vaultMeshEnabled,
            @Value("${kfe.mpc.signing-enabled:false}") boolean mpcSigningEnabled,
            @Value("${kfe.vaultmesh.require-mtls:true}") boolean requireMtls,
            @Value("${kfe.vaultmesh.tls.enabled:true}") boolean tlsEnabled,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.tls.cert-path:}") String tlsCertPath,
            @Value("${kfe.vaultmesh.tls.key-path:}") String tlsKeyPath,
            @Value("${kfe.vaultmesh.tls.ca-path:}") String tlsCaPath,
            @Value("${kfe.vaultmesh.tls.keystore-path:}") String tlsKeystorePath,
            @Value("${kfe.vaultmesh.tls.truststore-path:}") String tlsTruststorePath,
            @Value("${kfe.vaultmesh.transport:tor}") String vaultMeshTransport,
            @Value("${kfe.vaultmesh.tls.hostname-verification:true}") boolean tlsHostnameVerification,
            @Value("${bitcoin.network:testnet3}") String bitcoinNetwork,
            @Value("${quorum.psbt.local-core-signer-enabled:false}") boolean localCoreSignerEnabled,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold,
            @Value("${kfe.vaultmesh.constitution.hash:}") String constitutionHash) {
        this.meshOnly = meshOnly;
        this.vaultMeshEnabled = vaultMeshEnabled;
        this.mpcSigningEnabled = mpcSigningEnabled;
        this.requireMtls = requireMtls;
        this.tlsEnabled = tlsEnabled;
        this.apiToken = blankToEmpty(apiToken);
        this.tlsCertPath = blankToEmpty(tlsCertPath);
        this.tlsKeyPath = blankToEmpty(tlsKeyPath);
        this.tlsCaPath = blankToEmpty(tlsCaPath);
        this.tlsKeystorePath = blankToEmpty(tlsKeystorePath);
        this.tlsTruststorePath = blankToEmpty(tlsTruststorePath);
        this.vaultMeshTransport = blankToEmpty(vaultMeshTransport);
        this.tlsHostnameVerification = tlsHostnameVerification;
        this.bitcoinNetwork = blankToEmpty(bitcoinNetwork);
        this.localCoreSignerEnabled = localCoreSignerEnabled;
        this.memberCount = memberCount;
        this.threshold = threshold;
        this.constitutionHash = blankToEmpty(constitutionHash);
    }

    @Override
    public void run(ApplicationArguments args) {
        require(vaultMeshEnabled, "kfe.vaultmesh.enabled must be true");
        require(meshOnly, "kfe.vaultmesh.mesh-only must be true");
        require(!mpcSigningEnabled, "kfe.mpc.signing-enabled must be false");
        require(!localCoreSignerEnabled, "quorum.psbt.local-core-signer-enabled must be false");
        require(requireMtls, "kfe.vaultmesh.require-mtls must be true");
        require(tlsEnabled, "kfe.vaultmesh.tls.enabled must be true");
        require(apiToken.isEmpty(), "kfe.vaultmesh.api-token was removed; use mTLS identity");
        require("tor".equalsIgnoreCase(vaultMeshTransport), "kfe.vaultmesh.transport must be tor");
        require(tlsHostnameVerification, "Vault TLS hostname verification must remain enabled");
        require("testnet3".equalsIgnoreCase(bitcoinNetwork) || "testnet".equalsIgnoreCase(bitcoinNetwork),
                "bitcoin.network must be testnet3");
        require(memberCount == 3 && threshold == 2, "Vault constitution must be exactly 2-of-3");
        require(constitutionHash.matches("[0-9a-fA-F]{64}"),
                "kfe.vaultmesh.constitution.hash must be a 32-byte hexadecimal digest");

        boolean pem = !tlsCertPath.isEmpty() && !tlsKeyPath.isEmpty() && !tlsCaPath.isEmpty();
        boolean pkcs12 = !tlsKeystorePath.isEmpty() && !tlsTruststorePath.isEmpty();
        require(pem || pkcs12, "Vault client mTLS PEM or PKCS12 material is required");

        log.info("KFE production guard active: testnet3, 2-of-3 Vault mesh, Tor and mTLS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
