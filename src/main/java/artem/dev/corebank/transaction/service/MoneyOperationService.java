package artem.dev.corebank.transaction.service;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.entity.AccountStatus;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.BusinessRuleException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransactionHistoryRequest;
import artem.dev.corebank.transaction.dto.TransactionPageResponse;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.TransferRequest;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.mapper.TransactionMapper;
import artem.dev.corebank.transaction.repository.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

    @Transactional
    public TransactionResponse transfer(TransferRequest request) {
        UUID sourceAccountId = request.sourceAccountId();
        UUID targetAccountId = request.targetAccountId();
        validateDifferentAccounts(sourceAccountId, targetAccountId);
        BigDecimal amount = validateAmount(request.amount());
        String description = normalizeDescription(request.description());

        LockedAccounts accounts = lockAccountsInStableOrder(sourceAccountId, targetAccountId);
        validateTransferAccounts(accounts.source(), accounts.target());

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        accounts.source().withdraw(amount, now);
        accounts.target().deposit(amount, now);
        AccountTransactionEntity transaction = AccountTransactionEntity.transfer(
                UUID.randomUUID(),
                amount,
                accounts.source(),
                accounts.target(),
                description,
                now
        );
        AccountTransactionEntity savedTransaction = transactionRepository.save(transaction);
        return transactionMapper.toResponse(savedTransaction);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionById(UUID transactionId) {
        AccountTransactionEntity transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TRANSACTION_NOT_FOUND",
                        "Transaction with id " + transactionId + " was not found"
                ));
        return transactionMapper.toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public TransactionPageResponse getAccountTransactions(
            UUID accountId,
            TransactionHistoryRequest request
    ) {
        if (!accountRepository.existsById(accountId)) {
            throw new ResourceNotFoundException(
                    "ACCOUNT_NOT_FOUND",
                    "Account with id " + accountId + " was not found"
            );
        }

        Sort sort = Sort.by(request.sortDirection(), "createdAt")
                .and(Sort.by(request.sortDirection(), "id"));
        Pageable pageable = PageRequest.of(request.page(), request.size(), sort);
        Page<AccountTransactionEntity> transactionPage = transactionRepository.findAccountHistory(
                accountId,
                request.type(),
                request.from(),
                request.to(),
                pageable
        );
        List<TransactionResponse> content = transactionPage.getContent().stream()
                .map(transactionMapper::toResponse)
                .toList();
        return new TransactionPageResponse(
                content,
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages()
        );
    }

    private void validateDifferentAccounts(UUID sourceAccountId, UUID targetAccountId) {
        if (sourceAccountId != null && sourceAccountId.equals(targetAccountId)) {
            throw new BusinessRuleException(
                    "SAME_ACCOUNT_TRANSFER",
                    "Source and target accounts must be different"
            );
        }
    }

    private LockedAccounts lockAccountsInStableOrder(UUID sourceAccountId, UUID targetAccountId) {
        UUID firstId = sourceAccountId.compareTo(targetAccountId) < 0 ? sourceAccountId : targetAccountId;
        UUID secondId = sourceAccountId.compareTo(targetAccountId) < 0 ? targetAccountId : sourceAccountId;
        AccountEntity firstAccount = findAccountForUpdate(firstId);
        AccountEntity secondAccount = findAccountForUpdate(secondId);
        if (sourceAccountId.equals(firstId)) {
            return new LockedAccounts(firstAccount, secondAccount);
        }
        return new LockedAccounts(secondAccount, firstAccount);
    }

    private void validateTransferAccounts(AccountEntity sourceAccount, AccountEntity targetAccount) {
        if (sourceAccount.getStatus() != AccountStatus.ACTIVE || targetAccount.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("ACCOUNT_NOT_ACTIVE", "Account must be active");
        }
        if (sourceAccount.getCurrency() != targetAccount.getCurrency()) {
            throw new BusinessRuleException(
                    "CURRENCY_MISMATCH",
                    "Source and target accounts must use the same currency"
            );
        }
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

    private record LockedAccounts(AccountEntity source, AccountEntity target) {
    }
}
