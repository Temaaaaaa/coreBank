package artem.dev.corebank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
@SpringBootTest
class DatabaseInfrastructureIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connectsToPostgreSqlAndVerifiesFlywayMigration() {
        Integer connectionCheck = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        String metadataTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.application_metadata')::text",
                String.class
        );
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'",
                Boolean.class
        );
        String schemaVersion = jdbcTemplate.queryForObject(
                "SELECT metadata_value FROM application_metadata WHERE metadata_key = 'schema_version'",
                String.class
        );

        assertThat(connectionCheck).isEqualTo(1);
        assertThat(metadataTable).isEqualTo("application_metadata");
        assertThat(migrationSucceeded).isTrue();
        assertThat(schemaVersion).isEqualTo("1");
    }
}
