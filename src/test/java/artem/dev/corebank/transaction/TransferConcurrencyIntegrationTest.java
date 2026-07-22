package artem.dev.corebank.transaction;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransferRequest;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
class TransferConcurrencyIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-23T10:00:00Z");

    @Autowired
    private MoneyOperationService service;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void concurrentTransfersFromOneSourceCannotOverdrawIt() throws Exception {
        CustomerEntity customer = saveCustomer("concurrent.source@example.com");
        AccountEntity source = saveAccount(customer, "10000000000000000041");
        AccountEntity firstTarget = saveAccount(customer, "10000000000000000042");
        AccountEntity secondTarget = saveAccount(customer, "10000000000000000043");
        service.deposit(source.getId(), new DepositRequest(new BigDecimal("100.00"), "Funding"));

        List<String> outcomes = runConcurrently(
                new TransferRequest(source.getId(), firstTarget.getId(), new BigDecimal("80.00"), "First"),
                new TransferRequest(source.getId(), secondTarget.getId(), new BigDecimal("80.00"), "Second")
        );

        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "INSUFFICIENT_FUNDS");
        assertThat(balance(source)).isEqualTo(new BigDecimal("20.00"));
        assertThat(List.of(balance(firstTarget), balance(secondTarget)))
                .containsExactlyInAnyOrder(new BigDecimal("80.00"), new BigDecimal("0.00"));
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    void oppositeDirectionTransfersCompleteWithoutDeadlock() throws Exception {
        CustomerEntity customer = saveCustomer("concurrent.opposite@example.com");
        AccountEntity first = saveAccount(customer, "10000000000000000044");
        AccountEntity second = saveAccount(customer, "10000000000000000045");
        service.deposit(first.getId(), new DepositRequest(new BigDecimal("100.00"), "Funding"));
        service.deposit(second.getId(), new DepositRequest(new BigDecimal("100.00"), "Funding"));

        List<String> outcomes = runConcurrently(
                new TransferRequest(first.getId(), second.getId(), new BigDecimal("30.00"), "Forward"),
                new TransferRequest(second.getId(), first.getId(), new BigDecimal("20.00"), "Reverse")
        );

        assertThat(outcomes).containsExactly("SUCCESS", "SUCCESS");
        assertThat(balance(first)).isEqualTo(new BigDecimal("90.00"));
        assertThat(balance(second)).isEqualTo(new BigDecimal("110.00"));
        assertThat(transferCount()).isEqualTo(2);
    }

    private List<String> runConcurrently(TransferRequest firstRequest, TransferRequest secondRequest)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> transferAfterSignal(firstRequest, ready, start));
            Future<String> second = executor.submit(() -> transferAfterSignal(secondRequest, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String transferAfterSignal(
            TransferRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent transfer start signal timed out");
        }
        try {
            service.transfer(request);
            return "SUCCESS";
        } catch (BusinessRuleException exception) {
            return exception.getErrorCode();
        }
    }

    private CustomerEntity saveCustomer(String email) {
        return customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(), "Concurrent", "Owner", email, FIXED_TIME, FIXED_TIME
        ));
    }

    private AccountEntity saveAccount(CustomerEntity customer, String accountNumber) {
        return accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(), accountNumber, customer, Currency.RUB, FIXED_TIME, FIXED_TIME
        ));
    }

    private BigDecimal balance(AccountEntity account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private long transferCount() {
        return transactionRepository.findAll().stream()
                .filter(transaction -> transaction.getType() == TransactionType.TRANSFER)
                .count();
    }
}
