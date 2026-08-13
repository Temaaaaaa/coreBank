package artem.dev.corebank.transaction.repository;

import artem.dev.corebank.transaction.entity.AccountTransactionEntity;
import artem.dev.corebank.transaction.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AccountTransactionRepository extends JpaRepository<AccountTransactionEntity, UUID> {

    @Query(
            value = """
                    SELECT transaction
                    FROM AccountTransactionEntity transaction
                    LEFT JOIN FETCH transaction.sourceAccount sourceAccount
                    LEFT JOIN FETCH transaction.targetAccount targetAccount
                    WHERE (sourceAccount.id = :accountId OR targetAccount.id = :accountId)
                      AND transaction.type = COALESCE(:type, transaction.type)
                      AND transaction.createdAt >= COALESCE(:fromTime, transaction.createdAt)
                      AND transaction.createdAt <= COALESCE(:toTime, transaction.createdAt)
                    """,
            countQuery = """
                    SELECT COUNT(transaction)
                    FROM AccountTransactionEntity transaction
                    LEFT JOIN transaction.sourceAccount sourceAccount
                    LEFT JOIN transaction.targetAccount targetAccount
                    WHERE (sourceAccount.id = :accountId OR targetAccount.id = :accountId)
                      AND transaction.type = COALESCE(:type, transaction.type)
                      AND transaction.createdAt >= COALESCE(:fromTime, transaction.createdAt)
                      AND transaction.createdAt <= COALESCE(:toTime, transaction.createdAt)
                    """
    )
    Page<AccountTransactionEntity> findAccountHistory(
            @Param("accountId") UUID accountId,
            @Param("type") TransactionType type,
            @Param("fromTime") Instant from,
            @Param("toTime") Instant to,
            Pageable pageable
    );
}
