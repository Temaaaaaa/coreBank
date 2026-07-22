package artem.dev.corebank.transaction.controller;

import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.TransferRequest;
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
class MoneyOperationTransferControllerTest {

    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant FIXED_TIME = Instant.parse("2026-07-23T10:00:00Z");

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
    void transferReturnsCreatedTransactionAndLocation() throws Exception {
        when(service.transfer(any(TransferRequest.class))).thenReturn(response());

        mockMvc.perform(validRequest())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transactions/" + TRANSACTION_ID))
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.sourceAccountId").value(SOURCE_ID.toString()))
                .andExpect(jsonPath("$.targetAccountId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.description").value("Transfer between accounts"))
                .andExpect(jsonPath("$.createdAt").value(FIXED_TIME.toString()));
    }

    @Test
    void missingSourceAccountIdReturnsValidationError() throws Exception {
        assertValidationError(json(null, TARGET_ID.toString(), "500.00", null), "sourceAccountId");
    }

    @Test
    void missingTargetAccountIdReturnsValidationError() throws Exception {
        assertValidationError(json(SOURCE_ID.toString(), null, "500.00", null), "targetAccountId");
    }

    @Test
    void invalidAmountsReturnValidationError() throws Exception {
        assertValidationError(json(SOURCE_ID.toString(), TARGET_ID.toString(), null, null), "amount");
        assertValidationError(json(SOURCE_ID.toString(), TARGET_ID.toString(), "0.00", null), "amount");
        assertValidationError(json(SOURCE_ID.toString(), TARGET_ID.toString(), "-1.00", null), "amount");
        assertValidationError(json(SOURCE_ID.toString(), TARGET_ID.toString(), "1.001", null), "amount");
        assertValidationError(
                json(SOURCE_ID.toString(), TARGET_ID.toString(), "100000000000000000.00", null),
                "amount"
        );
    }

    @Test
    void descriptionOverMaximumLengthReturnsValidationError() throws Exception {
        assertValidationError(
                json(SOURCE_ID.toString(), TARGET_ID.toString(), "10.00", "a".repeat(256)),
                "description"
        );
    }

    @Test
    void malformedUuidReturnsMalformedJsonError() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("not-a-uuid", TARGET_ID.toString(), "10.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").value("Request body contains malformed JSON"))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    @Test
    void malformedJsonReturnsMalformedJsonError() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"));
    }

    @Test
    void sameAccountsReturnConflict() throws Exception {
        assertBusinessError(
                new BusinessRuleException("SAME_ACCOUNT_TRANSFER", "Source and target accounts must be different"),
                "SAME_ACCOUNT_TRANSFER",
                "Source and target accounts must be different",
                409
        );
    }

    @Test
    void missingSourceReturnsNotFound() throws Exception {
        assertNotFound("Source account was not found");
    }

    @Test
    void missingTargetReturnsNotFound() throws Exception {
        assertNotFound("Target account was not found");
    }

    @Test
    void inactiveSourceReturnsConflict() throws Exception {
        assertBusinessError(
                new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active"),
                "ACCOUNT_NOT_ACTIVE",
                "Account must be active",
                409
        );
    }

    @Test
    void inactiveTargetReturnsConflict() throws Exception {
        assertBusinessError(
                new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active"),
                "ACCOUNT_NOT_ACTIVE",
                "Account must be active",
                409
        );
    }

    @Test
    void currencyMismatchReturnsConflict() throws Exception {
        assertBusinessError(
                new BusinessRuleException(
                        "CURRENCY_MISMATCH",
                        "Source and target accounts must use the same currency"
                ),
                "CURRENCY_MISMATCH",
                "Source and target accounts must use the same currency",
                409
        );
    }

    @Test
    void insufficientFundsReturnsConflict() throws Exception {
        assertBusinessError(
                new BusinessRuleException("INSUFFICIENT_FUNDS", "Insufficient funds on the account"),
                "INSUFFICIENT_FUNDS",
                "Insufficient funds on the account",
                409
        );
    }

    private void assertValidationError(String body, String field) throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.validationErrors[0].field").value(field));
    }

    private void assertNotFound(String message) throws Exception {
        when(service.transfer(any(TransferRequest.class)))
                .thenThrow(new ResourceNotFoundException("ACCOUNT_NOT_FOUND", message));
        mockMvc.perform(validRequest())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    private void assertBusinessError(
            BusinessRuleException exception,
            String code,
            String message,
            int httpStatus
    ) throws Exception {
        when(service.transfer(any(TransferRequest.class))).thenThrow(exception);
        mockMvc.perform(validRequest())
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(
                        SOURCE_ID.toString(),
                        TARGET_ID.toString(),
                        "500.00",
                        "Transfer between accounts"
                ));
    }

    private String json(String sourceId, String targetId, String amount, String description) {
        return """
                {
                  "sourceAccountId": %s,
                  "targetAccountId": %s,
                  "amount": %s,
                  "description": %s
                }
                """.formatted(jsonString(sourceId), jsonString(targetId), amount, jsonString(description));
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private TransactionResponse response() {
        return new TransactionResponse(
                TRANSACTION_ID,
                TransactionType.TRANSFER,
                new BigDecimal("500.00"),
                Currency.RUB,
                SOURCE_ID,
                TARGET_ID,
                "Transfer between accounts",
                FIXED_TIME
        );
    }
}
