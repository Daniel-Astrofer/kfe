package com.kerosene.kfe.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit event for structured JSON logging to the "kerosene.audit.financial" stream.
 *
 * <p>NEVER log: bearer token, macaroon, full invoice, preimage, PSBT, raw transaction, PII.
 * referenceHash carries a txid or payment_hash hash, never raw values.
 */
public record KfeAuditEvent(
        UUID eventId,
        String eventType,
        UUID transactionId,
        UUID walletId,
        String principalId,
        String previousStatus,
        String newStatus,
        long amountSats,
        long feeSats,
        String network,
        String rail,
        String referenceHash,   // txid hash or payment_hash hash, never raw
        String requestId,
        String correlationId,
        Instant occurredAt) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID eventId = UUID.randomUUID();
        private String eventType;
        private UUID transactionId;
        private UUID walletId;
        private String principalId;
        private String previousStatus;
        private String newStatus;
        private long amountSats;
        private long feeSats;
        private String network;
        private String rail;
        private String referenceHash;
        private String requestId;
        private String correlationId;
        private Instant occurredAt = Instant.now();

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder transactionId(UUID transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder walletId(UUID walletId) {
            this.walletId = walletId;
            return this;
        }

        public Builder principalId(String principalId) {
            this.principalId = principalId;
            return this;
        }

        public Builder previousStatus(String previousStatus) {
            this.previousStatus = previousStatus;
            return this;
        }

        public Builder newStatus(String newStatus) {
            this.newStatus = newStatus;
            return this;
        }

        public Builder amountSats(long amountSats) {
            this.amountSats = amountSats;
            return this;
        }

        public Builder feeSats(long feeSats) {
            this.feeSats = feeSats;
            return this;
        }

        public Builder network(String network) {
            this.network = network;
            return this;
        }

        public Builder rail(String rail) {
            this.rail = rail;
            return this;
        }

        public Builder referenceHash(String referenceHash) {
            this.referenceHash = referenceHash;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public KfeAuditEvent build() {
            return new KfeAuditEvent(
                    eventId,
                    eventType,
                    transactionId,
                    walletId,
                    principalId,
                    previousStatus,
                    newStatus,
                    amountSats,
                    feeSats,
                    network,
                    rail,
                    referenceHash,
                    requestId,
                    correlationId,
                    occurredAt);
        }
    }
}
