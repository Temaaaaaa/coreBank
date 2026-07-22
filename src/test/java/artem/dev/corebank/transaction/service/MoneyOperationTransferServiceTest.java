package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.TransferRequest;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.mapper.TransactionMapper;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MoneyOperationTransferServiceTest {

    private static final UUID LOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CLOCK_TIME = Instant.parse("2026-07-23T10:00:00.123456789Z");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-07-23T10:00:00.123456Z");

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
    void transfersMoneyAndLocksAccountsInUuidOrderWhenSourceIsHigher() {
        AccountEntity source = fundedAccount(HIGH_ID, Currency.RUB, "1000.00");
        AccountEntity target = fundedAccount(LOW_ID, Currency.RUB, "0.00");
        stubLockedAccounts(target, source);
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = service.transfer(request(HIGH_ID, LOW_ID, "250.00", "  Rent  "));

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(LOW_ID);
        order.verify(accountRepository).findByIdForUpdate(HIGH_ID);
        ArgumentCaptor<AccountTransactionEntity> captor = ArgumentCaptor.forClass(AccountTransactionEntity.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        verify(accountRepository, never()).save(any(AccountEntity.class));
        AccountTransactionEntity transaction = captor.getValue();

        assertThat(source.getBalance()).isEqualTo(new BigDecimal("750.00"));
        assertThat(target.getBalance()).isEqualTo(new BigDecimal("250.00"));
        assertThat(source.getUpdatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(target.getUpdatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(transaction.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transaction.getSourceAccount()).isSameAs(source);
        assertThat(transaction.getTargetAccount()).isSameAs(target);
        assertThat(transaction.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(transaction.getCreatedAt()).isEqualTo(TRANSACTION_TIME);
        assertThat(response.sourceAccountId()).isEqualTo(HIGH_ID);
        assertThat(response.targetAccountId()).isEqualTo(LOW_ID);
        assertThat(response.description()).isEqualTo("Rent");
    }

    @Test
    void locksInSameUuidOrderForReverseTransferDirection() {
        AccountEntity source = fundedAccount(LOW_ID, Currency.RUB, "100.00");
        AccountEntity target = fundedAccount(HIGH_ID, Currency.RUB, "100.00");
        stubLockedAccounts(source, target);
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.transfer(request(LOW_ID, HIGH_ID, "10.00", null));

        InOrder order = inOrder(accountRepository);
        order.verify(accountRepository).findByIdForUpdate(LOW_ID);
        order.verify(accountRepository).findByIdForUpdate(HIGH_ID);
    }

    @Test
    void rejectsSameAccountBeforeLocking() {
        assertBusinessError(
                request(LOW_ID, LOW_ID, "10.00", null),
                "SAME_ACCOUNT_TRANSFER"
        );
        verify(accountRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    @Test
    void rejectsMissingSourceOrTargetAccount() {
        when(accountRepository.findByIdForUpdate(LOW_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer(request(LOW_ID, HIGH_ID, "10.00", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));

        org.mockito.Mockito.reset(accountRepository);
        when(accountRepository.findByIdForUpdate(LOW_ID))
                .thenReturn(Optional.of(fundedAccount(LOW_ID, Currency.RUB, "100.00")));
        when(accountRepository.findByIdForUpdate(HIGH_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transfer(request(LOW_ID, HIGH_ID, "10.00", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    @Test
    void rejectsBlockedAndClosedSourceAndTargetAccounts() {
        assertInactiveAccount(true, AccountStatus.BLOCKED);
        assertInactiveAccount(true, AccountStatus.CLOSED);
        assertInactiveAccount(false, AccountStatus.BLOCKED);
        assertInactiveAccount(false, AccountStatus.CLOSED);
    }

    @Test
    void rejectsCurrencyMismatchWithoutChangingBalances() {
        AccountEntity source = fundedAccount(LOW_ID, Currency.RUB, "100.00");
        AccountEntity target = fundedAccount(HIGH_ID, Currency.USD, "25.00");
        stubLockedAccounts(source, target);

        assertBusinessError(request(LOW_ID, HIGH_ID, "10.00", null), "CURRENCY_MISMATCH");

        assertThat(source.getBalance()).isEqualTo(new BigDecimal("100.00"));
        assertThat(target.getBalance()).isEqualTo(new BigDecimal("25.00"));
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    @Test
    void rejectsInsufficientFundsWithoutCreditingTarget() {
        AccountEntity source = fundedAccount(LOW_ID, Currency.RUB, "50.00");
        AccountEntity target = fundedAccount(HIGH_ID, Currency.RUB, "25.00");
        stubLockedAccounts(source, target);

        assertBusinessError(request(LOW_ID, HIGH_ID, "50.01", null), "INSUFFICIENT_FUNDS");

        assertThat(source.getBalance()).isEqualTo(new BigDecimal("50.00"));
        assertThat(target.getBalance()).isEqualTo(new BigDecimal("25.00"));
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    @Test
    void validatesAmountBeforeLocking() {
        assertBusinessError(request(LOW_ID, HIGH_ID, null, null), "INVALID_AMOUNT");
        assertBusinessError(request(LOW_ID, HIGH_ID, "0.00", null), "INVALID_AMOUNT");
        assertBusinessError(request(LOW_ID, HIGH_ID, "-1.00", null), "INVALID_AMOUNT");
        assertBusinessError(request(LOW_ID, HIGH_ID, "1.001", null), "INVALID_AMOUNT_SCALE");
        assertBusinessError(request(LOW_ID, HIGH_ID, "100000000000000000.00", null), "INVALID_AMOUNT");
        verify(accountRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    @Test
    void convertsBlankDescriptionToNull() {
        AccountEntity source = fundedAccount(LOW_ID, Currency.RUB, "100.00");
        AccountEntity target = fundedAccount(HIGH_ID, Currency.RUB, "0.00");
        stubLockedAccounts(source, target);
        when(transactionRepository.save(any(AccountTransactionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response = service.transfer(request(LOW_ID, HIGH_ID, "10.00", "   "));

        assertThat(response.description()).isNull();
    }

    private void assertInactiveAccount(boolean sourceInactive, AccountStatus status) {
        org.mockito.Mockito.reset(accountRepository);
        org.mockito.Mockito.reset(transactionRepository);
        AccountEntity source = accountMock(sourceInactive ? status : AccountStatus.ACTIVE);
        AccountEntity target = org.mockito.Mockito.mock(AccountEntity.class);
        if (!sourceInactive) {
            when(target.getStatus()).thenReturn(status);
        }
        stubLockedAccounts(source, target);

        assertBusinessError(request(LOW_ID, HIGH_ID, "10.00", null), "ACCOUNT_NOT_ACTIVE");
        verify(transactionRepository, never()).save(any(AccountTransactionEntity.class));
    }

    private AccountEntity accountMock(AccountStatus status) {
        AccountEntity account = org.mockito.Mockito.mock(AccountEntity.class);
        when(account.getStatus()).thenReturn(status);
        return account;
    }

    private void assertBusinessError(TransferRequest request, String code) {
        assertThatThrownBy(() -> service.transfer(request))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void stubLockedAccounts(AccountEntity lowAccount, AccountEntity highAccount) {
        when(accountRepository.findByIdForUpdate(LOW_ID)).thenReturn(Optional.of(lowAccount));
        when(accountRepository.findByIdForUpdate(HIGH_ID)).thenReturn(Optional.of(highAccount));
    }

    private TransferRequest request(UUID sourceId, UUID targetId, String amount, String description) {
        return new TransferRequest(
                sourceId,
                targetId,
                amount == null ? null : new BigDecimal(amount),
                description
        );
    }

    private AccountEntity fundedAccount(UUID id, Currency currency, String balance) {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(), "Transfer", "Owner", UUID.randomUUID() + "@example.com",
                TRANSACTION_TIME, TRANSACTION_TIME
        );
        AccountEntity account = new AccountEntity(
                id,
                id.equals(LOW_ID) ? "10000000000000000001" : "10000000000000000002",
                customer,
                currency,
                TRANSACTION_TIME,
                TRANSACTION_TIME
        );
        if (new BigDecimal(balance).signum() > 0) {
            account.deposit(new BigDecimal(balance), TRANSACTION_TIME);
        }
        return account;
    }
}
