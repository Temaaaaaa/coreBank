package artem.dev.corebank.transaction.controller;

import artem.dev.corebank.transaction.dto.DepositRequest;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.dto.TransferRequest;
import artem.dev.corebank.transaction.dto.WithdrawalRequest;
import artem.dev.corebank.transaction.service.MoneyOperationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
public class MoneyOperationController {

    private final MoneyOperationService moneyOperationService;

    public MoneyOperationController(MoneyOperationService moneyOperationService) {
        this.moneyOperationService = moneyOperationService;
    }

    @PostMapping("/api/v1/accounts/{accountId}/deposits")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody DepositRequest request
    ) {
        TransactionResponse response = moneyOperationService.deposit(accountId, request);
        URI location = URI.create("/api/v1/transactions/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/api/v1/accounts/{accountId}/withdrawals")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody WithdrawalRequest request
    ) {
        TransactionResponse response = moneyOperationService.withdraw(accountId, request);
        URI location = URI.create("/api/v1/transactions/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/api/v1/transfers")
    public ResponseEntity<TransactionResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransactionResponse response = moneyOperationService.transfer(request);
        URI location = URI.create("/api/v1/transactions/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/api/v1/transactions/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable UUID transactionId) {
        return ResponseEntity.ok(moneyOperationService.getTransactionById(transactionId));
    }
}
