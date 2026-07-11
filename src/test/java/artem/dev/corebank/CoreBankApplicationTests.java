package artem.dev.corebank;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import(PostgreSqlTestConfiguration.class)
@SpringBootTest
class CoreBankApplicationTests {

    @Test
    void contextLoads() {
    }
}
