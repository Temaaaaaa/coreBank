package artem.dev.corebank.account.controller;

import artem.dev.corebank.account.dto.AccountResponse;
import artem.dev.corebank.account.dto.OpenAccountRequest;
import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.account.service.AccountService;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_TIME);
    }

    @Test
    void postReturnsCreatedAccountWithLocation() throws Exception {
        when(accountService.openAccount(any(UUID.class), any(OpenAccountRequest.class)))
                .thenReturn(accountResponse());

        mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"RUB\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT_ID))
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.accountNumber").value("12345678901234567890"))
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void nullCurrencyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("currency"));
    }

    @Test
    void unknownCurrencyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"GBP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void unknownCustomerOnOpenReturnsNotFound() throws Exception {
        when(accountService.openAccount(any(UUID.class), any(OpenAccountRequest.class)))
                .thenThrow(customerNotFound());

        mockMvc.perform(post("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void getReturnsAccount() throws Exception {
        when(accountService.getAccountById(ACCOUNT_ID)).thenReturn(accountResponse());

        mockMvc.perform(get("/api/v1/accounts/{accountId}", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.accountNumber").value("12345678901234567890"));
    }

    @Test
    void missingAccountReturnsNotFound() throws Exception {
        when(accountService.getAccountById(ACCOUNT_ID)).thenThrow(new ResourceNotFoundException(
                "ACCOUNT_NOT_FOUND",
                "Account with id " + ACCOUNT_ID + " was not found"
        ));

        mockMvc.perform(get("/api/v1/accounts/{accountId}", ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void getCustomerAccountsReturnsArray() throws Exception {
        when(accountService.getAccountsByCustomerId(CUSTOMER_ID)).thenReturn(List.of(accountResponse()));

        mockMvc.perform(get("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(ACCOUNT_ID.toString()));
    }

    @Test
    void existingCustomerWithoutAccountsReturnsEmptyArray() throws Exception {
        when(accountService.getAccountsByCustomerId(CUSTOMER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void unknownCustomerAccountListReturnsNotFound() throws Exception {
        when(accountService.getAccountsByCustomerId(CUSTOMER_ID)).thenThrow(customerNotFound());

        mockMvc.perform(get("/api/v1/customers/{customerId}/accounts", CUSTOMER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void invalidAccountIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidCustomerIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/customers/not-a-uuid/accounts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private AccountResponse accountResponse() {
        return new AccountResponse(
                ACCOUNT_ID,
                "12345678901234567890",
                CUSTOMER_ID,
                Currency.RUB,
                new BigDecimal("0.00"),
                AccountStatus.ACTIVE,
                FIXED_TIME,
                FIXED_TIME
        );
    }

    private ResourceNotFoundException customerNotFound() {
        return new ResourceNotFoundException(
                "CUSTOMER_NOT_FOUND",
                "Customer with id " + CUSTOMER_ID + " was not found"
        );
    }
}
