package artem.dev.corebank.account.entity;

import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 20, updatable = false)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private CustomerEntity customer;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
    }

    public AccountEntity(
            UUID id,
            String accountNumber,
            CustomerEntity customer,
            Currency currency,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.customer = customer;
        this.currency = currency;
        this.balance = ZERO_BALANCE;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    AccountEntity(
            UUID id,
            String accountNumber,
            CustomerEntity customer,
            Currency currency,
            BigDecimal balance,
            AccountStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.customer = customer;
        this.currency = currency;
        this.balance = balance;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void deposit(BigDecimal amount, Instant updatedAt) {
        validateMoneyOperation(amount);
        this.balance = this.balance.add(amount);
        this.updatedAt = updatedAt;
    }

    public void withdraw(BigDecimal amount, Instant updatedAt) {
        validateMoneyOperation(amount);
        if (balance.compareTo(amount) < 0) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Insufficient funds on the account");
        }
        this.balance = this.balance.subtract(amount);
        this.updatedAt = updatedAt;
    }

    private void validateMoneyOperation(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        if (amount.scale() > 2) {
            throw new BusinessRuleException(
                    "INVALID_AMOUNT_SCALE",
                    "Amount must have at most two decimal places"
            );
        }
        if (status != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public CustomerEntity getCustomer() {
        return customer;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
