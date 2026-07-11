package artem.dev.corebank.transaction.mapper;

import artem.dev.corebank.account.entity.AccountEntity;
import artem.dev.corebank.transaction.dto.TransactionResponse;
import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransactionMapper {

    public TransactionResponse toResponse(AccountTransactionEntity transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                accountId(transaction.getSourceAccount()),
                accountId(transaction.getTargetAccount()),
                transaction.getDescription(),
                transaction.getCreatedAt()
        );
    }

    private UUID accountId(AccountEntity account) {
        return account == null ? null : account.getId();
    }
}
