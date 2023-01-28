package hu.perit.credittransferservice.services.impl.accountservice;

import hu.perit.credittransferservice.db.credittransferdb.repo.AccountRepo;
import hu.perit.credittransferservice.db.credittransferdb.table.AccountEntity;
import hu.perit.credittransferservice.services.api.AccountService;
import hu.perit.credittransferservice.services.model.CreditTransferException;
import hu.perit.credittransferservice.services.model.CreditTransferRequest;
import hu.perit.credittransferservice.services.model.LockMode;
import hu.perit.spvitamin.spring.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService
{
    private final AccountRepo repo;

    @Override
    @Transactional(rollbackFor = RuntimeException.class)
    public void transfer(CreditTransferRequest request) throws ResourceNotFoundException, CreditTransferException
    {
        // Using a write lock so that parallel transaction with the same debitor account have to wait until this
        // transaction completes. No optimistic locking used.
        AccountEntity debitorAccount = getAccountByIban(request.getDebitorIban(), LockMode.FOR_UPDATE);

        // Using optimistic locking here, in order to avoid deadlocks. But the caller must be aware of
        // OptimisticLockingException and retry the transaction in such cases
        AccountEntity creditorAccount = getAccountByIban(request.getCreditorIban(), LockMode.FOR_READ);

        BigDecimal amount = request.getAmount();
        if (!debitorAccount.withdraw(amount))
        {
            // Throwing business-related exception, there is no rollback to allow the external transaction to commit
            throw new CreditTransferException("There is no sufficient account coverage!");
        }
        creditorAccount.deposit(amount);

        // Save the account balances
        this.repo.save(debitorAccount);

        // This may throw OptimisticLockingException, if a parallel transaction would also try to deposit money into
        // the same creditor account
        this.repo.save(creditorAccount);
    }


    @Override
    public AccountEntity getAccountByIban(String iban, LockMode lockMode) throws ResourceNotFoundException
    {
        Optional<AccountEntity> entity = (lockMode == LockMode.FOR_UPDATE) ?
                this.repo.findByIbanWithWriteLock(iban) :
                this.repo.findByIban(iban);
        if (entity.isEmpty())
        {
            throw new ResourceNotFoundException(MessageFormat.format("No account found by iban {0}", iban));
        }

        return entity.get();
    }
}
