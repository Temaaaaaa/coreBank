package artem.dev.corebank.transaction.repository;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionHistoryRepositoryIntegrationTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void findsDepositWithdrawalAndTransferForEachParticipatingAccountWithoutForeignRowsOrDuplicates() {
        AccountEntity first = saveAccount("12345678901234567801");
        AccountEntity second = saveAccount("12345678901234567802");
        AccountEntity foreign = saveAccount("12345678901234567803");
        AccountTransactionEntity deposit = saveDeposit(UUID.randomUUID(), first, BASE_TIME);
        AccountTransactionEntity withdrawal = saveWithdrawal(UUID.randomUUID(), first, BASE_TIME.plusSeconds(1));
        AccountTransactionEntity transfer = saveTransfer(
                UUID.randomUUID(), first, second, BASE_TIME.plusSeconds(2)
        );
        saveDeposit(UUID.randomUUID(), foreign, BASE_TIME.plusSeconds(3));

        Page<AccountTransactionEntity> firstHistory = history(
                first.getId(), null, null, null, 0, 20, Sort.Direction.DESC
        );
        Page<AccountTransactionEntity> secondHistory = history(
                second.getId(), null, null, null, 0, 20, Sort.Direction.DESC
        );

        assertThat(firstHistory.getContent()).extracting(AccountTransactionEntity::getId)
                .containsExactly(transfer.getId(), withdrawal.getId(), deposit.getId());
        assertThat(firstHistory.getContent()).extracting(AccountTransactionEntity::getId)
                .doesNotHaveDuplicates();
        assertThat(secondHistory.getContent()).extracting(AccountTransactionEntity::getId)
                .containsExactly(transfer.getId());
    }

    @Test
    void filtersEverySupportedTransactionTypeAcrossWholeParticipationCondition() {
        AccountEntity first = saveAccount("12345678901234567804");
        AccountEntity second = saveAccount("12345678901234567805");
        AccountTransactionEntity deposit = saveDeposit(UUID.randomUUID(), first, BASE_TIME);
        AccountTransactionEntity withdrawal = saveWithdrawal(UUID.randomUUID(), first, BASE_TIME.plusSeconds(1));
        AccountTransactionEntity transfer = saveTransfer(
                UUID.randomUUID(), second, first, BASE_TIME.plusSeconds(2)
        );

        assertThat(history(first.getId(), TransactionType.DEPOSIT, null, null, 0, 20, Sort.Direction.DESC)
                .getContent()).extracting(AccountTransactionEntity::getId).containsExactly(deposit.getId());
        assertThat(history(first.getId(), TransactionType.WITHDRAWAL, null, null, 0, 20, Sort.Direction.DESC)
                .getContent()).extracting(AccountTransactionEntity::getId).containsExactly(withdrawal.getId());
        assertThat(history(first.getId(), TransactionType.TRANSFER, null, null, 0, 20, Sort.Direction.DESC)
                .getContent()).extracting(AccountTransactionEntity::getId).containsExactly(transfer.getId());
    }

    @Test
    void appliesFromAndToInclusivelyAloneAndTogether() {
        AccountEntity account = saveAccount("12345678901234567806");
        AccountTransactionEntity before = saveDeposit(UUID.randomUUID(), account, BASE_TIME);
        AccountTransactionEntity boundary = saveDeposit(UUID.randomUUID(), account, BASE_TIME.plusSeconds(1));
        AccountTransactionEntity after = saveDeposit(UUID.randomUUID(), account, BASE_TIME.plusSeconds(2));
        Instant boundaryTime = boundary.getCreatedAt();

        assertThat(history(account.getId(), null, boundaryTime, null, 0, 20, Sort.Direction.ASC)
                .getContent()).extracting(AccountTransactionEntity::getId)
                .containsExactly(boundary.getId(), after.getId());
        assertThat(history(account.getId(), null, null, boundaryTime, 0, 20, Sort.Direction.ASC)
                .getContent()).extracting(AccountTransactionEntity::getId)
                .containsExactly(before.getId(), boundary.getId());
        assertThat(history(account.getId(), null, boundaryTime, boundaryTime, 0, 20, Sort.Direction.ASC)
                .getContent()).extracting(AccountTransactionEntity::getId)
                .containsExactly(boundary.getId());
    }

    @Test
    void performsPaginationAndCountInDatabase() {
        AccountEntity account = saveAccount("12345678901234567807");
        for (int index = 0; index < 25; index++) {
            saveDeposit(uuid(index + 1), account, BASE_TIME.plusSeconds(index));
        }

        Page<AccountTransactionEntity> page = history(
                account.getId(), null, null, null, 1, 10, Sort.Direction.DESC
        );

        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(10);
        assertThat(page.getNumberOfElements()).isEqualTo(10);
        assertThat(page.getTotalElements()).isEqualTo(25);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent().getFirst().getCreatedAt()).isEqualTo(BASE_TIME.plusSeconds(14));
        assertThat(page.getContent().getLast().getCreatedAt()).isEqualTo(BASE_TIME.plusSeconds(5));
    }

    @Test
    void appliesAllFiltersToContentAndCountDuringPagination() {
        AccountEntity account = saveAccount("12345678901234567809");
        AccountEntity foreign = saveAccount("12345678901234567810");
        for (int index = 0; index < 7; index++) {
            saveDeposit(uuid(index + 1), account, BASE_TIME.plusSeconds(index));
        }
        saveWithdrawal(uuid(20), account, BASE_TIME.plusSeconds(3));
        saveDeposit(uuid(21), foreign, BASE_TIME.plusSeconds(3));

        Page<AccountTransactionEntity> page = history(
                account.getId(),
                TransactionType.DEPOSIT,
                BASE_TIME.plusSeconds(1),
                BASE_TIME.plusSeconds(5),
                1,
                2,
                Sort.Direction.ASC
        );

        assertThat(page.getContent()).extracting(AccountTransactionEntity::getId)
                .containsExactly(uuid(4), uuid(5));
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void usesIdAsStableTieBreakerInBothSortDirections() {
        AccountEntity account = saveAccount("12345678901234567808");
        UUID firstId = uuid(1);
        UUID secondId = uuid(2);
        UUID thirdId = uuid(3);
        saveDeposit(secondId, account, BASE_TIME);
        saveDeposit(firstId, account, BASE_TIME);
        saveDeposit(thirdId, account, BASE_TIME);

        List<UUID> ascending = history(account.getId(), null, null, null, 0, 20, Sort.Direction.ASC)
                .getContent().stream().map(AccountTransactionEntity::getId).toList();
        List<UUID> descending = history(account.getId(), null, null, null, 0, 20, Sort.Direction.DESC)
                .getContent().stream().map(AccountTransactionEntity::getId).toList();

        assertThat(ascending).containsExactly(firstId, secondId, thirdId);
        assertThat(descending).containsExactly(thirdId, secondId, firstId);
    }

    private Page<AccountTransactionEntity> history(
            UUID accountId,
            TransactionType type,
            Instant from,
            Instant to,
            int page,
            int size,
            Sort.Direction direction
    ) {
        Sort sort = Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
        return transactionRepository.findAccountHistory(
                accountId, type, from, to, PageRequest.of(page, size, sort)
        );
    }

    private AccountTransactionEntity saveDeposit(UUID id, AccountEntity account, Instant createdAt) {
        return transactionRepository.saveAndFlush(AccountTransactionEntity.deposit(
                id, new BigDecimal("10.00"), account, null, createdAt
        ));
    }

    private AccountTransactionEntity saveWithdrawal(UUID id, AccountEntity account, Instant createdAt) {
        return transactionRepository.saveAndFlush(AccountTransactionEntity.withdrawal(
                id, new BigDecimal("10.00"), account, null, createdAt
        ));
    }

    private AccountTransactionEntity saveTransfer(
            UUID id,
            AccountEntity source,
            AccountEntity target,
            Instant createdAt
    ) {
        return transactionRepository.saveAndFlush(AccountTransactionEntity.transfer(
                id, new BigDecimal("10.00"), source, target, null, createdAt
        ));
    }

    private AccountEntity saveAccount(String accountNumber) {
        CustomerEntity customer = customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(),
                "History",
                "Owner",
                UUID.randomUUID() + "@example.com",
                BASE_TIME,
                BASE_TIME
        ));
        return accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(), accountNumber, customer, Currency.RUB, BASE_TIME, BASE_TIME
        ));
    }

    private UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
