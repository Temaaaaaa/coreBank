package artem.dev.corebank.transaction;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
class DepositRollbackIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-12T10:00:00Z");

    @Autowired
    private MoneyOperationService moneyOperationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @MockitoBean
    private AccountTransactionRepository transactionRepository;

    @AfterEach
    void cleanDatabase() {
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void shouldRollbackBalanceWhenTransactionRecordCannotBeSaved() {
        CustomerEntity customer = customerRepository.save(
                new CustomerEntity(
                        UUID.randomUUID(),
                        "Ada",
                        "Lovelace",
                        "ada.rollback@example.com",
                        FIXED_TIME,
                        FIXED_TIME
                )
        );
        AccountEntity account = accountRepository.save(
                new AccountEntity(
                        UUID.randomUUID(),
                        "10000000000000000001",
                        customer,
                        Currency.USD,
                        FIXED_TIME,
                        FIXED_TIME
                )
        );

        when(transactionRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("Forced transaction persistence failure"));

        DepositRequest request = new DepositRequest(new BigDecimal("25.00"), "rollback check");

        assertThatThrownBy(() -> moneyOperationService.deposit(account.getId(), request))
                .isInstanceOf(DataIntegrityViolationException.class);

        AccountEntity reloadedAccount = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloadedAccount.getBalance()).isEqualByComparingTo("0.00");
    }
}
