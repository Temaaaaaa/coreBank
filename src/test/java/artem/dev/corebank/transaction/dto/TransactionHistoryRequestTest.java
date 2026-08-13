package artem.dev.corebank.transaction.dto;

import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.transaction.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionHistoryRequestTest {

    @Test
    void appliesDefaultsWhenQueryParametersAreAbsent() {
        TransactionHistoryRequest request = TransactionHistoryRequest.from(Map.of());

        assertThat(request.type()).isNull();
        assertThat(request.from()).isNull();
        assertThat(request.to()).isNull();
        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.sortDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void parsesAllSupportedParameters() {
        TransactionHistoryRequest request = TransactionHistoryRequest.from(Map.of(
                "type", List.of("TRANSFER"),
                "from", List.of("2026-08-01T00:00:00Z"),
                "to", List.of("2026-08-02T00:00:00Z"),
                "page", List.of("2"),
                "size", List.of("100"),
                "sort", List.of("createdAt,asc")
        ));

        assertThat(request.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(request.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(request.to()).isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
        assertThat(request.page()).isEqualTo(2);
        assertThat(request.size()).isEqualTo(100);
        assertThat(request.sortDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void acceptsEachSupportedTransactionType() {
        for (TransactionType type : TransactionType.values()) {
            assertThat(TransactionHistoryRequest.from(Map.of("type", List.of(type.name()))).type())
                    .isEqualTo(type);
        }
    }

    @Test
    void acceptsFromOnlyToOnlyAndInclusiveEqualRange() {
        Instant boundary = Instant.parse("2026-08-01T00:00:00Z");

        assertThat(TransactionHistoryRequest.from(Map.of("from", List.of(boundary.toString()))).from())
                .isEqualTo(boundary);
        assertThat(TransactionHistoryRequest.from(Map.of("to", List.of(boundary.toString()))).to())
                .isEqualTo(boundary);
        TransactionHistoryRequest equalRange = TransactionHistoryRequest.from(Map.of(
                "from", List.of(boundary.toString()),
                "to", List.of(boundary.toString())
        ));
        assertThat(equalRange.from()).isEqualTo(equalRange.to());
    }

    @Test
    void rejectsUnsupportedOrRepeatedParameters() {
        assertInvalid(Map.of("foo", List.of("bar")));
        assertInvalid(Map.of("page", List.of("0", "1")));
    }

    @Test
    void rejectsInvalidTypeAndTimestamps() {
        assertInvalid(Map.of("type", List.of("REFUND")));
        assertInvalid(Map.of("from", List.of("yesterday")));
        assertInvalid(Map.of("to", List.of("tomorrow")));
        assertInvalid(Map.of(
                "from", List.of("2026-08-02T00:00:00Z"),
                "to", List.of("2026-08-01T00:00:00Z")
        ));
    }

    @Test
    void rejectsInvalidPagination() {
        assertInvalid(Map.of("page", List.of("-1")));
        assertInvalid(Map.of("page", List.of("one")));
        assertInvalid(Map.of("size", List.of("0")));
        assertInvalid(Map.of("size", List.of("-1")));
        assertInvalid(Map.of("size", List.of("101")));
        assertInvalid(Map.of("size", List.of("many")));
    }

    @Test
    void rejectsUnsupportedOrMalformedSorting() {
        assertInvalid(Map.of("sort", List.of("amount,desc")));
        assertInvalid(Map.of("sort", List.of("createdAt,sideways")));
        assertInvalid(Map.of("sort", List.of("createdAt")));
        assertInvalid(Map.of("sort", List.of("createdAt,desc,extra")));
    }

    private void assertInvalid(Map<String, List<String>> parameters) {
        assertThatThrownBy(() -> TransactionHistoryRequest.from(parameters))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("INVALID_REQUEST"));
    }
}
