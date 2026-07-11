package artem.dev.corebank.account.repository;

import artem.dev.corebank.account.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    boolean existsByAccountNumber(String accountNumber);

    List<AccountEntity> findAllByCustomerIdOrderByCreatedAtAscIdAsc(UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT account FROM AccountEntity account WHERE account.id = :accountId")
    Optional<AccountEntity> findByIdForUpdate(@Param("accountId") UUID accountId);
}
