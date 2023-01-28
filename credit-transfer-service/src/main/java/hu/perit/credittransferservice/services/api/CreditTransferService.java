package hu.perit.credittransferservice.services.api;

import hu.perit.credittransferservice.db.credittransferdb.table.CreditTransferEntity;
import hu.perit.credittransferservice.services.model.CreditTransferException;
import hu.perit.credittransferservice.services.model.CreditTransferRequest;
import hu.perit.spvitamin.spring.exception.ResourceNotFoundException;

/**
 * This service stores the transaction in the database
 */

public interface CreditTransferService
{
    Long save(CreditTransferRequest request);

    void execute(Long transactionId) throws CreditTransferException, ResourceNotFoundException;

    CreditTransferEntity findById(Long transactionId) throws ResourceNotFoundException;

    // For unit testing only
    void dumpTransactions();
}
