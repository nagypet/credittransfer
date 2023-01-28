package hu.perit.credittransferservice.services.api;

import hu.perit.credittransferservice.db.credittransferdb.table.AccountEntity;
import hu.perit.credittransferservice.services.model.CreditTransferException;
import hu.perit.credittransferservice.services.model.CreditTransferRequest;
import hu.perit.credittransferservice.services.model.LockMode;
import hu.perit.spvitamin.spring.exception.ResourceNotFoundException;

public interface AccountService
{
    void transfer(CreditTransferRequest request) throws ResourceNotFoundException, CreditTransferException;

    AccountEntity getAccountByIban(String iban, LockMode lockMode) throws ResourceNotFoundException;
}
