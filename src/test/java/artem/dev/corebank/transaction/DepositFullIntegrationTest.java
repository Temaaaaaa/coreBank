package artem.dev.corebank.transaction;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransactionResponse;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
class DepositFullIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MoneyOperationService moneyOperationService;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void cleanBeforeTest() {
        cleanDatabase();
    }

    @AfterEach
    void cleanAfterTest() {
        cleanDatabase();
    }

    @Test
    void depositCreatesTransactionAndUpdatesAccountBalance() throws Exception {
        UUID customerId = createCustomerThroughHttp();
        UUID accountId = createAccountThroughHttp(customerId);

        MvcResult depositResult = mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1500.00,"description":"Initial deposit"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").doesNotExist())
                .andExpect(jsonPath("$.targetAccountId").value(accountId.toString()))
                .andReturn();

        JsonNode depositResponse = objectMapper.readTree(depositResult.getResponse().getContentAsString());
        UUID transactionId = UUID.fromString(depositResponse.get("id").asText());
        assertThat(depositResult.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/transactions/" + transactionId);
        assertThat(depositResult.getResponse().getContentAsString()).contains("\"amount\":1500.00");

        mockMvc.perform(get("/api/v1/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1500.00));

        AccountEntity account = accountRepository.findById(accountId).orElseThrow();
        List<AccountTransactionEntity> transactions = transactionRepository.findAll();
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("1500.00"));
        assertThat(transactions).hasSize(1);
        assertThat(transactions.getFirst().getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(transactions.getFirst().getTargetAccount().getId()).isEqualTo(accountId);
    }

    @Test
    void twoConcurrentDepositsDoNotLoseBalanceUpdates() throws Exception {
        AccountEntity account = saveAccountDirectly();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<TransactionResponse> first = executor.submit(() -> depositAfterSignal(
                    account.getId(), workersReady, start
            ));
            Future<TransactionResponse> second = executor.submit(() -> depositAfterSignal(
                    account.getId(), workersReady, start
            ));

            assertThat(workersReady.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(second.get(15, TimeUnit.SECONDS).type()).isEqualTo(TransactionType.DEPOSIT);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        AccountEntity updatedAccount = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(updatedAccount.getBalance()).isEqualTo(new BigDecimal("200.00"));
        assertThat(transactionRepository.findAll())
                .hasSize(2)
                .allMatch(transaction -> transaction.getType() == TransactionType.DEPOSIT);
    }

    private TransactionResponse depositAfterSignal(
            UUID accountId,
            CountDownLatch workersReady,
            CountDownLatch start
    ) throws InterruptedException {
        workersReady.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent deposit start signal timed out");
        }
        return moneyOperationService.deposit(
                accountId,
                new DepositRequest(new BigDecimal("100.00"), "Concurrent deposit")
        );
    }

    private UUID createCustomerThroughHttp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Deposit",
                                  "lastName":"Owner",
                                  "email":"deposit.full.integration@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).get("id").asText());
    }

    private UUID createAccountThroughHttp(UUID customerId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"RUB\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).get("id").asText());
    }

    private AccountEntity saveAccountDirectly() {
        CustomerEntity customer = customerRepository.saveAndFlush(new CustomerEntity(
                UUID.randomUUID(),
                "Concurrent",
                "Owner",
                "concurrent.deposit@example.com",
                FIXED_TIME,
                FIXED_TIME
        ));
        return accountRepository.saveAndFlush(new AccountEntity(
                UUID.randomUUID(),
                "12345678901234567899",
                customer,
                Currency.RUB,
                FIXED_TIME,
                FIXED_TIME
        ));
    }

    private void cleanDatabase() {
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }
}
