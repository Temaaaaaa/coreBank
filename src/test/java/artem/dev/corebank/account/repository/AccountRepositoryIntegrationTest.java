package artem.dev.corebank.account.repository;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountRepositoryIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");

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
        accountRepository.deleteAll();
        accountRepository.flush();
        customerRepository.deleteAll();
        customerRepository.flush();
    }

    @Test
    void savesAndReadsAccountWithCustomerId() {
        CustomerEntity customer = saveCustomer();
        AccountEntity account = account(
                UUID.randomUUID(),
                "12345678901234567890",
                customer,
                FIXED_TIME
        );

        accountRepository.saveAndFlush(account);
        entityManager.clear();
        AccountEntity loaded = accountRepository.findById(account.getId()).orElseThrow();

        assertThat(loaded.getAccountNumber()).isEqualTo("12345678901234567890");
        assertThat(loaded.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(loaded.getCurrency()).isEqualTo(Currency.RUB);
        assertThat(loaded.getBalance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(loaded.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(loaded.getCreatedAt()).isEqualTo(FIXED_TIME);
    }

    @Test
    void databaseRejectsDuplicateAccountNumber() {
        CustomerEntity customer = saveCustomer();
        String accountNumber = "12345678901234567891";
        accountRepository.saveAndFlush(account(UUID.randomUUID(), accountNumber, customer, FIXED_TIME));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(
                account(UUID.randomUUID(), accountNumber, customer, FIXED_TIME)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseEnforcesCustomerForeignKey() {
        assertSqlState("23503", () -> insertRawAccount(
                "12345678901234567892",
                UUID.randomUUID(),
                "RUB",
                new BigDecimal("0.00"),
                "ACTIVE"
        ));
    }

    @Test
    void databaseRejectsInvalidAccountNumberFormat() {
        CustomerEntity customer = saveCustomer();

        assertSqlState("23514", () -> insertRawAccount(
                "0234567890123456789X",
                customer.getId(),
                "RUB",
                new BigDecimal("0.00"),
                "ACTIVE"
        ));
    }

    @Test
    void databaseRejectsNegativeBalance() {
        CustomerEntity customer = saveCustomer();

        assertSqlState("23514", () -> insertRawAccount(
                "12345678901234567893",
                customer.getId(),
                "RUB",
                new BigDecimal("-0.01"),
                "ACTIVE"
        ));
    }

    @Test
    void databaseRejectsUnsupportedCurrency() {
        CustomerEntity customer = saveCustomer();

        assertSqlState("23514", () -> insertRawAccount(
                "12345678901234567894",
                customer.getId(),
                "GBP",
                new BigDecimal("0.00"),
                "ACTIVE"
        ));
    }

    @Test
    void databaseRejectsUnsupportedStatus() {
        CustomerEntity customer = saveCustomer();

        assertSqlState("23514", () -> insertRawAccount(
                "12345678901234567895",
                customer.getId(),
                "EUR",
                new BigDecimal("0.00"),
                "PENDING"
        ));
    }

    @Test
    void findsCustomerAccountsInStableOrder() {
        CustomerEntity customer = saveCustomer();
        AccountEntity third = account(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                "12345678901234567898",
                customer,
                FIXED_TIME.plusSeconds(1)
        );
        AccountEntity second = account(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "12345678901234567897",
                customer,
                FIXED_TIME
        );
        AccountEntity first = account(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "12345678901234567896",
                customer,
                FIXED_TIME
        );
        accountRepository.saveAllAndFlush(List.of(third, second, first));

        List<AccountEntity> accounts = accountRepository
                .findAllByCustomerIdOrderByCreatedAtAscIdAsc(customer.getId());

        assertThat(accounts).extracting(AccountEntity::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    void flywayAppliedAccountMigration() {
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '3'",
                Boolean.class
        );
        String accountsTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.accounts')::text",
                String.class
        );

        assertThat(migrationSucceeded).isTrue();
        assertThat(accountsTable).isEqualTo("accounts");
    }

    private CustomerEntity saveCustomer() {
        return customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(),
                "Artem",
                "Ivanov",
                UUID.randomUUID() + "@example.com",
                FIXED_TIME,
                FIXED_TIME
        ));
    }

    private AccountEntity account(
            UUID id,
            String accountNumber,
            CustomerEntity customer,
            Instant createdAt
    ) {
        return new AccountEntity(id, accountNumber, customer, Currency.RUB, createdAt, createdAt);
    }

    private void insertRawAccount(
            String accountNumber,
            UUID customerId,
            String currency,
            BigDecimal balance,
            String status
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO accounts (
                    id, account_number, customer_id, currency, balance, status, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                accountNumber,
                customerId,
                currency,
                balance,
                status
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
