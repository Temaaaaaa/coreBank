package artem.dev.corebank.transaction.repository;

import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountTransactionRepository extends JpaRepository<AccountTransactionEntity, UUID> {
}
