package artem.dev.corebank.transaction.dto;

import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.transaction.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        TransactionType type,
        BigDecimal amount,
        Currency currency,
        UUID sourceAccountId,
        UUID targetAccountId,
        String description,
        Instant createdAt
) {
}
