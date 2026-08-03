package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.security.CryptoPurpose;
import com.kerosene.common.security.EncryptedValue;
import com.kerosene.common.security.StringColumnCryptoPort;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Persists the exact externally executable payload before the first provider call. */
@Service
public class KfePreparedExecutionService {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private final KfeExecutionOutboxRepository outboxRepository;
    private final StringColumnCryptoPort cryptoPort;
    private final KfeHashService hashService;
    private final ObjectMapper objectMapper;

    public KfePreparedExecutionService(
            KfeExecutionOutboxRepository outboxRepository,
            StringColumnCryptoPort cryptoPort,
            KfeHashService hashService,
            ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.cryptoPort = cryptoPort;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
    }

    public enum PayloadType {
        ONCHAIN,
        LIGHTNING
    }

    public record StoredPayload<T>(T payload, String executionReference) {
    }

    private record Envelope(
            int schemaVersion,
            UUID outboxId,
            UUID transactionId,
            String operation,
            PayloadType payloadType,
            JsonNode payload) {
    }

    @Transactional
    public <T> Optional<StoredPayload<T>> load(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String operation,
            PayloadType payloadType,
            Class<T> payloadClass) {
        KfeExecutionOutboxEntity outbox = lockedOwnedOutbox(
                outboxId, transactionId, claimToken, operation);
        if (outbox.getPreparedPayloadCiphertext() == null) {
            if (outbox.getPreparedPayloadHash() != null || outbox.getExecutionReference() != null) {
                throw new IllegalStateException("Prepared execution fields are only partially populated.");
            }
            return Optional.empty();
        }
        return Optional.of(decrypt(outbox, payloadType, payloadClass));
    }

    @Transactional
    public <T> StoredPayload<T> persistIfAbsent(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String operation,
            PayloadType payloadType,
            T payload,
            String executionReference,
            Class<T> payloadClass) {
        if (payload == null) {
            throw new IllegalArgumentException("Prepared execution payload is required.");
        }
        String normalizedReference = normalizeReference(executionReference);
        KfeExecutionOutboxEntity outbox = lockedOwnedOutbox(
                outboxId, transactionId, claimToken, operation);
        if (outbox.getPreparedPayloadCiphertext() != null) {
            return decrypt(outbox, payloadType, payloadClass);
        }

        try {
            Envelope envelope = new Envelope(
                    SCHEMA_VERSION,
                    outboxId,
                    transactionId,
                    operation,
                    payloadType,
                    objectMapper.valueToTree(payload));
            byte[] plaintext = objectMapper.writeValueAsBytes(envelope);
            if (plaintext.length == 0 || plaintext.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Prepared execution payload size is invalid.");
            }
            EncryptedValue encrypted = cryptoPort.encrypt(
                    CryptoPurpose.COLUMN_ENCRYPTION,
                    plaintext,
                    associatedData(outbox));
            outbox.setPreparedPayloadCiphertext(objectMapper.writeValueAsString(encrypted));
            outbox.setPreparedPayloadHash(hashService.sha256(new String(plaintext, StandardCharsets.UTF_8)));
            outbox.setExecutionReference(normalizedReference);
            outboxRepository.saveAndFlush(outbox);
            return new StoredPayload<>(payload, normalizedReference);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not persist prepared external execution.", exception);
        }
    }

    private <T> StoredPayload<T> decrypt(
            KfeExecutionOutboxEntity outbox,
            PayloadType expectedType,
            Class<T> payloadClass) {
        try {
            if (outbox.getPreparedPayloadHash() == null || outbox.getExecutionReference() == null) {
                throw new IllegalStateException("Prepared execution fields are only partially populated.");
            }
            EncryptedValue encrypted = objectMapper.readValue(
                    outbox.getPreparedPayloadCiphertext(), EncryptedValue.class);
            byte[] plaintext = cryptoPort.decrypt(
                    CryptoPurpose.COLUMN_ENCRYPTION,
                    encrypted,
                    associatedData(outbox));
            if (plaintext.length == 0 || plaintext.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("Prepared execution plaintext size is invalid.");
            }
            String actualHash = hashService.sha256(new String(plaintext, StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(
                    actualHash.getBytes(StandardCharsets.US_ASCII),
                    outbox.getPreparedPayloadHash().getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Prepared execution plaintext hash mismatch.");
            }
            Envelope envelope = objectMapper.readValue(plaintext, Envelope.class);
            if (envelope.schemaVersion() != SCHEMA_VERSION
                    || !outbox.getId().equals(envelope.outboxId())
                    || !outbox.getTransactionId().equals(envelope.transactionId())
                    || !outbox.getOperation().equals(envelope.operation())
                    || expectedType != envelope.payloadType()) {
                throw new IllegalStateException("Prepared execution envelope context mismatch.");
            }
            T payload = objectMapper.treeToValue(envelope.payload(), payloadClass);
            return new StoredPayload<>(payload, outbox.getExecutionReference());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt prepared external execution.", exception);
        }
    }

    private KfeExecutionOutboxEntity lockedOwnedOutbox(
            UUID outboxId,
            UUID transactionId,
            UUID claimToken,
            String operation) {
        if (outboxId == null || transactionId == null || claimToken == null
                || operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Outbox execution ownership context is required.");
        }
        KfeExecutionOutboxEntity outbox = outboxRepository.findByIdForUpdate(outboxId)
                .orElseThrow(() -> new IllegalStateException("Execution outbox entry was not found."));
        if (!transactionId.equals(outbox.getTransactionId()) || !operation.equals(outbox.getOperation())) {
            throw new IllegalStateException("Execution outbox context does not match the prepared operation.");
        }
        if (!"PROCESSING".equals(outbox.getStatus())
                || !claimToken.equals(outbox.getClaimToken())
                || outbox.getLeaseExpiresAt() == null
                || !outbox.getLeaseExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new KfeExecutionClaimLostException(outboxId);
        }
        return outbox;
    }

    private byte[] associatedData(KfeExecutionOutboxEntity outbox) {
        return String.join("|",
                        "financial.financial_execution_outbox",
                        "prepared_payload_ciphertext",
                        outbox.getId().toString(),
                        outbox.getTransactionId().toString(),
                        outbox.getOperation(),
                        "v1")
                .getBytes(StandardCharsets.UTF_8);
    }

    private String normalizeReference(String executionReference) {
        String value = executionReference == null ? "" : executionReference.trim();
        if (value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Prepared execution reference must be 1 to 255 characters.");
        }
        return value;
    }
}
