package artem.dev.corebank.transaction;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransferRequest;
import artem.dev.corebank.transaction.entity.TransactionType;
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
class TransferRollbackIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-23T10:00:00Z");

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
        jdbcTemplate.execute("ALTER TABLE account_transactions DROP CONSTRAINT IF EXISTS test_reject_transfer");
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void transactionInsertFailureRollsBackBothBalanceChanges() {
        CustomerEntity customer = customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(), "Rollback", "Owner", "transfer.rollback@example.com",
                FIXED_TIME, FIXED_TIME
        ));
        AccountEntity source = accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(), "10000000000000000031", customer, Currency.RUB,
                FIXED_TIME, FIXED_TIME
        ));
        AccountEntity target = accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(), "10000000000000000032", customer, Currency.RUB,
                FIXED_TIME, FIXED_TIME
        ));
        service.deposit(source.getId(), new DepositRequest(new BigDecimal("100.00"), "Funding"));
        jdbcTemplate.execute("""
                ALTER TABLE account_transactions
                ADD CONSTRAINT test_reject_transfer CHECK (type <> 'TRANSFER')
                """);

        try {
            assertThatThrownBy(() -> service.transfer(new TransferRequest(
                    source.getId(), target.getId(), new BigDecimal("40.00"), null
            ))).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE account_transactions DROP CONSTRAINT test_reject_transfer");
        }

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(accountRepository.findById(target.getId()).orElseThrow().getBalance())
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(transactionRepository.findAll())
                .noneMatch(transaction -> transaction.getType() == TransactionType.TRANSFER);
    }
}
