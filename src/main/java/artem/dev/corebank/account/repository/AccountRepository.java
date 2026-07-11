package artem.dev.corebank.account.repository;

import artem.dev.corebank.account.entity.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    boolean existsByAccountNumber(String accountNumber);

    List<AccountEntity> findAllByCustomerIdOrderByCreatedAtAscIdAsc(UUID customerId);
}
