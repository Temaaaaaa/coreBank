package artem.dev.corebank.transaction.repository;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountTransactionRepositoryIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        transactionRepository.flush();
        accountRepository.deleteAll();
        accountRepository.flush();
        customerRepository.deleteAll();
        customerRepository.flush();
    }

    @Test
    void savesDepositWithExpectedStructureAmountAndCurrency() {
        AccountEntity account = saveAccount("12345678901234567890");
        AccountTransactionEntity transaction = AccountTransactionEntity.deposit(
                UUID.randomUUID(),
                new BigDecimal("1500.00"),
                account,
                "Initial deposit",
                FIXED_TIME
        );

        transactionRepository.saveAndFlush(transaction);
        entityManager.clear();
        AccountTransactionEntity loaded = transactionRepository.findById(transaction.getId()).orElseThrow();

        assertThat(loaded.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(loaded.getSourceAccount()).isNull();
        assertThat(loaded.getTargetAccount().getId()).isEqualTo(account.getId());
        assertThat(loaded.getAmount()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(loaded.getAmount().scale()).isEqualTo(2);
        assertThat(loaded.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(loaded.getDescription()).isEqualTo("Initial deposit");
        assertThat(loaded.getCreatedAt()).isEqualTo(FIXED_TIME);
    }

    @Test
    void savesWithdrawalWithExpectedStructureAmountAndCurrency() {
        AccountEntity account = saveAccount("12345678901234567897");
        AccountTransactionEntity transaction = AccountTransactionEntity.withdrawal(
                UUID.randomUUID(),
                new BigDecimal("400.00"),
                account,
                "ATM withdrawal",
                FIXED_TIME
        );

        transactionRepository.saveAndFlush(transaction);
        entityManager.clear();
        AccountTransactionEntity loaded = transactionRepository.findById(transaction.getId()).orElseThrow();

        assertThat(loaded.getType()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(loaded.getSourceAccount().getId()).isEqualTo(account.getId());
        assertThat(loaded.getTargetAccount()).isNull();
        assertThat(loaded.getAmount()).isEqualTo(new BigDecimal("400.00"));
        assertThat(loaded.getAmount().scale()).isEqualTo(2);
        assertThat(loaded.getCurrency()).isEqualTo(Currency.RUB);
    }

    @Test
    void databaseEnforcesSourceAccountForeignKey() {
        assertSqlState("23503", () -> insertRaw(
                "WITHDRAWAL",
                new BigDecimal("10.00"),
                "RUB",
                UUID.randomUUID(),
                null
        ));
    }

    @Test
    void databaseRejectsWithdrawalWithoutSourceAccount() {
        assertSqlState("23514", () -> insertRaw(
                "WITHDRAWAL",
                new BigDecimal("10.00"),
                "RUB",
                null,
                null
        ));
    }

    @Test
    void databaseEnforcesTargetAccountForeignKey() {
        assertSqlState("23503", () -> insertRaw(
                "DEPOSIT",
                new BigDecimal("10.00"),
                "RUB",
                null,
                UUID.randomUUID()
        ));
    }

    @Test
    void databaseRejectsNonPositiveAmount() {
        AccountEntity account = saveAccount("12345678901234567891");
        assertSqlState("23514", () -> insertRaw(
                "DEPOSIT",
                new BigDecimal("0.00"),
                "RUB",
                null,
                account.getId()
        ));
    }

    @Test
    void databaseHasExplicitAmountScaleConstraint() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'chk_account_transactions_amount_scale'",
                Integer.class
        );

        assertThat(count).isEqualTo(1);
    }

    @Test
    void databaseRejectsUnsupportedTransactionType() {
        AccountEntity account = saveAccount("12345678901234567892");
        assertSqlState("23514", () -> insertRaw(
                "REFUND",
                new BigDecimal("10.00"),
                "RUB",
                null,
                account.getId()
        ));
    }

    @Test
    void databaseRejectsInvalidDepositStructure() {
        AccountEntity source = saveAccount("12345678901234567893");
        AccountEntity target = saveAccount("12345678901234567894");
        assertSqlState("23514", () -> insertRaw(
                "DEPOSIT",
                new BigDecimal("10.00"),
                "RUB",
                source.getId(),
                target.getId()
        ));
    }

    @Test
    void databaseRejectsInvalidWithdrawalStructure() {
        AccountEntity target = saveAccount("12345678901234567895");
        assertSqlState("23514", () -> insertRaw(
                "WITHDRAWAL",
                new BigDecimal("10.00"),
                "RUB",
                null,
                target.getId()
        ));
    }

    @Test
    void databaseRejectsTransferToSameAccount() {
        AccountEntity account = saveAccount("12345678901234567896");
        assertSqlState("23514", () -> insertRaw(
                "TRANSFER",
                new BigDecimal("10.00"),
                "RUB",
                account.getId(),
                account.getId()
        ));
    }

    @Test
    void flywayAppliedAccountTransactionMigration() {
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '4'",
                Boolean.class
        );
        String table = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.account_transactions')::text",
                String.class
        );

        assertThat(migrationSucceeded).isTrue();
        assertThat(table).isEqualTo("account_transactions");
    }

    private AccountEntity saveAccount(String accountNumber) {
        CustomerEntity customer = customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(),
                "Artem",
                "Ivanov",
                UUID.randomUUID() + "@example.com",
                FIXED_TIME,
                FIXED_TIME
        ));
        return accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(),
                accountNumber,
                customer,
                Currency.RUB,
                FIXED_TIME,
                FIXED_TIME
        ));
    }

    private void insertRaw(
            String type,
            BigDecimal amount,
            String currency,
            UUID sourceAccountId,
            UUID targetAccountId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO account_transactions (
                    id, type, amount, currency, source_account_id, target_account_id, description, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                type,
                amount,
                currency,
                sourceAccountId,
                targetAccountId
        );
    }

    private void assertSqlState(String expectedSqlState, Runnable operation) {
        Throwable thrown = catchThrowable(operation::run);
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown).hasRootCauseInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(sqlException.getSQLState()).isEqualTo(expectedSqlState);
    }
}
