package artem.dev.corebank.account.entity;

import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountEntityDepositTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-11T18:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-11T19:00:00Z");

    @Test
    void depositsAmountAndUpdatesTimestamp() {
        AccountEntity account = account(AccountStatus.ACTIVE, new BigDecimal("0.00"));

        account.deposit(new BigDecimal("100.00"), UPDATED_AT);

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("100.00"));
        assertThat(account.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void repeatedDepositsAreAdded() {
        AccountEntity account = account(AccountStatus.ACTIVE, new BigDecimal("0.00"));

        account.deposit(new BigDecimal("100.00"), UPDATED_AT);
        account.deposit(new BigDecimal("25.50"), UPDATED_AT.plusSeconds(1));

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("125.50"));
    }

    @Test
    void depositKeepsBalanceScaleTwo() {
        AccountEntity account = account(AccountStatus.ACTIVE, new BigDecimal("0.00"));

        account.deposit(new BigDecimal("1"), UPDATED_AT);

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("1.00"));
        assertThat(account.getBalance().scale()).isEqualTo(2);
    }

    @Test
    void rejectsNullAmount() {
        assertInvalidAmount(null, "INVALID_AMOUNT");
    }

    @Test
    void rejectsZeroAmount() {
        assertInvalidAmount(new BigDecimal("0.00"), "INVALID_AMOUNT");
    }

    @Test
    void rejectsNegativeAmount() {
        assertInvalidAmount(new BigDecimal("-1.00"), "INVALID_AMOUNT");
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimalPlaces() {
        assertInvalidAmount(new BigDecimal("1.001"), "INVALID_AMOUNT_SCALE");
    }

    @Test
    void rejectsDepositForBlockedAccount() {
        assertInactive(AccountStatus.BLOCKED);
    }

    @Test
    void rejectsDepositForClosedAccount() {
        assertInactive(AccountStatus.CLOSED);
    }

    private void assertInvalidAmount(BigDecimal amount, String expectedCode) {
        AccountEntity account = account(AccountStatus.ACTIVE, new BigDecimal("0.00"));

        assertThatThrownBy(() -> account.deposit(amount, UPDATED_AT))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    private void assertInactive(AccountStatus status) {
        AccountEntity account = account(status, new BigDecimal("0.00"));

        assertThatThrownBy(() -> account.deposit(new BigDecimal("10.00"), UPDATED_AT))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.00"));
    }

    private AccountEntity account(AccountStatus status, BigDecimal balance) {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(),
                "Artem",
                "Ivanov",
                "deposit-owner@example.com",
                CREATED_AT,
                CREATED_AT
        );
        return new AccountEntity(
                UUID.randomUUID(),
                "12345678901234567890",
                customer,
                Currency.RUB,
                balance,
                status,
                CREATED_AT,
                CREATED_AT
        );
    }
}
