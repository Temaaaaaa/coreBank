package artem.dev.corebank.customer.controller;

import artem.dev.corebank.common.exception.DuplicateResourceException;
import artem.dev.corebank.common.exception.ResourceNotFoundException;
import artem.dev.corebank.customer.dto.CreateCustomerRequest;
import artem.dev.corebank.customer.dto.CustomerResponse;
import artem.dev.corebank.customer.service.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-11T18:00:00Z");
    private static final UUID CUSTOMER_ID = UUID.fromString("c4f540b1-194d-4be7-bb96-64df4b06334c");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(FIXED_TIME);
    }

    @Test
    void postReturnsCreatedCustomerAndLocationHeader() throws Exception {
        when(customerService.createCustomer(any(CreateCustomerRequest.class))).thenReturn(customerResponse());

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/customers/" + CUSTOMER_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.firstName").value("Artem"))
                .andExpect(jsonPath("$.lastName").value("Ivanov"))
                .andExpect(jsonPath("$.email").value("artem@example.com"))
                .andExpect(jsonPath("$.createdAt").value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.updatedAt").value(FIXED_TIME.toString()));
    }

    @Test
    void invalidEmailReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Artem","lastName":"Ivanov","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("email"));
    }

    @Test
    void blankFirstNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"   ","lastName":"Ivanov","email":"artem@example.com"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value("firstName"));
    }

    @Test
    void firstNameOverMaximumLengthReturnsBadRequest() throws Exception {
        String longName = "a".repeat(101);

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"%s","lastName":"Ivanov","email":"artem@example.com"}
                                """.formatted(longName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value("firstName"));
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        when(customerService.createCustomer(any(CreateCustomerRequest.class)))
                .thenThrow(new DuplicateResourceException(
                        "CUSTOMER_EMAIL_ALREADY_EXISTS",
                        "Customer with this email already exists"
                ));

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.timestamp").value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.path").value("/api/v1/customers"))
                .andExpect(jsonPath("$.code").value("CUSTOMER_EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Customer with this email already exists"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    @Test
    void getReturnsCustomer() throws Exception {
        when(customerService.getCustomerById(CUSTOMER_ID)).thenReturn(customerResponse());

        mockMvc.perform(get("/api/v1/customers/{customerId}", CUSTOMER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.email").value("artem@example.com"));
    }

    @Test
    void getMissingCustomerReturnsNotFound() throws Exception {
        when(customerService.getCustomerById(CUSTOMER_ID)).thenThrow(new ResourceNotFoundException(
                "CUSTOMER_NOT_FOUND",
                "Customer with id " + CUSTOMER_ID + " was not found"
        ));

        mockMvc.perform(get("/api/v1/customers/{customerId}", CUSTOMER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/customers/" + CUSTOMER_ID));
    }

    @Test
    void malformedCustomerIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/customers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/v1/customers/not-a-uuid"));
    }

    @Test
    void malformedJsonReturnsBadRequestInCommonErrorFormat() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").value(FIXED_TIME.toString()))
                .andExpect(jsonPath("$.path").value("/api/v1/customers"))
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").value("Request body contains malformed JSON"))
                .andExpect(jsonPath("$.validationErrors").isEmpty());
    }

    private String validRequest() {
        return """
                {"firstName":"Artem","lastName":"Ivanov","email":"artem@example.com"}
                """;
    }

    private CustomerResponse customerResponse() {
        return new CustomerResponse(
                CUSTOMER_ID,
                "Artem",
                "Ivanov",
                "artem@example.com",
                FIXED_TIME,
                FIXED_TIME
        );
    }
}
