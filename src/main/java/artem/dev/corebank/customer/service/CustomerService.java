package artem.dev.corebank.customer.service;

import artem.dev.corebank.common.exception.DuplicateResourceException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.dto.CreateCustomerRequest;
import artem.dev.corebank.customer.dto.CustomerResponse;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.mapper.CustomerMapper;
import artem.dev.corebank.customer.repository.CustomerRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

@Service
public class CustomerService {

    private static final String CUSTOMER_EMAIL_CONSTRAINT = "uk_customers_email";
    private static final String DUPLICATE_EMAIL_CODE = "CUSTOMER_EMAIL_ALREADY_EXISTS";
    private static final String DUPLICATE_EMAIL_MESSAGE = "Customer with this email already exists";

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final Clock clock;

    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper, Clock clock) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.clock = clock;
    }

    @Transactional
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        String firstName = normalizeRequired(request.firstName(), "firstName");
        String lastName = normalizeRequired(request.lastName(), "lastName");
        String email = normalizeEmail(request.email());

        if (customerRepository.existsByEmail(email)) {
            throw duplicateEmailException();
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        CustomerEntity customer = new CustomerEntity(
                UUID.randomUUID(),
                firstName,
                lastName,
                email,
                now,
                now
        );

        try {
            return customerMapper.toResponse(customerRepository.saveAndFlush(customer));
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueConstraintViolation(exception)) {
                throw duplicateEmailException();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomerById(UUID customerId) {
        CustomerEntity customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CUSTOMER_NOT_FOUND",
                        "Customer with id " + customerId + " was not found"
                ));
        return customerMapper.toResponse(customer);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeEmail(String email) {
        return normalizeRequired(email, "email").toLowerCase(Locale.ROOT);
    }

    private DuplicateResourceException duplicateEmailException() {
        return new DuplicateResourceException(DUPLICATE_EMAIL_CODE, DUPLICATE_EMAIL_MESSAGE);
    }

    private boolean isEmailUniqueConstraintViolation(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && CUSTOMER_EMAIL_CONSTRAINT.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
