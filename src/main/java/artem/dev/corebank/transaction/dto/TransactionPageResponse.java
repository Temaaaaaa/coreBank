package artem.dev.corebank.transaction.dto;

import java.util.List;

public record TransactionPageResponse(
        List<TransactionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public TransactionPageResponse {
        content = List.copyOf(content);
    }
}
