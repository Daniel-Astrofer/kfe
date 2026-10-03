package com.kerosene.kfe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.FinancialQuorumPort;
import com.kerosene.common.vaultmesh.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import com.kerosene.kfe.maintenance.KfeMaintenanceService;
import com.kerosene.kfe.maintenance.KfeMaintenanceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "removal"})
class KfeVaultProviderMaintenanceTest {
    private enum Root { THRESHOLD, LEGACY, MPC }
    private static final String BASE = "http://synthetic-vault.invalid";
    private static final String KEY = "ab".repeat(32);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate transport = mock(RestTemplate.class);
    private final ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
    private final VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final FinancialQuorumPort.Proposal proposal = new FinancialQuorumPort.Proposal(
            "12".repeat(32), "34".repeat(32), 1, Instant.now(), Instant.now().plusSeconds(3600));
    private VaultMeshFinancialQuorumAdapter quorum;
    private KfeVaultMeshMpcKeyAdapter mpc;

    @BeforeEach
    void setup() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(transport);
        quorum = new VaultMeshFinancialQuorumAdapter(builder, mapper, BASE, BASE, "",
                2000, 10000, false, "", "", "", "", "", "PKCS12", "", "", "PKCS12",
                true, 1, 1, "direct", "", 9050);
        mpc = new KfeVaultMeshMpcKeyAdapter(provider);
        // Constructors deliberately retain their unavailable defaults.
    }

    @ParameterizedTest @EnumSource(Root.class)
    void missingInjectionRejectsBeforeProviderResolutionOrTransport(Root root) {
        assertThatThrownBy(() -> execute(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
        verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainRejectsBeforeProviderResolutionOrTransport(Root root) {
        inject();
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        assertThatThrownBy(() -> execute(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void storageOutageRejectsBeforeProviderResolutionOrTransport(Root root) {
        inject();
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> execute(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void settersRejectNullAndKeepUnavailableGuard() {
        assertThatNullPointerException().isThrownBy(() -> quorum.setMaintenanceGuard(null));
        assertThatNullPointerException().isThrownBy(() -> mpc.setMaintenanceGuard(null));
        assertThatThrownBy(() -> execute(Root.THRESHOLD)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThatThrownBy(() -> execute(Root.MPC)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
    }

    @Test
    void invalidQuorumInputsRemainPureWithoutAdmission() {
        assertThatIllegalArgumentException().isThrownBy(() -> quorum.requireThresholdConsensus(null));
        assertThatIllegalArgumentException().isThrownBy(() -> quorum.requireThresholdConsensus(
                new FinancialQuorumPort.Proposal(proposal.proposalHash(), proposal.constitutionHash(), 1,
                        Instant.now().minusSeconds(60), Instant.now().minusSeconds(30))));
        assertThatIllegalArgumentException().isThrownBy(() -> quorum.requireHealthyUnanimousConsensus("invalid"));
        noEffects();
        verifyNoInteractions(store);
    }

    @Test
    void activeMpcReplyRemainsUncertainAndAdmissionPrecedesProviderResolution() {
        active();
        prepareMpc();
        assertThat(execute(Root.MPC)).isEqualTo(KEY);
        var order = inOrder(store, provider, port);
        order.verify(store).admit("vault.mpc.keygen");
        order.verify(provider).getIfAvailable();
        order.verify(port).getUsersDepositAddress();
        order.verify(store).resolve(admission.id(), false);
        neverComplete();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void activeProviderErrorsRemainUncertain(Root root) {
        active();
        if (root == Root.MPC) {
            when(provider.getIfAvailable()).thenReturn(port);
            when(port.getUsersDepositAddress()).thenThrow(new IllegalStateException("synthetic provider outage"));
        } else {
            when(transport.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new IllegalStateException("synthetic transport outage"));
        }
        assertThatThrownBy(() -> execute(root)).isInstanceOf(IllegalStateException.class);
        verify(store).resolve(admission.id(), false);
        neverComplete();
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"THRESHOLD", "LEGACY"})
    void activeAcceptedQuorumReplyRemainsUncertain(Root root) throws Exception {
        active();
        when(transport.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"output_pubkey\":\"" + KEY + "\"}"));
        if (root == Root.LEGACY) {
            when(transport.exchange(eq(BASE + "/v1/financial-quorum/context"), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(String.class))).thenReturn(ResponseEntity.ok(
                            "{\"constitution_hash\":\"" + proposal.constitutionHash() + "\",\"constitution_epoch\":1}"));
        }
        var response = mapper.createObjectNode();
        response.put("decision", "ACCEPTED");
        response.put("proposal_hash", proposal.proposalHash());
        response.put("constitution_hash", proposal.constitutionHash());
        response.put("constitution_epoch", proposal.constitutionEpoch());
        response.put("configured_members", 1);
        response.put("required_threshold", 1);
        response.put("verifying_key", KEY);
        response.put("aggregate_proof", "cd".repeat(64));
        response.put("decided_at_epoch_ms", Instant.now().toEpochMilli());
        response.putArray("accepted_members").add("synthetic-member");
        when(transport.postForObject(eq(BASE + "/v1/financial-quorum"), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> request = invocation.getArgument(1);
                    Map<String, Object> body = (Map<String, Object>) request.getBody();
                    String canonical = "kerosene-financial-quorum-v1|" + body.get("proposal_hash") + "|"
                            + body.get("constitution_hash") + "|" + body.get("constitution_epoch") + "|"
                            + body.get("submitted_at_epoch_ms") + "|" + body.get("expires_at_epoch_ms");
                    byte[] digest = MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
                    response.put("signed_digest", HexFormat.of().formatHex(digest));
                    return response.toString();
                });
        // Admission test only: synthetic proof acceptance is not cryptographic verification evidence.
        try (var verifier = mockStatic(Bip340Verifier.class)) {
            verifier.when(() -> Bip340Verifier.verify(any(byte[].class), any(byte[].class), any(byte[].class)))
                    .thenReturn(true);
            Object result = execute(root);
            if (root == Root.THRESHOLD) {
                assertThat(((FinancialQuorumPort.QuorumDecision) result).decision())
                        .isEqualTo(FinancialQuorumPort.Decision.ACCEPTED);
            } else {
                assertThat(((FinancialQuorumPort.Result) result).acceptedNodes()).isEqualTo(1);
            }
        }
        var order = inOrder(store, transport);
        order.verify(store).admit(root == Root.THRESHOLD ? "vault.quorum.threshold" : "vault.quorum.legacy");
        if (root == Root.LEGACY) {
            order.verify(transport).exchange(eq(BASE + "/v1/financial-quorum/context"),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        }
        order.verify(transport).exchange(eq(BASE + "/v1/bitcoin/deposit?bucket=USERS"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        order.verify(transport).postForObject(eq(BASE + "/v1/financial-quorum"), any(HttpEntity.class), eq(String.class));
        order.verify(store).resolve(admission.id(), false);
        verify(store, times(1)).admit(anyString());
        neverComplete();
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"THRESHOLD", "LEGACY"})
    void activePostFailureRemainsUncertainAfterGroupKeyAgreement(Root root) {
        active();
        String reply = "{\"output_pubkey\":\"" + KEY + "\",\"constitution_hash\":\""
                + proposal.constitutionHash() + "\",\"constitution_epoch\":1}";
        when(transport.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(reply));
        when(transport.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new IllegalStateException("synthetic POST outage"));
        assertThatThrownBy(() -> execute(root)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POST failed");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        neverComplete();
    }

    @Test
    void admittedParentMayReachMpcDuringDrainButCannotClaimCompletion() {
        active();
        prepareMpc();
        assertThat(guard.executeMutation("synthetic.parent", () -> {
            when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
            return mpc.keygenWallet(UUID.randomUUID(), 7L);
        })).isEqualTo(KEY);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        neverComplete();
        assertThatThrownBy(() -> execute(Root.MPC)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(port, times(1)).getUsersDepositAddress();
    }

    private Object execute(Root root) {
        return switch (root) {
            case THRESHOLD -> quorum.requireThresholdConsensus(proposal);
            case LEGACY -> quorum.requireHealthyUnanimousConsensus(proposal.proposalHash());
            case MPC -> mpc.keygenWallet(UUID.randomUUID(), 7L);
        };
    }

    private void inject() { quorum.setMaintenanceGuard(guard); mpc.setMaintenanceGuard(guard); }
    private void active() { inject(); when(store.admit(anyString())).thenReturn(admission); }
    private void prepareMpc() {
        when(provider.getIfAvailable()).thenReturn(port);
        when(port.getUsersDepositAddress()).thenReturn(
                new VaultMeshDepositInfo("synthetic-address", null, "taproot", KEY, KEY, "testnet"));
    }
    private void noEffects() { verifyNoInteractions(transport, provider, port); }
    private void neverComplete() { verify(store, never()).resolve(any(), eq(true)); }
}
