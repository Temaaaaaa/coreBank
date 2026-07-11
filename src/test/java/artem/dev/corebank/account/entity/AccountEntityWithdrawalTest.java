package artem.dev.corebank.account.entity;

import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountEntityWithdrawalTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-12T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-12T11:00:00Z");

    @Test
    void withdrawsMoneyAndUpdatesTimestamp() {
        AccountEntity account = activeAccount("1000.00");

        account.withdraw(new BigDecimal("250.00"), UPDATED_AT);

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("750.00"));
        assertThat(account.getBalance().scale()).isEqualTo(2);
        assertThat(account.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void allowsWithdrawingEntireBalance() {
        AccountEntity account = activeAccount("100.00");

        account.withdraw(new BigDecimal("100.00"), UPDATED_AT);

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(account.getBalance().scale()).isEqualTo(2);
    }

    @Test
    void repeatedWithdrawalsAreSubtracted() {
        AccountEntity account = activeAccount("1000.00");

        account.withdraw(new BigDecimal("100.00"), UPDATED_AT);
        account.withdraw(new BigDecimal("200.00"), UPDATED_AT.plusSeconds(1));

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("700.00"));
    }

    @Test
    void rejectsNullZeroNegativeAndInvalidScale() {
        assertError(null, "INVALID_AMOUNT");
        assertError(new BigDecimal("0.00"), "INVALID_AMOUNT");
        assertError(new BigDecimal("-1.00"), "INVALID_AMOUNT");
        assertError(new BigDecimal("1.001"), "INVALID_AMOUNT_SCALE");
    }

    @Test
    void rejectsInsufficientFundsWithoutChangingBalance() {
        AccountEntity account = activeAccount("100.00");

        assertThatThrownBy(() -> account.withdraw(new BigDecimal("100.01"), UPDATED_AT))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(exception.getMessage()).isEqualTo("Insufficient funds on the account");
                });
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("100.00"));
        assertThat(account.getBalance()).isNotNegative();
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rejectsBlockedAndClosedAccounts() {
        assertInactive(AccountStatus.BLOCKED);
        assertInactive(AccountStatus.CLOSED);
    }

    private void assertError(BigDecimal amount, String code) {
        AccountEntity account = activeAccount("100.00");
        assertThatThrownBy(() -> account.withdraw(amount, UPDATED_AT))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(code));
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("100.00"));
    }

    private void assertInactive(AccountStatus status) {
        AccountEntity account = account("100.00", status);
        assertThatThrownBy(() -> account.withdraw(new BigDecimal("10.00"), UPDATED_AT))
                .isInstanceOfSatisfying(BusinessRuleException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("100.00"));
    }

    private AccountEntity activeAccount(String balance) {
        return account(balance, AccountStatus.ACTIVE);
    }

    private AccountEntity account(String balance, AccountStatus status) {
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(), "Test", "Owner", UUID.randomUUID() + "@example.com", CREATED_AT, CREATED_AT
        );
        return new AccountEntity(
                UUID.randomUUID(), "12345678901234567890", customer, Currency.RUB,
                new BigDecimal(balance), status, CREATED_AT, CREATED_AT
        );
    }
}
