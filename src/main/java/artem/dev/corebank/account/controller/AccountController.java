package artem.dev.corebank.account.controller;

import artem.dev.corebank.account.dto.AccountResponse;
import artem.dev.corebank.account.dto.OpenAccountRequest;
import artem.dev.corebank.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/api/v1/customers/{customerId}/accounts")
    public ResponseEntity<AccountResponse> openAccount(
            @PathVariable UUID customerId,
            @Valid @RequestBody OpenAccountRequest request
    ) {
        AccountResponse response = accountService.openAccount(customerId, request);
        URI location = URI.create("/api/v1/accounts/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/api/v1/customers/{customerId}/accounts")
    public List<AccountResponse> getCustomerAccounts(@PathVariable UUID customerId) {
        return accountService.getAccountsByCustomerId(customerId);
    }

    @GetMapping("/api/v1/accounts/{accountId}")
    public AccountResponse getAccount(@PathVariable UUID accountId) {
        return accountService.getAccountById(accountId);
    }
}
