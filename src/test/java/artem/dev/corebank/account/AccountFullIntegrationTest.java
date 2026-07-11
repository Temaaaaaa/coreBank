package artem.dev.corebank.account;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.customer.repository.CustomerRepository;
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
import java.util.UUID;

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
class AccountFullIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void createsAndRetrievesAccountThroughHttpAndDatabase() throws Exception {
        MvcResult customerResult = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Account",
                                  "lastName": "Owner",
                                  "email": "account.full.integration@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        UUID customerId = UUID.fromString(objectMapper.readTree(
                customerResult.getResponse().getContentAsString()
        ).get("id").asText());

        MvcResult accountResult = mockMvc.perform(post(
                            "/api/v1/customers/{customerId}/accounts",
                            customerId
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"RUB\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        JsonNode accountResponse = objectMapper.readTree(accountResult.getResponse().getContentAsString());
        UUID accountId = UUID.fromString(accountResponse.get("id").asText());
        String accountNumber = accountResponse.get("accountNumber").asText();
        assertThat(accountNumber).matches("[1-9][0-9]{19}");
        assertThat(accountResult.getResponse().getContentAsString()).contains("\"balance\":0.00");
        assertThat(accountResult.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/accounts/" + accountId);

        mockMvc.perform(get("/api/v1/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(accountNumber));

        mockMvc.perform(get("/api/v1/customers/{customerId}/accounts", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(accountId.toString()));

        AccountEntity persistedAccount = accountRepository.findById(accountId).orElseThrow();
        assertThat(persistedAccount.getCustomer().getId()).isEqualTo(customerId);
        assertThat(persistedAccount.getBalance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(persistedAccount.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(persistedAccount.getCurrency()).isEqualTo(Currency.RUB);
    }

    private void cleanDatabase() {
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }
}
