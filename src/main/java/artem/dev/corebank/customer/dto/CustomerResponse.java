package artem.dev.corebank.customer.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        Instant createdAt,
        Instant updatedAt
) {
}
