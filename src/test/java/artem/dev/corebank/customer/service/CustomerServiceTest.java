package artem.dev.corebank.customer.service;

import artem.dev.corebank.common.exception.DuplicateResourceException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.dto.CreateCustomerRequest;
import artem.dev.corebank.customer.dto.CustomerResponse;
import artem.dev.corebank.customer.entity.CustomerEntity;
import artem.dev.corebank.customer.mapper.CustomerMapper;
import artem.dev.corebank.customer.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final Instant CLOCK_TIME = Instant.parse("2026-07-11T18:00:00.123456789Z");
    private static final Instant PERSISTED_TIME = Instant.parse("2026-07-11T18:00:00.123456Z");

    @Mock
    private CustomerRepository customerRepository;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(CLOCK_TIME, ZoneOffset.UTC);
        customerService = new CustomerService(customerRepository, new CustomerMapper(), fixedClock);
    }

    @Test
    void createsCustomerWithNormalizedFieldsAndFixedTimestamps() {
        CreateCustomerRequest request = new CreateCustomerRequest(
                "  Artem  ",
                "  Ivanov  ",
                "  ARTEM@example.com  "
        );
        when(customerRepository.existsByEmail("artem@example.com")).thenReturn(false);
        when(customerRepository.saveAndFlush(any(CustomerEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerResponse response = customerService.createCustomer(request);

        ArgumentCaptor<CustomerEntity> customerCaptor = ArgumentCaptor.forClass(CustomerEntity.class);
        verify(customerRepository).saveAndFlush(customerCaptor.capture());
        CustomerEntity savedCustomer = customerCaptor.getValue();

        assertThat(response.id()).isNotNull();
        assertThat(response.firstName()).isEqualTo("Artem");
        assertThat(response.lastName()).isEqualTo("Ivanov");
        assertThat(response.email()).isEqualTo("artem@example.com");
        assertThat(response.createdAt()).isEqualTo(PERSISTED_TIME);
        assertThat(response.updatedAt()).isEqualTo(PERSISTED_TIME);
        assertThat(savedCustomer.getCreatedAt()).isEqualTo(savedCustomer.getUpdatedAt());
    }

    @Test
    void rejectsExistingEmail() {
        CreateCustomerRequest request = new CreateCustomerRequest("Artem", "Ivanov", "ARTEM@example.com");
        when(customerRepository.existsByEmail("artem@example.com")).thenReturn(true);

        assertThatThrownBy(() -> customerService.createCustomer(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessage("Customer with this email already exists");

        verify(customerRepository, never()).saveAndFlush(any(CustomerEntity.class));
    }

    @Test
    void returnsCustomerById() {
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = customer(customerId);
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getCustomerById(customerId);

        assertThat(response.id()).isEqualTo(customerId);
        assertThat(response.email()).isEqualTo("artem@example.com");
    }

    @Test
    void throwsWhenCustomerDoesNotExist() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getCustomerById(customerId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(customerId.toString());
    }

    @Test
    void rejectsFirstNameThatIsBlankAfterNormalization() {
        CreateCustomerRequest request = new CreateCustomerRequest("   ", "Ivanov", "artem@example.com");

        assertThatThrownBy(() -> customerService.createCustomer(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("firstName must not be blank");

        verify(customerRepository, never()).existsByEmail(any());
    }

    private CustomerEntity customer(UUID customerId) {
        return new CustomerEntity(
                customerId,
                "Artem",
                "Ivanov",
                "artem@example.com",
                PERSISTED_TIME,
                PERSISTED_TIME
        );
    }
}
