package hu.perit.credittransferservice.services.impl.credittransferservice;

import hu.perit.credittransferservice.db.credittransferdb.table.AccountEntity;
import hu.perit.credittransferservice.services.api.AccountService;
import hu.perit.credittransferservice.services.api.CreditTransferService;
import hu.perit.credittransferservice.services.model.CreditTransferException;
import hu.perit.credittransferservice.services.model.CreditTransferRequest;
import hu.perit.credittransferservice.services.model.LockMode;
import hu.perit.spvitamin.spring.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

@ActiveProfiles({"default", "spvitamin-defaults"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Slf4j
class CreditTransferServiceImplTest
{
    @Autowired
    private CreditTransferService creditTransferService;
    @Autowired
    private AccountService accountService;

    @AfterEach
    void afterEach()
    {
        this.creditTransferService.dumpTransactions();
    }

    @Test
    void saveAndExecute() throws ResourceNotFoundException, CreditTransferException
    {
        log.debug("------------------- saveAndExecute ------------------------------------------------------------------");
        Long transactionId = this.creditTransferService.save(new CreditTransferRequest("HU-PETER", "DE-ALICE", new BigDecimal(10)));
        this.creditTransferService.execute(transactionId);
        AccountEntity debitorAccount = this.accountService.getAccountByIban("HU-PETER", LockMode.FOR_READ);
        AccountEntity creditorAccount = this.accountService.getAccountByIban("DE-ALICE", LockMode.FOR_READ);
        Assertions.assertThat(debitorAccount.getBalance()).isEqualTo("990.00");
        Assertions.assertThat(creditorAccount.getBalance()).isEqualTo("1010.00");
    }

    @Test
    void saveAndExecuteNoCoverage()
    {
        log.debug("------------------- saveAndExecuteNoCoverage --------------------------------------------------------");
        Long transactionId = this.creditTransferService.save(new CreditTransferRequest("HU-PETER", "DE-ALICE", new BigDecimal(2000)));
        Assertions.assertThatThrownBy(() -> this.creditTransferService.execute(transactionId)).isInstanceOf(CreditTransferException.class);
    }

    @Test
    void saveAndExecuteInvalidIban()
    {
        log.debug("------------------- saveAndExecuteInvalidIban -------------------------------------------------------");
        Long transactionId = this.creditTransferService.save(new CreditTransferRequest("HU_PETER", "DE-ALICE", new BigDecimal(100)));
        Assertions.assertThatThrownBy(() -> this.creditTransferService.execute(transactionId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
