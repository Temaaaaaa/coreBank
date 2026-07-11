package artem.dev.corebank.transaction.entity;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.Currency;
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
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "account_transactions")
public class AccountTransactionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private Currency currency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id", updatable = false)
    private AccountEntity sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_account_id", updatable = false)
    private AccountEntity targetAccount;

    @Column(name = "description", length = 255, updatable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountTransactionEntity() {
    }

    private AccountTransactionEntity(
            UUID id,
            TransactionType type,
            BigDecimal amount,
            Currency currency,
            AccountEntity sourceAccount,
            AccountEntity targetAccount,
            String description,
            Instant createdAt
    ) {
        this.id = id;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.sourceAccount = sourceAccount;
        this.targetAccount = targetAccount;
        this.description = description;
        this.createdAt = createdAt;
    }

    public static AccountTransactionEntity deposit(
            UUID id,
            BigDecimal amount,
            AccountEntity targetAccount,
            String description,
            Instant createdAt
    ) {
        Objects.requireNonNull(targetAccount, "targetAccount must not be null");
        return new AccountTransactionEntity(
                id,
                TransactionType.DEPOSIT,
                amount,
                targetAccount.getCurrency(),
                null,
                targetAccount,
                description,
                createdAt
        );
    }

    public static AccountTransactionEntity withdrawal(
            UUID id,
            BigDecimal amount,
            AccountEntity sourceAccount,
            String description,
            Instant createdAt
    ) {
        Objects.requireNonNull(sourceAccount, "sourceAccount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return new AccountTransactionEntity(
                id,
                TransactionType.WITHDRAWAL,
                amount,
                sourceAccount.getCurrency(),
                sourceAccount,
                null,
                description,
                createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public AccountEntity getSourceAccount() {
        return sourceAccount;
    }

    public AccountEntity getTargetAccount() {
        return targetAccount;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
