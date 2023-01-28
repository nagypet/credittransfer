package hu.perit.credittransferservice.db.credittransferdb.repo;

import hu.perit.credittransferservice.db.credittransferdb.table.CreditTransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditTransferRepo extends JpaRepository<CreditTransferEntity, Long>
{
}
