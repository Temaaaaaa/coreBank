package artem.dev.corebank.transaction.controller;

import artem.dev.corebank.account.entity.Currency;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.TransactionHistoryRequest;
import artem.dev.corebank.transaction.dto.TransactionPageResponse;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.entity.TransactionType;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MoneyOperationController.class)
class TransactionHistoryControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TRANSACTION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
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
    void returnsDefaultPageContractAndDefaultQueryValues() throws Exception {
        when(service.getAccountTransactions(eq(ACCOUNT_ID), any(TransactionHistoryRequest.class)))
                .thenReturn(pageResponse(List.of(depositResponse()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(TRANSACTION_ID.toString()))
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[0].sourceAccountId").doesNotExist())
                .andExpect(jsonPath("$.content[0].targetAccountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<TransactionHistoryRequest> requestCaptor =
                ArgumentCaptor.forClass(TransactionHistoryRequest.class);
        verify(service).getAccountTransactions(eq(ACCOUNT_ID), requestCaptor.capture());
        TransactionHistoryRequest request = requestCaptor.getValue();
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.sortDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void acceptsFiltersExplicitPaginationMaximumSizeAndAscendingSort() throws Exception {
        when(service.getAccountTransactions(eq(ACCOUNT_ID), any(TransactionHistoryRequest.class)))
                .thenReturn(pageResponse(List.of(), 2, 100, 0, 0));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .queryParam("type", "TRANSFER")
                        .queryParam("from", "2026-08-01T00:00:00Z")
                        .queryParam("to", "2026-08-02T00:00:00Z")
                        .queryParam("page", "2")
                        .queryParam("size", "100")
                        .queryParam("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(100));

        ArgumentCaptor<TransactionHistoryRequest> requestCaptor =
                ArgumentCaptor.forClass(TransactionHistoryRequest.class);
        verify(service).getAccountTransactions(eq(ACCOUNT_ID), requestCaptor.capture());
        TransactionHistoryRequest request = requestCaptor.getValue();
        assertThat(request.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(request.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(request.to()).isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(request.sortDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void existingAccountWithoutTransactionsReturnsEmptyPage() throws Exception {
        when(service.getAccountTransactions(eq(ACCOUNT_ID), any(TransactionHistoryRequest.class)))
                .thenReturn(pageResponse(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void missingAccountReturnsExistingNotFoundContract() throws Exception {
        when(service.getAccountTransactions(eq(ACCOUNT_ID), any(TransactionHistoryRequest.class)))
                .thenThrow(new ResourceNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account with id " + ACCOUNT_ID + " was not found"
                ));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/accounts/" + ACCOUNT_ID + "/transactions"));
    }

    @Test
    void malformedAccountIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid/transactions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(service, never()).getAccountTransactions(any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidSingleParameters")
    void invalidSingleQueryParameterReturnsBadRequest(String name, String value) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .queryParam(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());

        verify(service, never()).getAccountTransactions(any(), any());
    }

    @Test
    void fromAfterToReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .queryParam("from", "2026-08-02T00:00:00Z")
                        .queryParam("to", "2026-08-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownOrRepeatedQueryParameterReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .queryParam("foo", "bar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .queryParam("page", "0", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void strictQueryValidationDoesNotChangeSingleTransactionLookup() throws Exception {
        when(service.getTransactionById(TRANSACTION_ID)).thenReturn(depositResponse());

        mockMvc.perform(get("/api/v1/transactions/{transactionId}", TRANSACTION_ID)
                        .queryParam("foo", "bar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TRANSACTION_ID.toString()));
    }

    private static Stream<Arguments> invalidSingleParameters() {
        return Stream.of(
                Arguments.of("type", "REFUND"),
                Arguments.of("from", "yesterday"),
                Arguments.of("to", "tomorrow"),
                Arguments.of("page", "-1"),
                Arguments.of("page", "one"),
                Arguments.of("size", "0"),
                Arguments.of("size", "-1"),
                Arguments.of("size", "101"),
                Arguments.of("size", "many"),
                Arguments.of("sort", "amount,desc"),
                Arguments.of("sort", "createdAt,sideways"),
                Arguments.of("sort", "createdAt"),
                Arguments.of("sort", "createdAt,desc,extra")
        );
    }

    private TransactionPageResponse pageResponse(
            List<TransactionResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        return new TransactionPageResponse(content, page, size, totalElements, totalPages);
    }

    private TransactionResponse depositResponse() {
        return new TransactionResponse(
                TRANSACTION_ID,
                TransactionType.DEPOSIT,
                new BigDecimal("250.00"),
                Currency.RUB,
                null,
                ACCOUNT_ID,
                "History test",
                CREATED_AT
        );
    }
}
