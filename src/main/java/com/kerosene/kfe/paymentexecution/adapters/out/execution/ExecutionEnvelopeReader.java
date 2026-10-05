package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionIntentBinding;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Reads the persisted wire envelope without trusting Jackson coercions or the shared mapper. */
public final class ExecutionEnvelopeReader {

    private final ObjectMapper mapper;

    public ExecutionEnvelopeReader() {
        mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    public Envelope read(String json, String expectedSha256) {
        verifyHash(json, expectedSha256);
        JsonNode root;
        try (JsonParser parser = mapper.createParser(json)) {
            root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalid();
            }
        } catch (IOException exception) {
            // Parser diagnostics can include the financial payload. Do not retain their cause.
            throw invalid();
        }

        ExecutionIntentBinding binding = new ExecutionIntentBinding(
                uuid(requiredText(root, "transactionId")),
                requiredLong(root, "userId"),
                requiredText(root, "idempotencyKey"),
                rail(requiredText(root, "rail")),
                direction(requiredText(root, "direction")),
                optionalUuid(root, "sourceWalletId"),
                optionalUuid(root, "destinationWalletId"),
                requiredLong(root, "amountSats"),
                requiredLong(root, "networkFeeSats"),
                requiredLong(root, "totalDebitSats"),
                optionalText(root, "externalReference"),
                optionalText(root, "memo"),
                requiredText(root, "quorumProposalHash"));

        Long rate = aliasedPositiveLong(root, "feeRateSatsPerVbyte", "feeRateSatPerVbyte");
        Long target = aliasedPositiveLong(root, "feeTargetBlocks", "confirmationTarget");
        if (target != null && target > Integer.MAX_VALUE) {
            throw invalid();
        }
        String quoteId = optionalText(root, "quoteId");
        quoteId = quoteId == null || quoteId.isBlank() ? null : quoteId.trim();
        return new Envelope(binding, rate, target == null ? null : target.intValue(),
                optionalPositiveLong(root, "estimatedVbytes"), quoteId);
    }

    private static void verifyHash(String json, String expectedSha256) {
        if (json == null || expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw invalid();
        }
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(expectedSha256))) {
                throw invalid();
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : requiredText(root, name);
    }

    private static long requiredLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid();
        }
        return value.longValue();
    }

    private static UUID optionalUuid(JsonNode root, String name) {
        String value = optionalText(root, name);
        return value == null ? null : uuid(value);
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw invalid();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static PaymentRail rail(String value) {
        try {
            return PaymentRail.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static PaymentDirection direction(String value) {
        try {
            return PaymentDirection.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static Long aliasedPositiveLong(JsonNode root, String name, String alias) {
        Long primary = optionalPositiveLong(root, name);
        Long alternate = optionalPositiveLong(root, alias);
        if (primary != null && alternate != null && !Objects.equals(primary, alternate)) {
            throw invalid();
        }
        return primary == null ? alternate : primary;
    }

    private static Long optionalPositiveLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        long number;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            number = value.longValue();
        } else if (value.isTextual()) {
            String text = value.textValue().trim();
            if (!text.matches("[+-]?[0-9]+")) {
                throw invalid();
            }
            try {
                number = Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        } else {
            throw invalid();
        }
        if (number <= 0L) {
            throw invalid();
        }
        return number;
    }

    private static InvalidExecutionEnvelope invalid() {
        return new InvalidExecutionEnvelope();
    }

    public record Envelope(ExecutionIntentBinding binding, Long feeRateSatsPerVbyte,
                           Integer feeTargetBlocks, Long estimatedVbytes, String quoteId) {
        @Override
        public String toString() {
            return "Envelope[binding=" + binding + ", references=REDACTED]";
        }
    }

    public static final class InvalidExecutionEnvelope extends IllegalArgumentException {
        public InvalidExecutionEnvelope() {
            super("Execution envelope is invalid.");
        }
    }
}
