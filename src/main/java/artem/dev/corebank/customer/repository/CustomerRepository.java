package artem.dev.corebank.customer.repository;

import artem.dev.corebank.customer.entity.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    boolean existsByEmail(String email);
}
