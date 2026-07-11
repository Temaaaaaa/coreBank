package artem.dev.corebank.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record WithdrawalRequest(
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,
        @Size(max = 255)
        String description
) {
}
