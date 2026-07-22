package artem.dev.corebank.transaction;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.repository.CustomerRepository;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
class TransferFullIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountTransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    @Test
    void transferThroughHttpMovesMoneyAndCreatesOneTransferRecord() throws Exception {
        UUID customerId = createCustomer("transfer.success@example.com");
        UUID sourceId = createAccount(customerId, Currency.RUB);
        UUID targetId = createAccount(customerId, Currency.RUB);
        deposit(sourceId, "1000.00");

        MvcResult result = performTransfer(sourceId, targetId, "250.00", "  Rent  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").value(sourceId.toString()))
                .andExpect(jsonPath("$.targetAccountId").value(targetId.toString()))
                .andExpect(jsonPath("$.description").value("Rent"))
                .andReturn();

        UUID transactionId = UUID.fromString(objectMapper.readTree(
                result.getResponse().getContentAsString()
        ).get("id").asText());
        header().string("Location", "/api/v1/transactions/" + transactionId)
                .match(result);

        assertBalances(sourceId, "750.00", targetId, "250.00");
        List<AccountTransactionEntity> transfers = transactionRepository.findAll().stream()
                .filter(transaction -> transaction.getType() == TransactionType.TRANSFER)
                .toList();
        assertThat(transfers).hasSize(1);
        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT type, amount, currency, source_account_id, target_account_id, description
                FROM account_transactions
                WHERE id = ?
                """, transactionId);
        assertThat(stored.get("type")).isEqualTo("TRANSFER");
        assertThat(stored.get("amount")).isEqualTo(new BigDecimal("250.00"));
        assertThat(stored.get("currency")).isEqualTo("RUB");
        assertThat(stored.get("source_account_id")).isEqualTo(sourceId);
        assertThat(stored.get("target_account_id")).isEqualTo(targetId);
        assertThat(stored.get("description")).isEqualTo("Rent");
    }

    @Test
    void transferOfWholeBalanceLeavesSourceAtZero() throws Exception {
        UUID customerId = createCustomer("transfer.whole@example.com");
        UUID sourceId = createAccount(customerId, Currency.RUB);
        UUID targetId = createAccount(customerId, Currency.RUB);
        deposit(sourceId, "100.00");

        performTransfer(sourceId, targetId, "100.00", null)
                .andExpect(status().isCreated());

        assertBalances(sourceId, "0.00", targetId, "100.00");
    }

    @Test
    void insufficientFundsDoesNotChangeBalancesOrCreateTransfer() throws Exception {
        UUID customerId = createCustomer("transfer.insufficient@example.com");
        UUID sourceId = createAccount(customerId, Currency.RUB);
        UUID targetId = createAccount(customerId, Currency.RUB);
        deposit(sourceId, "50.00");

        performTransfer(sourceId, targetId, "50.01", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertBalances(sourceId, "50.00", targetId, "0.00");
        assertNoTransfers();
    }

    @Test
    void currencyMismatchDoesNotChangeBalancesOrCreateTransfer() throws Exception {
        UUID customerId = createCustomer("transfer.currency@example.com");
        UUID sourceId = createAccount(customerId, Currency.RUB);
        UUID targetId = createAccount(customerId, Currency.USD);
        deposit(sourceId, "50.00");

        performTransfer(sourceId, targetId, "10.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        assertBalances(sourceId, "50.00", targetId, "0.00");
        assertNoTransfers();
    }

    @Test
    void sameAccountIsRejectedBeforeAnyBalanceChange() throws Exception {
        UUID customerId = createCustomer("transfer.same@example.com");
        UUID accountId = createAccount(customerId, Currency.RUB);

        performTransfer(accountId, accountId, "10.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));

        assertThat(balance(accountId)).isEqualTo(new BigDecimal("0.00"));
        assertNoTransfers();
    }

    @Test
    void inactiveSourceOrTargetIsRejected() throws Exception {
        UUID customerId = createCustomer("transfer.inactive@example.com");
        UUID sourceId = createAccount(customerId, Currency.RUB);
        UUID targetId = createAccount(customerId, Currency.RUB);
        deposit(sourceId, "100.00");

        setStatus(sourceId, "BLOCKED");
        performTransfer(sourceId, targetId, "10.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        setStatus(sourceId, "ACTIVE");
        setStatus(targetId, "CLOSED");
        performTransfer(sourceId, targetId, "10.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertBalances(sourceId, "100.00", targetId, "0.00");
        assertNoTransfers();
    }

    @Test
    void missingSourceOrTargetReturnsNotFound() throws Exception {
        UUID customerId = createCustomer("transfer.missing@example.com");
        UUID accountId = createAccount(customerId, Currency.RUB);

        performTransfer(UUID.randomUUID(), accountId, "10.00", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        performTransfer(accountId, UUID.randomUUID(), "10.00", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(balance(accountId)).isEqualTo(new BigDecimal("0.00"));
        assertNoTransfers();
    }

    private org.springframework.test.web.servlet.ResultActions performTransfer(
            UUID sourceId,
            UUID targetId,
            String amount,
            String description
    ) throws Exception {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return mockMvc.perform(post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sourceAccountId":"%s",
                          "targetAccountId":"%s",
                          "amount":%s,
                          "description":%s
                        }
                        """.formatted(sourceId, targetId, amount, descriptionJson)));
    }

    private UUID createCustomer(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Transfer","lastName":"Owner","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return responseId(result);
    }

    private UUID createAccount(UUID customerId, Currency currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return responseId(result);
    }

    private void deposit(UUID accountId, String amount) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    private UUID responseId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(response.get("id").asText());
    }

    private void assertBalances(UUID sourceId, String sourceBalance, UUID targetId, String targetBalance) {
        assertThat(balance(sourceId)).isEqualTo(new BigDecimal(sourceBalance));
        assertThat(balance(targetId)).isEqualTo(new BigDecimal(targetBalance));
    }

    private BigDecimal balance(UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId).orElseThrow();
        return account.getBalance();
    }

    private void assertNoTransfers() {
        assertThat(transactionRepository.findAll())
                .noneMatch(transaction -> transaction.getType() == TransactionType.TRANSFER);
    }

    private void setStatus(UUID accountId, String statusValue) {
        jdbcTemplate.update("UPDATE accounts SET status = ? WHERE id = ?", statusValue, accountId);
    }
}
