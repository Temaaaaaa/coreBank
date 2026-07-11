package artem.dev.corebank.transaction.controller;

import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.service.MoneyOperationService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MoneyOperationController.class)
class MoneyOperationControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRANSACTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MoneyOperationService moneyOperationService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_TIME);
    }

    @Test
    void depositReturnsCreatedTransactionAndLocation() throws Exception {
        when(moneyOperationService.deposit(any(UUID.class), any(DepositRequest.class)))
                .thenReturn(transactionResponse());

        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1500.00,"description":"Initial deposit"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transactions/" + TRANSACTION_ID))
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").doesNotExist())
                .andExpect(jsonPath("$.targetAccountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.description").value("Initial deposit"));
    }

    @Test
    void nullAmountReturnsBadRequest() throws Exception {
        assertValidationError("{\"amount\":null}");
    }

    @Test
    void zeroAmountReturnsBadRequest() throws Exception {
        assertValidationError("{\"amount\":0.00}");
    }

    @Test
    void negativeAmountReturnsBadRequest() throws Exception {
        assertValidationError("{\"amount\":-1.00}");
    }

    @Test
    void amountWithMoreThanTwoDecimalPlacesReturnsBadRequest() throws Exception {
        assertValidationError("{\"amount\":1.001}");
    }

    @Test
    void tooLargeAmountReturnsBadRequest() throws Exception {
        assertValidationError("{\"amount\":100000000000000000.00}");
    }

    @Test
    void missingAccountReturnsNotFound() throws Exception {
        when(moneyOperationService.deposit(any(UUID.class), any(DepositRequest.class)))
                .thenThrow(new ResourceNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account with id " + ACCOUNT_ID + " was not found"
                ));

        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void inactiveAccountReturnsConflict() throws Exception {
        when(moneyOperationService.deposit(any(UUID.class), any(DepositRequest.class)))
                .thenThrow(new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active"));

        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void malformedAccountIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/not-a-uuid/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void descriptionOverMaximumLengthReturnsBadRequest() throws Exception {
        String description = "a".repeat(256);

        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10.00,"description":"%s"}
                                """.formatted(description)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value("description"));
    }

    private void assertValidationError(String requestBody) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/deposits", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("amount"));
    }

    private TransactionResponse transactionResponse() {
        return new TransactionResponse(
                TRANSACTION_ID,
                TransactionType.DEPOSIT,
                new BigDecimal("1500.00"),
                Currency.RUB,
                null,
                ACCOUNT_ID,
                "Initial deposit",
                FIXED_TIME
        );
    }
}
