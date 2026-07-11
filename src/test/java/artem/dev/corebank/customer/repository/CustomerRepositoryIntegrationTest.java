package artem.dev.corebank.customer.repository;

import artem.dev.corebank.PostgreSqlTestConfiguration;
import artem.dev.corebank.customer.entity.CustomerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CustomerRepositoryIntegrationTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanCustomers() {
        customerRepository.deleteAll();
        customerRepository.flush();
    }

    @Test
    void savesAndReadsCustomerWithExactTimestamps() {
        CustomerEntity customer = customer(UUID.randomUUID(), "saved@example.com");

        customerRepository.saveAndFlush(customer);
        CustomerEntity loaded = customerRepository.findById(customer.getId()).orElseThrow();

        assertThat(loaded.getFirstName()).isEqualTo("Artem");
        assertThat(loaded.getLastName()).isEqualTo("Ivanov");
        assertThat(loaded.getEmail()).isEqualTo("saved@example.com");
        assertThat(loaded.getCreatedAt()).isEqualTo(FIXED_TIME);
        assertThat(loaded.getUpdatedAt()).isEqualTo(FIXED_TIME);
    }

    @Test
    void databaseRejectsDuplicateEmail() {
        customerRepository.saveAndFlush(customer(UUID.randomUUID(), "duplicate@example.com"));

        assertThatThrownBy(() -> customerRepository.saveAndFlush(
                customer(UUID.randomUUID(), "duplicate@example.com")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNullFirstName() {
        Throwable thrown = catchThrowable(() -> jdbcTemplate.update(
                """
                INSERT INTO customers (id, first_name, last_name, email, created_at, updated_at)
                VALUES (?, NULL, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                "Ivanov",
                "null-name@example.com"
        ));

        assertThat(thrown).isInstanceOf(DataAccessException.class);
        assertThat(thrown).hasRootCauseInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(sqlException.getSQLState()).isEqualTo("23502");
    }

    @Test
    void flywayAppliedCustomerMigration() {
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'",
                Boolean.class
        );
        String customersTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.customers')::text",
                String.class
        );

        assertThat(migrationSucceeded).isTrue();
        assertThat(customersTable).isEqualTo("customers");
    }

    private CustomerEntity customer(UUID id, String email) {
        return new CustomerEntity(id, "Artem", "Ivanov", email, FIXED_TIME, FIXED_TIME);
    }
}
