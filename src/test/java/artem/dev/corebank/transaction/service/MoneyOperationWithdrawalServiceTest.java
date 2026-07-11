package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
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
class MoneyOperationWithdrawalServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CLOCK_TIME = Instant.parse("2026-07-12T12:00:00.123456789Z");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-12T12:00:00.123456Z");

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
                Clock.fixed(CLOCK_TIME, ZoneOffset.UTC)
        );
    }

    @Test
    void withdrawsUsingLockedAccountAndSavesWithdrawalTransaction() {
        AccountEntity account = fundedAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = service.withdraw(
                ACCOUNT_ID, new WithdrawalRequest(new BigDecimal("250.00"), "  ATM withdrawal  ")
        );

        ArgumentCaptor<AccountTransactionEntity> captor = ArgumentCaptor.forClass(AccountTransactionEntity.class);
        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(transactionRepository).save(captor.capture());
        verify(accountRepository, never()).save(any(AccountEntity.class));
        AccountTransactionEntity transaction = captor.getValue();

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("750.00"));
        assertThat(account.getUpdatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(transaction.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(transaction.getSourceAccount()).isSameAs(account);
        assertThat(transaction.getTargetAccount()).isNull();
        assertThat(transaction.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(transaction.getCreatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(response.sourceAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.targetAccountId()).isNull();
        assertThat(response.description()).isEqualTo("ATM withdrawal");
    }

    @Test
    void convertsBlankDescriptionToNull() {
        AccountEntity account = fundedAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = service.withdraw(
                ACCOUNT_ID, new WithdrawalRequest(new BigDecimal("10.00"), "   ")
        );

        assertThat(response.description()).isNull();
    }

    @Test
    void rejectsMissingInactiveAndInsufficientAccountsWithoutTransaction() {
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.withdraw(
                ACCOUNT_ID, new WithdrawalRequest(new BigDecimal("10.00"), null)
        )).isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));

        org.mockito.Mockito.reset(accountRepository);
        org.mockito.Mockito.reset(transactionRepository);
        AccountEntity inactive = org.mockito.Mockito.mock(AccountEntity.class);
        BusinessRuleException inactiveError = new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active");
        doThrow(inactiveError).when(inactive).withdraw(new BigDecimal("10.00"), TRANSACTION_TIME);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.withdraw(
                ACCOUNT_ID, new WithdrawalRequest(new BigDecimal("10.00"), null)
        )).isSameAs(inactiveError);
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));

        org.mockito.Mockito.reset(accountRepository);
        org.mockito.Mockito.reset(transactionRepository);
        AccountEntity account = fundedAccount();
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        assertThatThrownBy(() -> service.withdraw(
                ACCOUNT_ID, new WithdrawalRequest(new BigDecimal("1000.01"), null)
        )).isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("1000.00"));
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    @Test
    void rejectsInvalidAmountsBeforeLocking() {
        assertInvalid(null, "INVALID_AMOUNT");
        assertInvalid(new BigDecimal("0.00"), "INVALID_AMOUNT");
        assertInvalid(new BigDecimal("-1.00"), "INVALID_AMOUNT");
        assertInvalid(new BigDecimal("1.001"), "INVALID_AMOUNT_SCALE");
    }

    private void assertInvalid(BigDecimal amount, String code) {
        assertThatThrownBy(() -> service.withdraw(ACCOUNT_ID, new WithdrawalRequest(amount, null)))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(code));
        verify(accountRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    private AccountEntity fundedAccount() {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(), "Test", "Owner", "withdrawal.owner@example.com",
                TRANSACTION_TIME, TRANSACTION_TIME
        );
        AccountEntity account = new AccountEntity(
                ACCOUNT_ID, "12345678901234567890", customer, Currency.RUB,
                TRANSACTION_TIME, TRANSACTION_TIME
        );
        account.deposit(new BigDecimal("1000.00"), TRANSACTION_TIME);
        return account;
    }
}
