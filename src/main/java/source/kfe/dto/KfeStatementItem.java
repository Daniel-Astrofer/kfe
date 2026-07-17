package source.kfe.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Recent-activity row for dashboard / local merge.
 *
 * <p><b>Client merge contract</b>
 * <ul>
 *   <li>Stable identity: {@code id} == {@code transactionId} (never recreate on status change).
 *   <li>Sort key: {@code createdAt} DESC, then {@code transactionId} DESC — never reorder by
 *       {@code updatedAt}.
 *   <li>On push/pull of the same {@code transactionId}: update status/payload/updatedAt in place;
 *       keep {@code createdAt} from the first time the row was known.
 *   <li>{@code displayStatus}: PENDING | CONFIRMED | FAILED for badges.
 * </ul>
 */
public record KfeStatementItem(
        UUID id,
        UUID transactionId,
        UUID walletId,
        /** Raw ledger status: INTENT, EXECUTING, SETTLED, FAILED, … */
        String status,
        /** Coarse UI badge: PENDING | CONFIRMED | FAILED */
        String displayStatus,
        String displayPayloadJson,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {
}
