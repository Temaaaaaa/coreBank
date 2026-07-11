package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.mapper.TransactionMapper;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MoneyOperationServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CLOCK_TIME = Instant.parse("2026-07-11T18:00:00.123456789Z");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-11T18:00:00.123456Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountTransactionRepository transactionRepository;

    private MoneyOperationService moneyOperationService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(CLOCK_TIME, ZoneOffset.UTC);
        moneyOperationService = new MoneyOperationService(
                accountRepository,
                transactionRepository,
                new TransactionMapper(),
                fixedClock
        );
    }

    @Test
    void depositsUsingLockedAccountAndSavesDepositTransaction() {
        AccountEntity account = account();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = moneyOperationService.deposit(
                ACCOUNT_ID,
                new DepositRequest(new BigDecimal("1500.00"), "  Initial deposit  ")
        );

        ArgumentCaptor<AccountTransactionEntity> captor = ArgumentCaptor.forClass(AccountTransactionEntity.class);
        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(transactionRepository).save(captor.capture());
        verify(accountRepository, never()).save(any(AccountEntity.class));
        AccountTransactionEntity transaction = captor.getValue();

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(account.getUpdatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(transaction.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(transaction.getSourceAccount()).isNull();
        assertThat(transaction.getTargetAccount()).isSameAs(account);
        assertThat(transaction.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(transaction.getCreatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(response.amount()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(response.currency()).isEqualTo(Currency.RUB);
        assertThat(response.sourceAccountId()).isNull();
        assertThat(response.targetAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.description()).isEqualTo("Initial deposit");
    }

    @Test
    void convertsBlankDescriptionToNull() {
        AccountEntity account = account();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = moneyOperationService.deposit(
                ACCOUNT_ID,
                new DepositRequest(new BigDecimal("10.00"), "   ")
        );

        assertThat(response.description()).isNull();
    }

    @Test
    void throwsWhenAccountDoesNotExist() {
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moneyOperationService.deposit(
                ACCOUNT_ID,
                new DepositRequest(new BigDecimal("10.00"), null)
        )).isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void rejectsInactiveAccountWithoutSavingTransaction() {
        AccountEntity inactiveAccount = org.mockito.Mockito.mock(AccountEntity.class);
        BusinessRuleException inactive = new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active");
        doThrow(inactive).when(inactiveAccount).deposit(new BigDecimal("10.00"), TRANSACTION_TIME);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(inactiveAccount));

        assertThatThrownBy(() -> moneyOperationService.deposit(
                ACCOUNT_ID,
                new DepositRequest(new BigDecimal("10.00"), null)
        )).isSameAs(inactive);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void rejectsNullZeroAndNegativeAmountsBeforeLocking() {
        assertInvalidAmount(null, "INVALID_AMOUNT");
        assertInvalidAmount(new BigDecimal("0.00"), "INVALID_AMOUNT");
        assertInvalidAmount(new BigDecimal("-1.00"), "INVALID_AMOUNT");
    }

    @Test
    void rejectsAmountWithInvalidScaleBeforeLocking() {
        assertInvalidAmount(new BigDecimal("1.001"), "INVALID_AMOUNT_SCALE");
    }

    private void assertInvalidAmount(BigDecimal amount, String expectedCode) {
        assertThatThrownBy(() -> moneyOperationService.deposit(
                ACCOUNT_ID,
                new DepositRequest(amount, null)
        )).isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(transactionRepository, never()).save(any());
    }

    private AccountEntity account() {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(),
                "Artem",
                "Ivanov",
                "money-operation-owner@example.com",
                TRANSACTION_TIME,
                TRANSACTION_TIME
        );
        return new AccountEntity(
                ACCOUNT_ID,
                "12345678901234567890",
                customer,
                Currency.RUB,
                TRANSACTION_TIME,
                TRANSACTION_TIME
        );
    }
}
