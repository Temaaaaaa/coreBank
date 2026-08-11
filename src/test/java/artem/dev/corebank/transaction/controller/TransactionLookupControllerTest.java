package artem.dev.corebank.transaction.controller;

import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MoneyOperationController.class)
class TransactionLookupControllerTest {

    private static final UUID TRANSACTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SOURCE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MoneyOperationService service;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(CREATED_AT);
    }

    @Test
    void returnsDepositTransaction() throws Exception {
        when(service.getTransactionById(TRANSACTION_ID)).thenReturn(response(
                TransactionType.DEPOSIT, null, TARGET_ID
        ));

        mockMvc.perform(get("/api/v1/transactions/{transactionId}", TRANSACTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").doesNotExist())
                .andExpect(jsonPath("$.targetAccountId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.description").value("Lookup test"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));
    }

    @Test
    void returnsWithdrawalTransaction() throws Exception {
        when(service.getTransactionById(TRANSACTION_ID)).thenReturn(response(
                TransactionType.WITHDRAWAL, SOURCE_ID, null
        ));

        mockMvc.perform(get("/api/v1/transactions/{transactionId}", TRANSACTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.sourceAccountId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.targetAccountId").doesNotExist());
    }

    @Test
    void returnsTransferTransaction() throws Exception {
        when(service.getTransactionById(TRANSACTION_ID)).thenReturn(response(
                TransactionType.TRANSFER, SOURCE_ID, TARGET_ID
        ));

        mockMvc.perform(get("/api/v1/transactions/{transactionId}", TRANSACTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.sourceAccountId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.targetAccountId").value(TARGET_ID.toString()));
    }

    @Test
    void missingTransactionReturnsNotFoundErrorContract() throws Exception {
        when(service.getTransactionById(TRANSACTION_ID)).thenThrow(new ResourceNotFoundException(
                "TRANSACTION_NOT_FOUND",
                "Transaction with id " + TRANSACTION_ID + " was not found"
        ));

        mockMvc.perform(get("/api/v1/transactions/{transactionId}", TRANSACTION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.path").value("/api/v1/transactions/" + TRANSACTION_ID))
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "Transaction with id " + TRANSACTION_ID + " was not found"
                ))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    @Test
    void malformedTransactionIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.path").value("/api/v1/transactions/not-a-uuid"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request parameter has an invalid format"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    private TransactionResponse response(
            TransactionType type,
            UUID sourceAccountId,
            UUID targetAccountId
    ) {
        return new TransactionResponse(
                TRANSACTION_ID,
                type,
                new BigDecimal("250.00"),
                Currency.RUB,
                sourceAccountId,
                targetAccountId,
                "Lookup test",
                CREATED_AT
        );
    }
}
