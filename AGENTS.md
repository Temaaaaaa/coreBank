# CoreBank Lite — Agent Instructions

## Project purpose

CoreBank Lite is an educational banking REST API built as a portfolio project.

The project must demonstrate:

- clear Spring Boot architecture;
- safe handling of monetary operations;
- database migrations;
- validation and error handling;
- unit and integration testing;
- Docker-based local setup;
- understandable production-style code.

The code must remain understandable to a Junior Java developer.

## Technology constraints

Use:

- Java 21
- Maven
- Spring Boot
- Spring Web
- Spring Data JPA
- PostgreSQL
- Flyway
- Jakarta Bean Validation
- Springdoc OpenAPI
- JUnit 5
- Mockito
- Testcontainers
- Docker Compose

Do not introduce additional infrastructure without explicit approval.

Do not use:

- Lombok
- MapStruct
- Kafka
- Redis
- Kubernetes
- microservices
- event sourcing
- CQRS
- unnecessary design patterns

## Architecture

Use a feature-based package structure.

Expected top-level features:

- customer
- account
- transaction
- common

Each feature may contain:

- controller
- service
- repository
- entity
- mapper
- dto

Rules:

- Controllers handle HTTP concerns only.
- Controllers must not contain business logic.
- Services implement business rules.
- Repositories handle persistence only.
- JPA entities must never be returned directly from controllers.
- Request and response DTOs must be separate where their responsibilities differ.
- Mapping must be explicit and readable.
- Avoid generic base controllers, services, and repositories.
- Prefer composition over inheritance.
- Avoid abstractions that have only one implementation unless they create a real boundary.

## Domain rules

- Use UUID identifiers.
- Use BigDecimal for monetary values.
- Never use double or float for money.
- Monetary values must have an explicitly defined scale and rounding policy.
- Account balance must never become negative.
- Transfer amount must be greater than zero.
- Source and target accounts must be different.
- Both accounts must exist and be active.
- Currency compatibility must be validated.
- Balance-changing operations must execute transactionally.
- Concurrent balance changes must be handled safely.
- Every successful monetary operation must create a transaction record.
- Failed operations must not partially change account balances.
- Store timestamps in UTC.
- Use enums for constrained domain values.

## API rules

- Use REST-oriented resource naming.
- Use proper HTTP status codes.
- Validate all external input.
- Do not expose internal exception messages.
- Use a consistent API error response.
- Include a request path, timestamp, error code, message, and validation details when relevant.
- Do not return stack traces.
- Pagination must be used for transaction history.
- Do not silently ignore unsupported query parameters.

## Database rules

- Use Flyway for all schema changes.
- Do not rely on Hibernate schema auto-creation.
- Set Hibernate DDL mode to validate.
- Define database constraints where appropriate.
- Add indexes only when justified by actual queries.
- Avoid eager fetching by default.
- Watch for N+1 query problems.
- Do not use cascade operations blindly.
- Do not expose mutable entity collections unnecessarily.

## Testing rules

For every business operation, test:

- successful scenario;
- validation failure;
- missing resource;
- business-rule violation;
- relevant boundary cases.

Use:

- unit tests for isolated service logic;
- integration tests for repositories and database behaviour;
- controller tests for HTTP contracts;
- Testcontainers for PostgreSQL integration tests.

Tests must not depend on execution order.
Tests must be deterministic.
Do not mock value objects or simple data structures.
Do not replace meaningful assertions with `assertDoesNotThrow`.

## Code quality rules

- Use descriptive names.
- Keep methods focused.
- Prefer early validation over deeply nested conditions.
- Do not leave commented-out code.
- Do not leave TODOs instead of required implementation.
- Do not catch exceptions unless they can be handled meaningfully.
- Do not use raw types.
- Do not suppress warnings without explanation.
- Do not introduce unused code.
- Avoid static mutable state.
- Keep configuration outside source code.
- Never commit real credentials or secrets.

## Workflow

Before editing:

1. Inspect relevant files.
2. Explain the intended change briefly.
3. Identify affected tests.

After editing:

1. Run relevant tests.
2. Run `./mvnw verify`.
3. Report changed files.
4. Report test and build results.
5. Mention remaining risks or assumptions.

Make the smallest coherent change necessary for the current task.
Do not implement future stages early.