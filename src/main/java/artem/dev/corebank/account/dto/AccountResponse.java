package artem.dev.corebank.account.dto;

import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.entity.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        UUID customerId,
        Currency currency,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
