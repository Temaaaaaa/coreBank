package artem.dev.corebank.account.dto;

import artem.dev.corebank.account.entity.Currency;
import jakarta.validation.constraints.NotNull;

public record OpenAccountRequest(
        @NotNull Currency currency
) {
}
