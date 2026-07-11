package artem.dev.corebank.account.service;

import artem.dev.corebank.account.dto.AccountResponse;
import artem.dev.corebank.account.dto.OpenAccountRequest;
import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.account.mapper.AccountMapper;
import artem.dev.corebank.account.repository.AccountRepository;
import artem.dev.corebank.common.exception.InternalOperationException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.repository.CustomerRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccountService {

    private static final int MAX_ACCOUNT_NUMBER_ATTEMPTS = 5;
    private static final String ACCOUNT_NUMBER_CONSTRAINT = "uk_accounts_account_number";
    private static final String GENERATION_ERROR_CODE = "ACCOUNT_NUMBER_GENERATION_FAILED";
    private static final String GENERATION_ERROR_MESSAGE = "Unable to generate a unique account number";

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AccountMapper accountMapper;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            CustomerRepository customerRepository,
            AccountNumberGenerator accountNumberGenerator,
            AccountMapper accountMapper,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountMapper = accountMapper;
        this.clock = clock;
    }

    @Transactional
    public AccountResponse openAccount(UUID customerId, OpenAccountRequest request) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> customerNotFound(customerId));
        Objects.requireNonNull(request.currency(), "currency must not be null");

        String accountNumber = generateUniqueAccountNumber();
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        AccountEntity account = new AccountEntity(
                UUID.randomUUID(),
                accountNumber,
                customer,
                request.currency(),
                now,
                now
        );

        try {
            return accountMapper.toResponse(accountRepository.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            if (isAccountNumberUniqueConstraintViolation(exception)) {
                throw accountNumberGenerationFailed();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account with id " + accountId + " was not found"
                ));
        return accountMapper.toResponse(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsByCustomerId(UUID customerId) {
        if (!customerRepository.existsById(customerId)) {
            throw customerNotFound(customerId);
        }
        return accountRepository.findAllByCustomerIdOrderByCreatedAtAscIdAsc(customerId)
                .stream()
                .map(accountMapper::toResponse)
                .toList();
    }

    private String generateUniqueAccountNumber() {
        for (int attempt = 0; attempt < MAX_ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String candidate = accountNumberGenerator.generate();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw accountNumberGenerationFailed();
    }

    private ResourceNotFoundException customerNotFound(UUID customerId) {
        return new ResourceNotFoundException(
                "CUSTOMER_NOT_FOUND",
                "Customer with id " + customerId + " was not found"
        );
    }

    private InternalOperationException accountNumberGenerationFailed() {
        return new InternalOperationException(GENERATION_ERROR_CODE, GENERATION_ERROR_MESSAGE);
    }

    private boolean isAccountNumberUniqueConstraintViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && ACCOUNT_NUMBER_CONSTRAINT.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
