package artem.dev.corebank.transaction.entity;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.customer.entity.CustomerEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTransactionEntityTransferTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-23T10:00:00Z");

    @Test
    void createsTransferWithCorrectAccountsCurrencyAndFields() {
        AccountEntity source = account("00000000-0000-0000-0000-000000000001", Currency.RUB);
        AccountEntity target = account("00000000-0000-0000-0000-000000000002", Currency.RUB);
        UUID transactionId = UUID.randomUUID();

        AccountTransactionEntity transaction = AccountTransactionEntity.transfer(
                transactionId,
                new BigDecimal("500.00"),
                source,
                target,
                "Transfer between accounts",
                CREATED_AT
        );

        assertThat(transaction.getId()).isEqualTo(transactionId);
        assertThat(transaction.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transaction.getAmount()).isEqualTo(new BigDecimal("500.00"));
        assertThat(transaction.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(transaction.getSourceAccount()).isSameAs(source);
        assertThat(transaction.getTargetAccount()).isSameAs(target);
        assertThat(transaction.getDescription()).isEqualTo("Transfer between accounts");
        assertThat(transaction.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rejectsMissingSameCurrencyMismatchedAndNonPositiveArguments() {
        AccountEntity source = account("00000000-0000-0000-0000-000000000001", Currency.RUB);
        AccountEntity target = account("00000000-0000-0000-0000-000000000002", Currency.RUB);
        AccountEntity usdTarget = account("00000000-0000-0000-0000-000000000003", Currency.USD);

        assertThatThrownBy(() -> transfer(null, target, new BigDecimal("1.00"), CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transfer(source, null, new BigDecimal("1.00"), CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> transfer(source, source, new BigDecimal("1.00"), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(source, usdTarget, new BigDecimal("1.00"), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(source, target, null, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(source, target, BigDecimal.ZERO, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transfer(source, target, BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
    }

    private AccountTransactionEntity transfer(
            AccountEntity source,
            AccountEntity target,
            BigDecimal amount,
            Instant createdAt
    ) {
        return AccountTransactionEntity.transfer(
                UUID.randomUUID(), amount, source, target, null, createdAt
        );
    }

    private AccountEntity account(String id, Currency currency) {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(), "Transfer", "Owner", UUID.randomUUID() + "@example.com",
                CREATED_AT, CREATED_AT
        );
        return new AccountEntity(
                UUID.fromString(id),
                String.format("%020d", Math.abs(UUID.fromString(id).getLeastSignificantBits()) + 1),
                customer,
                currency,
                CREATED_AT,
                CREATED_AT
        );
    }
}
