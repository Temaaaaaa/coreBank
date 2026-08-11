package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.mapper.TransactionMapper;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionLookupServiceTest {

    private static final UUID TRANSACTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountTransactionRepository transactionRepository;

    private MoneyOperationService service;

    @BeforeEach
    void setUp() {
        service = new MoneyOperationService(
                accountRepository,
                transactionRepository,
                new TransactionMapper(),
                Clock.fixed(CREATED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void returnsMappedTransactionWithSourceAndTargetAccountIds() {
        AccountEntity source = account(SOURCE_ID, "12345678901234567891");
        AccountEntity target = account(TARGET_ID, "12345678901234567892");
        AccountTransactionEntity transaction = AccountTransactionEntity.transfer(
                TRANSACTION_ID,
                new BigDecimal("250.00"),
                source,
                target,
                "Rent",
                CREATED_AT
        );
        when(transactionRepository.findById(TRANSACTION_ID)).thenReturn(Optional.of(transaction));

        TransactionResponse response = service.getTransactionById(TRANSACTION_ID);

        assertThat(response.id()).isEqualTo(TRANSACTION_ID);
        assertThat(response.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(response.amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(response.currency()).isEqualTo(Currency.RUB);
        assertThat(response.sourceAccountId()).isEqualTo(SOURCE_ID);
        assertThat(response.targetAccountId()).isEqualTo(TARGET_ID);
        assertThat(response.description()).isEqualTo("Rent");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        verify(transactionRepository).findById(TRANSACTION_ID);
    }

    @Test
    void throwsTransactionNotFoundWhenIdDoesNotExist() {
        when(transactionRepository.findById(TRANSACTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransactionById(TRANSACTION_ID))
                .isInstanceOfSatisfying(ResourceNotFoundException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("TRANSACTION_NOT_FOUND");
                    assertThat(exception.getMessage()).isEqualTo(
                            "Transaction with id " + TRANSACTION_ID + " was not found"
                    );
                });
        verify(transactionRepository).findById(TRANSACTION_ID);
    }

    private AccountEntity account(UUID accountId, String accountNumber) {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(),
                "Transaction",
                "Owner",
                accountId + "@example.com",
                CREATED_AT,
                CREATED_AT
        );
        return new AccountEntity(
                accountId,
                accountNumber,
                customer,
                Currency.RUB,
                CREATED_AT,
                CREATED_AT
        );
    }
}
