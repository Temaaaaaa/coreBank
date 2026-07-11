package artem.dev.corebank.account.service;

import artem.dev.corebank.account.dto.AccountResponse;
import artem.dev.corebank.account.dto.OpenAccountRequest;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.mapper.AccountMapper;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.InternalOperationException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACCOUNT_NUMBER = "12345678901234567890";
    private static final Instant CLOCK_TIME = Instant.parse("2026-07-11T18:00:00.123456789Z");
    private static final Instant PERSISTED_TIME = Instant.parse("2026-07-11T18:00:00.123456Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(CLOCK_TIME, ZoneOffset.UTC);
        accountService = new AccountService(
                accountRepository,
                customerRepository,
                accountNumberGenerator,
                new AccountMapper(),
                fixedClock
        );
    }

    @Test
    void opensActiveAccountWithZeroBalanceAndFixedTimestamps() {
        CustomerEntity customer = customer();
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(accountNumberGenerator.generate()).thenReturn(ACCOUNT_NUMBER);
        when(accountRepository.existsByAccountNumber(ACCOUNT_NUMBER)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(AccountEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.openAccount(CUSTOMER_ID, new OpenAccountRequest(Currency.RUB));

        ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountRepository).saveAndFlush(captor.capture());
        AccountEntity savedAccount = captor.getValue();

        assertThat(response.id()).isNotNull();
        assertThat(response.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.accountNumber()).isEqualTo(ACCOUNT_NUMBER).matches("[1-9][0-9]{19}");
        assertThat(response.currency()).isEqualTo(Currency.RUB);
        assertThat(response.balance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(response.balance().scale()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(PERSISTED_TIME);
        assertThat(response.updatedAt()).isEqualTo(PERSISTED_TIME);
        assertThat(savedAccount.getCreatedAt()).isEqualTo(savedAccount.getUpdatedAt());
    }

    @Test
    void rejectsOpeningAccountForUnknownCustomer() {
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.openAccount(
                CUSTOMER_ID,
                new OpenAccountRequest(Currency.RUB)
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(CUSTOMER_ID.toString());

        verify(accountRepository, never()).saveAndFlush(any(AccountEntity.class));
    }

    @Test
    void returnsExistingAccount() {
        AccountEntity account = account(ACCOUNT_ID, ACCOUNT_NUMBER, PERSISTED_TIME);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccountById(ACCOUNT_ID);

        assertThat(response.id()).isEqualTo(ACCOUNT_ID);
        assertThat(response.accountNumber()).isEqualTo(ACCOUNT_NUMBER);
    }

    @Test
    void throwsWhenAccountDoesNotExist() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccountById(ACCOUNT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(ACCOUNT_ID.toString());
    }

    @Test
    void returnsCustomerAccountsInRepositoryOrder() {
        AccountEntity first = account(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "12345678901234567891",
                PERSISTED_TIME
        );
        AccountEntity second = account(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "12345678901234567892",
                PERSISTED_TIME.plusSeconds(1)
        );
        when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(true);
        when(accountRepository.findAllByCustomerIdOrderByCreatedAtAscIdAsc(CUSTOMER_ID))
                .thenReturn(List.of(first, second));

        List<AccountResponse> responses = accountService.getAccountsByCustomerId(CUSTOMER_ID);

        assertThat(responses).extracting(AccountResponse::id)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void returnsEmptyListForExistingCustomerWithoutAccounts() {
        when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(true);
        when(accountRepository.findAllByCustomerIdOrderByCreatedAtAscIdAsc(CUSTOMER_ID))
                .thenReturn(List.of());

        assertThat(accountService.getAccountsByCustomerId(CUSTOMER_ID)).isEmpty();
    }

    @Test
    void rejectsAccountListForUnknownCustomer() {
        when(customerRepository.existsById(CUSTOMER_ID)).thenReturn(false);

        assertThatThrownBy(() -> accountService.getAccountsByCustomerId(CUSTOMER_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(CUSTOMER_ID.toString());

        verify(accountRepository, never()).findAllByCustomerIdOrderByCreatedAtAscIdAsc(CUSTOMER_ID);
    }

    @Test
    void retriesWhenFirstGeneratedAccountNumberAlreadyExists() {
        String collision = "12345678901234567891";
        CustomerEntity customer = customer();
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(accountNumberGenerator.generate()).thenReturn(collision, ACCOUNT_NUMBER);
        when(accountRepository.existsByAccountNumber(collision)).thenReturn(true);
        when(accountRepository.existsByAccountNumber(ACCOUNT_NUMBER)).thenReturn(false);
        when(accountRepository.saveAndFlush(any(AccountEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.openAccount(CUSTOMER_ID, new OpenAccountRequest(Currency.EUR));

        assertThat(response.accountNumber()).isEqualTo(ACCOUNT_NUMBER);
        verify(accountNumberGenerator, times(2)).generate();
    }

    @Test
    void failsAfterFiveAccountNumberCollisions() {
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(accountNumberGenerator.generate()).thenReturn(ACCOUNT_NUMBER);
        when(accountRepository.existsByAccountNumber(ACCOUNT_NUMBER)).thenReturn(true);

        assertThatThrownBy(() -> accountService.openAccount(
                CUSTOMER_ID,
                new OpenAccountRequest(Currency.USD)
        )).isInstanceOf(InternalOperationException.class)
                .hasMessage("Unable to generate a unique account number");

        verify(accountNumberGenerator, times(5)).generate();
        verify(accountRepository, never()).saveAndFlush(any(AccountEntity.class));
    }

    private CustomerEntity customer() {
        return new CustomerEntity(
                CUSTOMER_ID,
                "Artem",
                "Ivanov",
                "account-owner@example.com",
                PERSISTED_TIME,
                PERSISTED_TIME
        );
    }

    private AccountEntity account(UUID id, String accountNumber, Instant createdAt) {
        return new AccountEntity(id, accountNumber, customer(), Currency.RUB, createdAt, createdAt);
    }
}
