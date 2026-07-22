package source.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeClassifyTaxEventRequest;
import source.kfe.dto.KfeTaxEventResponse;
import source.kfe.dto.KfeTaxEventsExportResponse;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeTaxEventClassificationEntity;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.repository.KfeTaxEventClassificationRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KfeTaxEventService {

    private static final String EDUCATIONAL_NOTICE =
            "Eventos derivados do KFE para apoio operacional. Não substituem orientação fiscal profissional.";

    private final KfeTransactionRepository transactionRepository;
    private final KfeTaxEventClassificationRepository classificationRepository;

    public KfeTaxEventService(
            KfeTransactionRepository transactionRepository,
            KfeTaxEventClassificationRepository classificationRepository) {
        this.transactionRepository = transactionRepository;
        this.classificationRepository = classificationRepository;
    }

    @Transactional(readOnly = true)
    public List<KfeTaxEventResponse> list(Long userId) {
        Map<String, KfeTaxEventClassificationEntity> classifications = classifications(userId);
        return transactionRepository.findTop200ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(transaction -> toTaxEvent(transaction, classifications))
                .toList();
    }

    @Transactional(readOnly = true)
    public KfeTaxEventsExportResponse export(Long userId, String format) {
        String normalizedFormat = normalizeFormat(format);
        List<KfeTaxEventResponse> events = list(userId);
        String filename = "kerosene-kfe-tax-events." + normalizedFormat;
        String content = "csv".equals(normalizedFormat) ? csv(events) : jsonLike(events);
        return new KfeTaxEventsExportResponse(
                normalizedFormat,
                filename,
                EDUCATIONAL_NOTICE,
                content,
                events);
    }

    @Transactional
    public KfeTaxEventResponse classify(Long userId, String eventId, KfeClassifyTaxEventRequest request) {
        String cleanEventId = requireText(eventId, "eventId");
        String classification = requireText(request != null ? request.classification() : null, "classification")
                .toUpperCase(Locale.ROOT);
        KfeTransactionEntity transaction = transactionRepository.findByIdAndUserId(resolveTransactionId(cleanEventId), userId)
                .orElseThrow(() -> new IllegalArgumentException("KFE tax event not found."));
        KfeTaxEventClassificationEntity entity = classificationRepository
                .findByUserIdAndEventId(userId, cleanEventId)
                .orElseGet(KfeTaxEventClassificationEntity::new);
        entity.setUserId(userId);
        entity.setEventId(cleanEventId);
        entity.setClassification(classification);
        classificationRepository.save(entity);
        return toTaxEvent(transaction, Map.of(cleanEventId, entity));
    }

    private KfeTaxEventResponse toTaxEvent(
            KfeTransactionEntity transaction,
            Map<String, KfeTaxEventClassificationEntity> classifications) {
        String eventId = transaction.getId().toString();
        String eventType = eventType(transaction);
        String classification = classifications.containsKey(eventId)
                ? classifications.get(eventId).getClassification()
                : defaultClassification(eventType);
        UUID walletId = transaction.getSourceWalletId() != null
                ? transaction.getSourceWalletId()
                : transaction.getDestinationWalletId();
        return new KfeTaxEventResponse(
                eventId,
                eventType,
                "BTC",
                quantity(transaction),
                classification,
                sourceRef(transaction),
                transaction.getCreatedAt(),
                walletId,
                transaction.getSourceWalletId(),
                walletId,
                transaction.getCreatedAt() != null ? transaction.getCreatedAt().plusDays(365) : null);
    }

    private Map<String, KfeTaxEventClassificationEntity> classifications(Long userId) {
        return classificationRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        KfeTaxEventClassificationEntity::getEventId,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private String eventType(KfeTransactionEntity transaction) {
        if (transaction.getNetworkFeeSats() > 0 || transaction.getKeroseneFeeSats() > 0) {
            if (transaction.getDirection() == KfeDirection.OUTBOUND) {
                return "WITHDRAWAL";
            }
        }
        if (transaction.getDirection() == KfeDirection.INBOUND) {
            return "DEPOSIT_EXTERNAL";
        }
        if (transaction.getDirection() == KfeDirection.OUTBOUND) {
            return "WITHDRAWAL";
        }
        if (transaction.getDirection() == KfeDirection.INTERNAL) {
            return "SELF_TRANSFER";
        }
        return "KFE_TRANSACTION";
    }

    private String defaultClassification(String eventType) {
        return switch (eventType) {
            case "DEPOSIT_EXTERNAL" -> "INCOME_OR_TRANSFER_IN";
            case "WITHDRAWAL" -> "TRANSFER_OUT_OR_SPEND";
            case "SELF_TRANSFER" -> "SELF_TRANSFER";
            default -> "UNCLASSIFIED";
        };
    }

    private long quantity(KfeTransactionEntity transaction) {
        long value = transaction.getReceiverAmountSats() > 0
                ? transaction.getReceiverAmountSats()
                : transaction.getGrossAmountSats();
        return Math.max(0L, value);
    }

    private String sourceRef(KfeTransactionEntity transaction) {
        if (hasText(transaction.getBlockchainTxid())) {
            return transaction.getBlockchainTxid();
        }
        if (hasText(transaction.getPaymentHash())) {
            return transaction.getPaymentHash();
        }
        if (hasText(transaction.getProviderReference())) {
            return transaction.getProviderReference();
        }
        return transaction.getId().toString();
    }

    private UUID resolveTransactionId(String eventId) {
        String normalized = eventId;
        int separator = normalized.indexOf(':');
        if (separator > 0) {
            normalized = normalized.substring(0, separator);
        }
        return UUID.fromString(normalized);
    }

    private String normalizeFormat(String format) {
        String normalized = hasText(format) ? format.trim().toLowerCase(Locale.ROOT) : "json";
        return "csv".equals(normalized) ? "csv" : "json";
    }

    private String csv(List<KfeTaxEventResponse> events) {
        StringBuilder builder = new StringBuilder("id,eventType,asset,quantitySats,classification,sourceRef,createdAt,walletId\n");
        for (KfeTaxEventResponse event : events) {
            builder.append(csvCell(event.id())).append(',')
                    .append(csvCell(event.eventType())).append(',')
                    .append(csvCell(event.asset())).append(',')
                    .append(event.quantitySats()).append(',')
                    .append(csvCell(event.classification())).append(',')
                    .append(csvCell(event.sourceRef())).append(',')
                    .append(csvCell(String.valueOf(event.createdAt()))).append(',')
                    .append(csvCell(String.valueOf(event.walletId())))
                    .append('\n');
        }
        return builder.toString();
    }

    private String jsonLike(List<KfeTaxEventResponse> events) {
        return events.stream()
                .map(event -> "{\"id\":\"" + event.id() + "\",\"eventType\":\"" + event.eventType()
                        + "\",\"quantitySats\":" + event.quantitySats() + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String csvCell(String value) {
        String clean = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + clean + "\"";
    }

    private String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("KFE tax event " + field + " is required.");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
