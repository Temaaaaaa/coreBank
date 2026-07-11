package artem.dev.corebank.transaction.controller;

import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
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
class MoneyOperationWithdrawalControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRANSACTION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant FIXED_TIME = Instant.parse("2026-07-12T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MoneyOperationService service;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_TIME);
    }

    @Test
    void withdrawalReturnsCreatedTransactionAndLocation() throws Exception {
        when(service.withdraw(any(UUID.class), any(WithdrawalRequest.class)))
                .thenReturn(response());

        mockMvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":500.00,"description":"ATM withdrawal"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transactions/" + TRANSACTION_ID))
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.targetAccountId").doesNotExist())
                .andExpect(jsonPath("$.description").value("ATM withdrawal"));
    }

    @Test
    void invalidAmountsReturnBadRequest() throws Exception {
        assertValidationError("{\"amount\":null}");
        assertValidationError("{\"amount\":0.00}");
        assertValidationError("{\"amount\":-1.00}");
        assertValidationError("{\"amount\":1.001}");
        assertValidationError("{\"amount\":100000000000000000.00}");
    }

    @Test
    void missingAccountReturnsNotFound() throws Exception {
        when(service.withdraw(any(UUID.class), any(WithdrawalRequest.class)))
                .thenThrow(new ResourceNotFoundException("ACCOUNT_NOT_FOUND", "Account was not found"));
        assertBusinessError("ACCOUNT_NOT_FOUND", status().isNotFound());
    }

    @Test
    void inactiveAccountReturnsConflict() throws Exception {
        when(service.withdraw(any(UUID.class), any(WithdrawalRequest.class)))
                .thenThrow(new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active"));
        assertBusinessError("ACCOUNT_NOT_ACTIVE", status().isConflict());
    }

    @Test
    void insufficientFundsReturnsConflict() throws Exception {
        when(service.withdraw(any(UUID.class), any(WithdrawalRequest.class)))
                .thenThrow(new BusinessRuleException("INSUFFICIENT_FUNDS", "Insufficient funds on the account"));
        assertBusinessError("INSUFFICIENT_FUNDS", status().isConflict());
    }

    @Test
    void malformedAccountIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/not-a-uuid/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void descriptionOverMaximumLengthReturnsBadRequest() throws Exception {
        String description = "a".repeat(256);
        mockMvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":10.00,"description":"%s"}
                                """.formatted(description)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value("description"));
    }

    private void assertValidationError(String body) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("amount"));
    }

    private void assertBusinessError(
            String code,
            org.springframework.test.web.servlet.ResultMatcher statusMatcher
    ) throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", ACCOUNT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(statusMatcher)
                .andExpect(jsonPath("$.code").value(code));
    }

    private TransactionResponse response() {
        return new TransactionResponse(
                TRANSACTION_ID,
                TransactionType.WITHDRAWAL,
                new BigDecimal("500.00"),
                Currency.RUB,
                ACCOUNT_ID,
                null,
                "ATM withdrawal",
                FIXED_TIME
        );
    }
}
