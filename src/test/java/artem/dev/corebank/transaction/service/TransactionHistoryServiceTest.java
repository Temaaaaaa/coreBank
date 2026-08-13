package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.transaction.dto.TransactionHistoryRequest;
import artem.dev.corebank.transaction.dto.TransactionPageResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryServiceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-02T00:00:00Z");

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
                Clock.fixed(FROM, ZoneOffset.UTC)
        );
    }

    @Test
    void returnsMappedPageAndBuildsStableAscendingPageable() {
        TransactionHistoryRequest request = new TransactionHistoryRequest(
                TransactionType.DEPOSIT,
                FROM,
                TO,
                2,
                10,
                Sort.Direction.ASC
        );
        AccountTransactionEntity transaction = AccountTransactionEntity.deposit(
                UUID.randomUUID(),
                new BigDecimal("25.00"),
                account(),
                "Test deposit",
                FROM
        );
        when(accountRepository.existsById(ACCOUNT_ID)).thenReturn(true);
        when(transactionRepository.findAccountHistory(
                eq(ACCOUNT_ID), eq(TransactionType.DEPOSIT), eq(FROM), eq(TO), any(Pageable.class)
        )).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(4);
            return new PageImpl<>(List.of(
                    transaction, transaction, transaction, transaction, transaction
            ), pageable, 25);
        });

        TransactionPageResponse response = service.getAccountTransactions(ACCOUNT_ID, request);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAccountHistory(
                eq(ACCOUNT_ID), eq(TransactionType.DEPOSIT), eq(FROM), eq(TO), pageableCaptor.capture()
        );
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(response.content()).hasSize(5);
        assertThat(response.content().getFirst().targetAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(25);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void returnsEmptyPageForExistingAccountWithoutTransactions() {
        TransactionHistoryRequest request = TransactionHistoryRequest.from(java.util.Map.of());
        when(accountRepository.existsById(ACCOUNT_ID)).thenReturn(true);
        when(transactionRepository.findAccountHistory(
                eq(ACCOUNT_ID), eq(null), eq(null), eq(null), any(Pageable.class)
        )).thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(4), 0));

        TransactionPageResponse response = service.getAccountTransactions(ACCOUNT_ID, request);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void rejectsMissingAccountBeforeHistoryQuery() {
        when(accountRepository.existsById(ACCOUNT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getAccountTransactions(
                ACCOUNT_ID,
                TransactionHistoryRequest.from(java.util.Map.of())
        )).isInstanceOfSatisfying(ResourceNotFoundException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(exception.getMessage()).contains(ACCOUNT_ID.toString());
        });
        verify(transactionRepository, never()).findAccountHistory(any(), any(), any(), any(), any());
    }

    private AccountEntity account() {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(), "History", "Owner", "history.service@example.com", FROM, FROM
        );
        return new AccountEntity(
                ACCOUNT_ID, "12345678901234567890", customer, Currency.RUB, FROM, FROM
        );
    }
}
