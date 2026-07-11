package artem.dev.corebank.transaction;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
class WithdrawalFullIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-12T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MoneyOperationService service;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void depositThenWithdrawalUpdatesBalanceAndCreatesBothTransactions() throws Exception {
        UUID accountId = createAccountThroughHttp("withdrawal.full@example.com");
        depositThroughHttp(accountId, "1500.00");

        mockMvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":400.00,\"description\":\"ATM withdrawal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.amount").value(400.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.targetAccountId").doesNotExist());

        mockMvc.perform(get("/api/v1/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1100.00));

        AccountEntity account = accountRepository.findById(accountId).orElseThrow();
        List<AccountTransactionEntity> transactions = transactionRepository.findAll();
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("1100.00"));
        assertThat(transactions).extracting(AccountTransactionEntity::getType)
                .containsExactlyInAnyOrder(TransactionType.DEPOSIT, TransactionType.WITHDRAWAL);
    }

    @Test
    void insufficientFundsLeavesBalanceAndTransactionsUnchanged() throws Exception {
        UUID accountId = createAccountThroughHttp("insufficient.full@example.com");
        depositThroughHttp(accountId, "100.00");

        mockMvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(transactionRepository.findAll())
                .hasSize(1)
                .allMatch(transaction -> transaction.getType() == TransactionType.DEPOSIT);
    }

    @Test
    void concurrentWithdrawalsAllowOnlyOneWhenFundsCoverOne() throws Exception {
        AccountEntity account = saveAccountDirectly();
        service.deposit(account.getId(), new DepositRequest(new BigDecimal("100.00"), "Funding"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(() -> withdrawAfterSignal(account.getId(), ready, start));
            Future<String> second = executor.submit(() -> withdrawAfterSignal(account.getId(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "INSUFFICIENT_FUNDS");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        AccountEntity updated = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(updated.getBalance()).isEqualTo(new BigDecimal("20.00"));
        assertThat(updated.getBalance()).isNotNegative();
        assertThat(transactionRepository.findAll().stream()
                .filter(transaction -> transaction.getType() == TransactionType.WITHDRAWAL))
                .hasSize(1);
    }

    private String withdrawAfterSignal(UUID accountId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent withdrawal start signal timed out");
        }
        try {
            service.withdraw(accountId, new WithdrawalRequest(new BigDecimal("80.00"), "Concurrent"));
            return "SUCCESS";
        } catch (BusinessRuleException exception) {
            return exception.getErrorCode();
        }
    }

    private UUID createAccountThroughHttp(String email) throws Exception {
        MvcResult customerResult = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Withdrawal","lastName":"Owner","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID customerId = UUID.fromString(objectMapper.readTree(
                customerResult.getResponse().getContentAsString()
        ).get("id").asText());
        MvcResult accountResult = mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"RUB\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                accountResult.getResponse().getContentAsString()
        ).get("id").asText());
    }

    private void depositThroughHttp(UUID accountId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private AccountEntity saveAccountDirectly() {
        CustomerEntity customer = customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(), "Concurrent", "Owner", "concurrent.withdrawal@example.com",
                FIXED_TIME, FIXED_TIME
        ));
        return accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(), "12345678901234567898", customer, Currency.RUB, FIXED_TIME, FIXED_TIME
        ));
    }
}
