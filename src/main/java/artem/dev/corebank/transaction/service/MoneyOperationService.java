package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.mapper.TransactionMapper;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class MoneyOperationService {

    private static final int DESCRIPTION_MAX_LENGTH = 255;

    private final AccountRepository accountRepository;
    private final AccountTransactionRepository transactionRepository;
    private final TransactionMapper transactionMapper;
    private final Clock clock;

    public MoneyOperationService(
            AccountRepository accountRepository,
            AccountTransactionRepository transactionRepository,
            TransactionMapper transactionMapper,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.transactionMapper = transactionMapper;
        this.clock = clock;
    }

    @Transactional
    public TransactionResponse deposit(UUID accountId, DepositRequest request) {
        BigDecimal amount = validateAmount(request.amount());
        String description = normalizeDescription(request.description());
        AccountEntity account = findAccountForUpdate(accountId);

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        account.deposit(amount, now);
        AccountTransactionEntity transaction = AccountTransactionEntity.deposit(
                UUID.randomUUID(),
                amount,
                account,
                description,
                now
        );
        AccountTransactionEntity savedTransaction = transactionRepository.save(transaction);
        return transactionMapper.toResponse(savedTransaction);
    }

    @Transactional
    public TransactionResponse withdraw(UUID accountId, WithdrawalRequest request) {
        BigDecimal amount = validateAmount(request.amount());
        String description = normalizeDescription(request.description());
        AccountEntity account = findAccountForUpdate(accountId);

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        account.withdraw(amount, now);
        AccountTransactionEntity transaction = AccountTransactionEntity.withdrawal(
                UUID.randomUUID(),
                amount,
                account,
                description,
                now
        );
        AccountTransactionEntity savedTransaction = transactionRepository.save(transaction);
        return transactionMapper.toResponse(savedTransaction);
    }

    private AccountEntity findAccountForUpdate(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account with id " + accountId + " was not found"
                ));
    }

    private BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        if (amount.scale() > 2) {
            throw new BusinessRuleException(
                    "INVALID_AMOUNT_SCALE",
                    "Amount must have at most two decimal places"
            );
        }
        if (amount.precision() - amount.scale() > 17) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Amount is too large");
        }
        return amount;
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalized = description.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > DESCRIPTION_MAX_LENGTH) {
            throw new BusinessRuleException("INVALID_REQUEST", "Description is too long");
        }
        return normalized;
    }
}
