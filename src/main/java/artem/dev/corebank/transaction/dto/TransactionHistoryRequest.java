package artem.dev.corebank.transaction.dto;

import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.transaction.entity.TransactionType;
import org.springframework.data.domain.Sort;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record TransactionHistoryRequest(
        TransactionType type,
        Instant from,
        Instant to,
        int page,
        int size,
        Sort.Direction sortDirection
) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String DEFAULT_SORT = "createdAt,desc";

    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "type", "from", "to", "page", "size", "sort"
    );

    public TransactionHistoryRequest {
        if (page < 0) {
            throw invalidRequest("Page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalidRequest("Size must be between 1 and " + MAX_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw invalidRequest("From timestamp must not be after to timestamp");
        }
        if (sortDirection == null) {
            throw invalidRequest("Sort direction must be specified");
        }
    }

    public static TransactionHistoryRequest from(Map<String, List<String>> parameters) {
        validateParameterNames(parameters);
        return new TransactionHistoryRequest(
                parseType(singleValue(parameters, "type")),
                parseInstant(singleValue(parameters, "from"), "from"),
                parseInstant(singleValue(parameters, "to"), "to"),
                parseInteger(singleValue(parameters, "page"), "page", DEFAULT_PAGE),
                parseInteger(singleValue(parameters, "size"), "size", DEFAULT_SIZE),
                parseSort(singleValue(parameters, "sort"))
        );
    }

    private static void validateParameterNames(Map<String, List<String>> parameters) {
        parameters.keySet().stream()
                .filter(parameter -> !SUPPORTED_PARAMETERS.contains(parameter))
                .findFirst()
                .ifPresent(parameter -> {
                    throw invalidRequest("Unsupported query parameter: " + parameter);
                });
    }

    private static String singleValue(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw invalidRequest("Query parameter " + name + " must have one non-blank value");
        }
        return values.getFirst();
    }

    private static TransactionType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return TransactionType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw invalidRequest("Unsupported transaction type");
        }
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException exception) {
            throw invalidRequest("Query parameter " + name + " must be an ISO-8601 instant");
        }
    }

    private static int parseInteger(String value, String name, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidRequest("Query parameter " + name + " must be an integer");
        }
    }

    private static Sort.Direction parseSort(String value) {
        String sort = value == null ? DEFAULT_SORT : value;
        return switch (sort) {
            case "createdAt,asc" -> Sort.Direction.ASC;
            case "createdAt,desc" -> Sort.Direction.DESC;
            default -> throw invalidRequest("Sort must be createdAt,asc or createdAt,desc");
        };
    }

    private static BusinessRuleException invalidRequest(String message) {
        return new BusinessRuleException("INVALID_REQUEST", message);
    }
}
