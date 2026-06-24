package source.kfe.dto;

import jakarta.validation.constraints.Size;

public record KfeUpdateWalletRequest(
        @Size(max = 96, message = "Wallet label must have at most 96 characters.")
        String label) {
}
