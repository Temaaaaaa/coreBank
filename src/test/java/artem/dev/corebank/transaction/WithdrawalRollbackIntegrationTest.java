package artem.dev.corebank.transaction;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
class WithdrawalRollbackIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-12T12:00:00Z");

    @Autowired
    private MoneyOperationService service;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanDatabase() {
        jdbcTemplate.execute("ALTER TABLE account_transactions DROP CONSTRAINT IF EXISTS test_reject_withdrawal");
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void transactionInsertFailureRollsBackBalanceChange() {
        CustomerEntity customer = customerRepository.save(new CustomerEntity(
                UUID.randomUUID(), "Rollback", "Owner", "withdrawal.rollback@example.com",
                FIXED_TIME, FIXED_TIME
        ));
        AccountEntity account = new AccountEntity(
                UUID.randomUUID(), "10000000000000000002", customer, Currency.RUB, FIXED_TIME, FIXED_TIME
        );
        account.deposit(new BigDecimal("100.00"), FIXED_TIME);
        accountRepository.saveAndFlush(account);
        jdbcTemplate.execute("""
                ALTER TABLE account_transactions
                ADD CONSTRAINT test_reject_withdrawal CHECK (type <> 'WITHDRAWAL')
                """);

        try {
            assertThatThrownBy(() -> service.withdraw(
                    account.getId(), new WithdrawalRequest(new BigDecimal("40.00"), null)
            )).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE account_transactions DROP CONSTRAINT test_reject_withdrawal");
        }

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(transactionRepository.count()).isZero();
    }
}
