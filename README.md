# Credit Transfer

This is a proof of concept project on database transaction handling in the context of a credit transfer solution.

The challange: there are two database tables, one for accounts and another for storing transactions. The table `account` stores the iban number, name of the owner and the balance, while tha table `credit-transfer` is a kind of log of stored and executed transactions.

```java
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = AccountEntity.TABLE_NAME, schema = "DBO", indexes = {
        @Index(columnList = "iban", name = "IX_ACCOUNT_IBAN", unique = true)})
@ToString
public class AccountEntity
{
    public static final String TABLE_NAME = "account";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(nullable = false)
    private String iban;

    @Column(nullable = false)
    private String ownersName;

    @Column(nullable = false)
    private BigDecimal balance;

    // For optimistic locking
    @Version
    @Column(nullable = false)
    private Long recVersion;

    public void deposit(BigDecimal amount)
    {
        this.balance = this.balance.add(amount);
    }

    public boolean withdraw(BigDecimal amount)
    {
        if (this.balance.compareTo(amount) > 0)
        {
            this.balance = this.balance.subtract(amount);
            return true;
        }

        return false;
    }
}
```

```java
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = CreditTransferEntity.TABLE_NAME, schema = "DBO")
@ToString
public class CreditTransferEntity
{
    public static final String TABLE_NAME = "credit_transfer";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(nullable = false)
    private String debitorIban;

    @Column(nullable = false)
    private String creditorIban;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private Long statusCode;

    private String errorText;

    // For optimistic locking
    @Version
    @Column(nullable = false)
    private Long recVersion;

    public CreditTransferStatus getStatus()
    {
        if (this.statusCode == null)
        {
            return null;
        }

        return CreditTransferStatus.fromCode(this.statusCode);
    }

    public void setStatus(CreditTransferStatus status)
    {
        if (status == null)
        {
            this.statusCode = null;
            return;
        }

        this.statusCode = status.getCode();
    }
}
```

Obviously the two tables have to be updated in one database transaction, so that the final status of the transaction gets into the state `EXECUTED` only if the new balances could be stored successfully. On the other hand, even if there is an exception in the embedded transaction, e.g. because of no account coverage, the external transaction have to be stored updating the status of the transaction to `FAILED` and storing an error text along.

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditTransferServiceImpl implements CreditTransferService
{
    private final CreditTransferRepo repo;
    private final CreditTransferRequestMapper mapper;
    private final AccountService accountService;

    @Override
    public Long save(CreditTransferRequest request)
    {
        CreditTransferEntity creditTransferEntity = this.mapper.toEntity(request);

        CreditTransferEntity savedEntity = this.repo.save(creditTransferEntity);
        log.info(MessageFormat.format("Credit transfer saved: {0}", savedEntity));
        return savedEntity.getId();
    }

    @Override
    @Transactional(rollbackOn = RuntimeException.class)
    public void execute(Long transactionId) throws CreditTransferException, ResourceNotFoundException
    {
        CreditTransferEntity creditTransferEntity = findById(transactionId);

        try
        {
            this.accountService.transfer(this.mapper.fromEntity(creditTransferEntity));
            creditTransferEntity.setStatus(CreditTransferStatus.EXECUTED);
        }
        catch (RuntimeException | CreditTransferException | ResourceNotFoundException e)
        {
            // Here could be a retry in case of OptimisticLockingException
            log.error(StackTracer.toString(e));
            creditTransferEntity.setStatus(CreditTransferStatus.FAILED);
            creditTransferEntity.setErrorText(e.toString());
            throw e;
        }

        log.info(MessageFormat.format("Credit transfer executed: {0}", creditTransferEntity));
        this.repo.save(creditTransferEntity);
    }

    @Override
    public CreditTransferEntity findById(Long transactionId) throws ResourceNotFoundException
    {
        Optional<CreditTransferEntity> optEntity = this.repo.findById(transactionId);
        if (optEntity.isEmpty())
        {
            throw new ResourceNotFoundException(MessageFormat.format("Transaction not found by id: {}", transactionId));
        }

        return optEntity.get();
    }
}
```

```java
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
```

```java
public interface AccountRepo extends JpaRepository<AccountEntity, Long>
{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "select e from AccountEntity e where e.iban = :iban")
    Optional<AccountEntity> findByIbanWithWriteLock(String iban);

    Optional<AccountEntity> findByIban(String iban);
}
```

```
  _____                    _       _            _         _   _       ____                  _
 |_   _|__ _ __ ___  _ __ | | __ _| |_ ___     / \  _   _| |_| |__   / ___|  ___ _ ____   _(_) ___ ___
   | |/ _ \ '_ ` _ \| '_ \| |/ _` | __/ _ \   / _ \| | | | __| '_ \  \___ \ / _ \ '__\ \ / / |/ __/ _ \
   | |  __/ | | | | | |_) | | (_| | ||  __/  / ___ \ |_| | |_| | | |  ___) |  __/ |   \ V /| | (_|  __/
   |_|\___|_| |_| |_| .__/|_|\__,_|\__\___| /_/   \_\__,_|\__|_| |_| |____/ \___|_|    \_/ |_|\___\___|
                    |_|
                                           project

AdoptOpenJDK 11.0.9.1+1
Spring-Boot: 2.7.7
: 

Author: Peter Nagy <nagy.peter.home@gmail.com>

2023-01-28 12:27:20.076 DEBUG --- [Test worker    ] .CreditTransferServiceImplTest  39 : ------------------- saveAndExecute ------------------------------------------------------------------ 
2023-01-28 12:27:20.125 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT 
2023-01-28 12:27:20.126 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(1579223995<open>)] for JPA transaction 
2023-01-28 12:27:20.127 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@325a7baa] 
2023-01-28 12:27:20.133 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.206 DEBUG SPR [Test worker    ] o.h.SQL                        144 : insert into DBO.credit_transfer (id, amount, creditorIban, debitorIban, errorText, recVersion, statusCode) values (default, ?, ?, ?, ?, ?, ?) 
2023-01-28 12:27:20.211 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [10] 
2023-01-28 12:27:20.212 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.212 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.213 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           52 : binding parameter [4] as [VARCHAR] - [null] 
2023-01-28 12:27:20.215 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [0] 
2023-01-28 12:27:20.215 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [1] 
2023-01-28 12:27:20.252 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.254 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.254 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(1579223995<open>)] 
2023-01-28 12:27:20.274 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(1579223995<open>)] after transaction 
2023-01-28 12:27:20.319 INFO  --- [Test worker    ] .i.c.CreditTransferServiceImpl  37 : Credit transfer saved: CreditTransferEntity(id=1, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=10, statusCode=1, errorText=null, recVersion=0) 
2023-01-28 12:27:20.321 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,-java.lang.RuntimeException 
2023-01-28 12:27:20.322 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(1095396623<open>)] for JPA transaction 
2023-01-28 12:27:20.322 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@40bce99c] 
2023-01-28 12:27:20.323 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute] 
2023-01-28 12:27:20.324 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1095396623<open>)] for JPA transaction 
2023-01-28 12:27:20.325 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.326 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findById] 
2023-01-28 12:27:20.356 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select credittran0_.id as id1_1_0_, credittran0_.amount as amount2_1_0_, credittran0_.creditorIban as creditor3_1_0_, credittran0_.debitorIban as debitori4_1_0_, credittran0_.errorText as errortex5_1_0_, credittran0_.recVersion as recversi6_1_0_, credittran0_.statusCode as statusco7_1_0_ from DBO.credit_transfer credittran0_ where credittran0_.id=? 
2023-01-28 12:27:20.360 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [BIGINT] - [1] 
2023-01-28 12:27:20.383 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findById] 
2023-01-28 12:27:20.383 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1095396623<open>)] for JPA transaction 
2023-01-28 12:27:20.385 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.385 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer] 
2023-01-28 12:27:20.393 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIbanWithWriteLock]: This method is not transactional. 
2023-01-28 12:27:20.435 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? for update 
2023-01-28 12:27:20.436 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.444 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIban]: This method is not transactional. 
2023-01-28 12:27:20.463 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? 
2023-01-28 12:27:20.467 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.473 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1095396623<open>)] for JPA transaction 
2023-01-28 12:27:20.476 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.479 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.496 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.497 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1095396623<open>)] for JPA transaction 
2023-01-28 12:27:20.498 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.499 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.500 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.501 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer] 
2023-01-28 12:27:20.502 INFO  --- [Test worker    ] .i.c.CreditTransferServiceImpl  66 : Credit transfer executed: CreditTransferEntity(id=1, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=10.00, statusCode=2, errorText=null, recVersion=0) 
2023-01-28 12:27:20.503 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1095396623<open>)] for JPA transaction 
2023-01-28 12:27:20.503 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.504 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.505 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.506 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute] 
2023-01-28 12:27:20.506 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.507 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(1095396623<open>)] 
2023-01-28 12:27:20.525 DEBUG SPR [Test worker    ] o.h.SQL                        144 : update DBO.account set balance=?, iban=?, ownersName=?, recVersion=? where id=? and recVersion=? 
2023-01-28 12:27:20.532 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [1010.00] 
2023-01-28 12:27:20.539 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.542 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [Alice] 
2023-01-28 12:27:20.544 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [4] as [BIGINT] - [1] 
2023-01-28 12:27:20.546 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [1] 
2023-01-28 12:27:20.547 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [0] 
2023-01-28 12:27:20.551 DEBUG SPR [Test worker    ] o.h.SQL                        144 : update DBO.account set balance=?, iban=?, ownersName=?, recVersion=? where id=? and recVersion=? 
2023-01-28 12:27:20.553 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [990.00] 
2023-01-28 12:27:20.555 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.557 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [Peter] 
2023-01-28 12:27:20.557 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [4] as [BIGINT] - [1] 
2023-01-28 12:27:20.558 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [5] 
2023-01-28 12:27:20.559 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [0] 
2023-01-28 12:27:20.564 DEBUG SPR [Test worker    ] o.h.SQL                        144 : update DBO.credit_transfer set amount=?, creditorIban=?, debitorIban=?, errorText=?, recVersion=?, statusCode=? where id=? and recVersion=? 
2023-01-28 12:27:20.565 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [10.00] 
2023-01-28 12:27:20.566 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.567 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.568 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           52 : binding parameter [4] as [VARCHAR] - [null] 
2023-01-28 12:27:20.568 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [1] 
2023-01-28 12:27:20.569 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [2] 
2023-01-28 12:27:20.570 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [7] as [BIGINT] - [1] 
2023-01-28 12:27:20.571 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [8] as [BIGINT] - [0] 
2023-01-28 12:27:20.573 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(1095396623<open>)] after transaction 
2023-01-28 12:27:20.573 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIban]: This method is not transactional. 
2023-01-28 12:27:20.574 DEBUG SPR [Test worker    ] EntityManagerInvocationHandler 302 : Creating new EntityManager for shared EntityManager invocation 
2023-01-28 12:27:20.609 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? 
2023-01-28 12:27:20.610 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.615 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIban]: This method is not transactional. 
2023-01-28 12:27:20.618 DEBUG SPR [Test worker    ] EntityManagerInvocationHandler 302 : Creating new EntityManager for shared EntityManager invocation 
2023-01-28 12:27:20.620 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? 
2023-01-28 12:27:20.621 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.736 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,readOnly 
2023-01-28 12:27:20.736 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(542188687<open>)] for JPA transaction 
2023-01-28 12:27:20.738 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@6830885b] 
2023-01-28 12:27:20.740 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll] 
2023-01-28 12:27:20.755 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select credittran0_.id as id1_1_, credittran0_.amount as amount2_1_, credittran0_.creditorIban as creditor3_1_, credittran0_.debitorIban as debitori4_1_, credittran0_.errorText as errortex5_1_, credittran0_.recVersion as recversi6_1_, credittran0_.statusCode as statusco7_1_ from DBO.credit_transfer credittran0_ 
2023-01-28 12:27:20.758 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll] 
2023-01-28 12:27:20.758 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.759 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(542188687<open>)] 
2023-01-28 12:27:20.760 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(542188687<open>)] after transaction 
2023-01-28 12:27:20.761 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  86 : ---------------------------------------------------------------------------------------------------------- 
2023-01-28 12:27:20.761 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  89 : CreditTransferEntity(id=1, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=10.00, statusCode=2, errorText=null, recVersion=1) 
2023-01-28 12:27:20.761 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  91 : ---------------------------------------------------------------------------------------------------------- 



2023-01-28 12:27:20.785 DEBUG --- [Test worker    ] .CreditTransferServiceImplTest  59 : ------------------- saveAndExecuteInvalidIban ------------------------------------------------------- 
2023-01-28 12:27:20.786 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT 
2023-01-28 12:27:20.786 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(245762898<open>)] for JPA transaction 
2023-01-28 12:27:20.787 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@68bb67f1] 
2023-01-28 12:27:20.787 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.788 DEBUG SPR [Test worker    ] o.h.SQL                        144 : insert into DBO.credit_transfer (id, amount, creditorIban, debitorIban, errorText, recVersion, statusCode) values (default, ?, ?, ?, ?, ?, ?) 
2023-01-28 12:27:20.789 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [100] 
2023-01-28 12:27:20.789 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.789 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [HU_PETER] 
2023-01-28 12:27:20.789 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           52 : binding parameter [4] as [VARCHAR] - [null] 
2023-01-28 12:27:20.790 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [0] 
2023-01-28 12:27:20.790 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [1] 
2023-01-28 12:27:20.791 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.791 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.791 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(245762898<open>)] 
2023-01-28 12:27:20.792 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(245762898<open>)] after transaction 
2023-01-28 12:27:20.793 INFO  --- [Test worker    ] .i.c.CreditTransferServiceImpl  37 : Credit transfer saved: CreditTransferEntity(id=2, debitorIban=HU_PETER, creditorIban=DE-ALICE, amount=100, statusCode=1, errorText=null, recVersion=0) 
2023-01-28 12:27:20.795 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,-java.lang.RuntimeException 
2023-01-28 12:27:20.795 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(1246297356<open>)] for JPA transaction 
2023-01-28 12:27:20.796 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@71a19422] 
2023-01-28 12:27:20.796 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute] 
2023-01-28 12:27:20.796 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1246297356<open>)] for JPA transaction 
2023-01-28 12:27:20.797 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.797 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findById] 
2023-01-28 12:27:20.797 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select credittran0_.id as id1_1_0_, credittran0_.amount as amount2_1_0_, credittran0_.creditorIban as creditor3_1_0_, credittran0_.debitorIban as debitori4_1_0_, credittran0_.errorText as errortex5_1_0_, credittran0_.recVersion as recversi6_1_0_, credittran0_.statusCode as statusco7_1_0_ from DBO.credit_transfer credittran0_ where credittran0_.id=? 
2023-01-28 12:27:20.804 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [BIGINT] - [2] 
2023-01-28 12:27:20.810 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findById] 
2023-01-28 12:27:20.811 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(1246297356<open>)] for JPA transaction 
2023-01-28 12:27:20.812 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.813 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer] 
2023-01-28 12:27:20.814 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIbanWithWriteLock]: This method is not transactional. 
2023-01-28 12:27:20.817 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? for update 
2023-01-28 12:27:20.819 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [HU_PETER] 
2023-01-28 12:27:20.822 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 667 : Completing transaction for [hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer] after exception: hu.perit.spvitamin.spring.exception.ResourceNotFoundException: No account found by iban HU_PETER 
2023-01-28 12:27:20.829 ERROR --- [Test worker    ] .i.c.CreditTransferServiceImpl  60 : hu.perit.spvitamin.spring.exception.ResourceNotFoundException: No account found by iban HU_PETER
    => 1: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.getAccountByIban(AccountServiceImpl.java:62)
    => 2: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer(AccountServiceImpl.java:31)
    => 3: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl$$FastClassBySpringCGLIB$$f5253bcc.invoke(<generated>:-1)
    => 14: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl$$EnhancerBySpringCGLIB$$9887d518.transfer(<generated>:-1)
    => 15: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute(CreditTransferServiceImpl.java:49)
    => 16: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl$$FastClassBySpringCGLIB$$e843fbb2.invoke(<generated>:-1)
    => 27: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl$$EnhancerBySpringCGLIB$$fd50348a.execute(<generated>:-1)
    => 28: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImplTest.lambda$saveAndExecuteInvalidIban$1(CreditTransferServiceImplTest.java:61)
    => 33: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImplTest.saveAndExecuteInvalidIban(CreditTransferServiceImplTest.java:61) 
2023-01-28 12:27:20.832 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 667 : Completing transaction for [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute] after exception: hu.perit.spvitamin.spring.exception.ResourceNotFoundException: No account found by iban HU_PETER 
2023-01-28 12:27:20.832 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.833 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(1246297356<open>)] 
2023-01-28 12:27:20.834 DEBUG SPR [Test worker    ] o.h.SQL                        144 : update DBO.credit_transfer set amount=?, creditorIban=?, debitorIban=?, errorText=?, recVersion=?, statusCode=? where id=? and recVersion=? 
2023-01-28 12:27:20.835 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [100.00] 
2023-01-28 12:27:20.835 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.836 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [HU_PETER] 
2023-01-28 12:27:20.836 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [4] as [VARCHAR] - [hu.perit.spvitamin.spring.exception.ResourceNotFoundException: No account found by iban HU_PETER] 
2023-01-28 12:27:20.837 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [1] 
2023-01-28 12:27:20.837 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [3] 
2023-01-28 12:27:20.838 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [7] as [BIGINT] - [2] 
2023-01-28 12:27:20.838 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [8] as [BIGINT] - [0] 
2023-01-28 12:27:20.840 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(1246297356<open>)] after transaction 
2023-01-28 12:27:20.847 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,readOnly 
2023-01-28 12:27:20.848 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(2092541976<open>)] for JPA transaction 
2023-01-28 12:27:20.849 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@407987b7] 
2023-01-28 12:27:20.849 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll] 
2023-01-28 12:27:20.850 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select credittran0_.id as id1_1_, credittran0_.amount as amount2_1_, credittran0_.creditorIban as creditor3_1_, credittran0_.debitorIban as debitori4_1_, credittran0_.errorText as errortex5_1_, credittran0_.recVersion as recversi6_1_, credittran0_.statusCode as statusco7_1_ from DBO.credit_transfer credittran0_ 
2023-01-28 12:27:20.852 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll] 
2023-01-28 12:27:20.853 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.853 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(2092541976<open>)] 
2023-01-28 12:27:20.854 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(2092541976<open>)] after transaction 
2023-01-28 12:27:20.854 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  86 : ---------------------------------------------------------------------------------------------------------- 
2023-01-28 12:27:20.855 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  89 : CreditTransferEntity(id=1, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=10.00, statusCode=2, errorText=null, recVersion=1) 
2023-01-28 12:27:20.855 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  89 : CreditTransferEntity(id=2, debitorIban=HU_PETER, creditorIban=DE-ALICE, amount=100.00, statusCode=3, errorText=hu.perit.spvitamin.spring.exception.ResourceNotFoundException: No account found by iban HU_PETER, recVersion=1) 
2023-01-28 12:27:20.856 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  91 : ---------------------------------------------------------------------------------------------------------- 


2023-01-28 12:27:20.879 DEBUG --- [Test worker    ] .CreditTransferServiceImplTest  51 : ------------------- saveAndExecuteNoCoverage -------------------------------------------------------- 
2023-01-28 12:27:20.880 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT 
2023-01-28 12:27:20.882 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(844132295<open>)] for JPA transaction 
2023-01-28 12:27:20.887 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@19c13793] 
2023-01-28 12:27:20.887 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.887 DEBUG SPR [Test worker    ] o.h.SQL                        144 : insert into DBO.credit_transfer (id, amount, creditorIban, debitorIban, errorText, recVersion, statusCode) values (default, ?, ?, ?, ?, ?, ?) 
2023-01-28 12:27:20.887 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [2000] 
2023-01-28 12:27:20.891 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.892 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.893 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           52 : binding parameter [4] as [VARCHAR] - [null] 
2023-01-28 12:27:20.893 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [0] 
2023-01-28 12:27:20.894 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [1] 
2023-01-28 12:27:20.895 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.save] 
2023-01-28 12:27:20.895 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.896 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(844132295<open>)] 
2023-01-28 12:27:20.897 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(844132295<open>)] after transaction 
2023-01-28 12:27:20.898 INFO  --- [Test worker    ] .i.c.CreditTransferServiceImpl  37 : Credit transfer saved: CreditTransferEntity(id=3, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=2000, statusCode=1, errorText=null, recVersion=0) 
2023-01-28 12:27:20.899 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,-java.lang.RuntimeException 
2023-01-28 12:27:20.901 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(214033873<open>)] for JPA transaction 
2023-01-28 12:27:20.901 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@4ad6dbb5] 
2023-01-28 12:27:20.902 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute] 
2023-01-28 12:27:20.902 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(214033873<open>)] for JPA transaction 
2023-01-28 12:27:20.902 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.903 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findById] 
2023-01-28 12:27:20.904 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select credittran0_.id as id1_1_0_, credittran0_.amount as amount2_1_0_, credittran0_.creditorIban as creditor3_1_0_, credittran0_.debitorIban as debitori4_1_0_, credittran0_.errorText as errortex5_1_0_, credittran0_.recVersion as recversi6_1_0_, credittran0_.statusCode as statusco7_1_0_ from DBO.credit_transfer credittran0_ where credittran0_.id=? 
2023-01-28 12:27:20.905 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [BIGINT] - [3] 
2023-01-28 12:27:20.905 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findById] 
2023-01-28 12:27:20.905 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  375 : Found thread-bound EntityManager [SessionImpl(214033873<open>)] for JPA transaction 
2023-01-28 12:27:20.905 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  470 : Participating in existing transaction 
2023-01-28 12:27:20.905 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer] 
2023-01-28 12:27:20.905 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIbanWithWriteLock]: This method is not transactional. 
2023-01-28 12:27:20.905 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? for update 
2023-01-28 12:27:20.905 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.913 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 632 : No need to create transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findByIban]: This method is not transactional. 
2023-01-28 12:27:20.914 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select accountent0_.id as id1_0_, accountent0_.balance as balance2_0_, accountent0_.iban as iban3_0_, accountent0_.ownersName as ownersna4_0_, accountent0_.recVersion as recversi5_0_ from DBO.account accountent0_ where accountent0_.iban=? 
2023-01-28 12:27:20.914 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.918 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 667 : Completing transaction for [hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer] after exception: hu.perit.credittransferservice.services.model.CreditTransferException: There is no sufficient account coverage! 
2023-01-28 12:27:20.919 ERROR --- [Test worker    ] .i.c.CreditTransferServiceImpl  60 : hu.perit.credittransferservice.services.model.CreditTransferException: There is no sufficient account coverage!
    => 1: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl.transfer(AccountServiceImpl.java:41)
    => 2: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl$$FastClassBySpringCGLIB$$f5253bcc.invoke(<generated>:-1)
    => 13: hu.perit.credittransferservice.services.impl.accountservice.AccountServiceImpl$$EnhancerBySpringCGLIB$$9887d518.transfer(<generated>:-1)
    => 14: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute(CreditTransferServiceImpl.java:49)
    => 15: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl$$FastClassBySpringCGLIB$$e843fbb2.invoke(<generated>:-1)
    => 26: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl$$EnhancerBySpringCGLIB$$fd50348a.execute(<generated>:-1)
    => 27: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImplTest.lambda$saveAndExecuteNoCoverage$0(CreditTransferServiceImplTest.java:53)
    => 32: hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImplTest.saveAndExecuteNoCoverage(CreditTransferServiceImplTest.java:53) 
2023-01-28 12:27:20.920 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 667 : Completing transaction for [hu.perit.credittransferservice.services.impl.credittransferservice.CreditTransferServiceImpl.execute] after exception: hu.perit.credittransferservice.services.model.CreditTransferException: There is no sufficient account coverage! 
2023-01-28 12:27:20.922 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.922 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(214033873<open>)] 
2023-01-28 12:27:20.931 DEBUG SPR [Test worker    ] o.h.SQL                        144 : update DBO.credit_transfer set amount=?, creditorIban=?, debitorIban=?, errorText=?, recVersion=?, statusCode=? where id=? and recVersion=? 
2023-01-28 12:27:20.934 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [1] as [NUMERIC] - [2000.00] 
2023-01-28 12:27:20.937 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [2] as [VARCHAR] - [DE-ALICE] 
2023-01-28 12:27:20.937 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [3] as [VARCHAR] - [HU-PETER] 
2023-01-28 12:27:20.938 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [4] as [VARCHAR] - [hu.perit.credittransferservice.services.model.CreditTransferException: There is no sufficient account coverage!] 
2023-01-28 12:27:20.939 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [5] as [BIGINT] - [1] 
2023-01-28 12:27:20.941 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [6] as [BIGINT] - [3] 
2023-01-28 12:27:20.942 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [7] as [BIGINT] - [3] 
2023-01-28 12:27:20.944 TRACE SPR [Test worker    ] o.h.t.d.s.BasicBinder           64 : binding parameter [8] as [BIGINT] - [0] 
2023-01-28 12:27:20.947 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(214033873<open>)] after transaction 
2023-01-28 12:27:20.949 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  370 : Creating new transaction with name [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT,readOnly 
2023-01-28 12:27:20.950 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  412 : Opened new EntityManager [SessionImpl(896530794<open>)] for JPA transaction 
2023-01-28 12:27:20.950 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  440 : Exposing JPA transaction as JDBC [org.springframework.orm.jpa.vendor.HibernateJpaDialect$HibernateConnectionHandle@78d30ff9] 
2023-01-28 12:27:20.950 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 623 : Getting transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll] 
2023-01-28 12:27:20.950 DEBUG SPR [Test worker    ] o.h.SQL                        144 : select credittran0_.id as id1_1_, credittran0_.amount as amount2_1_, credittran0_.creditorIban as creditor3_1_, credittran0_.debitorIban as debitori4_1_, credittran0_.errorText as errortex5_1_, credittran0_.recVersion as recversi6_1_, credittran0_.statusCode as statusco7_1_ from DBO.credit_transfer credittran0_ 
2023-01-28 12:27:20.956 TRACE SPR [Test worker    ] o.s.t.i.TransactionInterceptor 652 : Completing transaction for [org.springframework.data.jpa.repository.support.SimpleJpaRepository.findAll] 
2023-01-28 12:27:20.956 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  740 : Initiating transaction commit 
2023-01-28 12:27:20.956 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  557 : Committing JPA transaction on EntityManager [SessionImpl(896530794<open>)] 
2023-01-28 12:27:20.960 DEBUG SPR [Test worker    ] o.s.o.j.JpaTransactionManager  648 : Closing JPA EntityManager [SessionImpl(896530794<open>)] after transaction 
2023-01-28 12:27:20.960 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  86 : ---------------------------------------------------------------------------------------------------------- 
2023-01-28 12:27:20.960 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  89 : CreditTransferEntity(id=1, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=10.00, statusCode=2, errorText=null, recVersion=1) 
2023-01-28 12:27:20.960 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  89 : CreditTransferEntity(id=2, debitorIban=HU_PETER, creditorIban=DE-ALICE, amount=100.00, statusCode=3, errorText=hu.perit.spvitamin.spring.exception.ResourceNotFoundException: No account found by iban HU_PETER, recVersion=1) 
2023-01-28 12:27:20.960 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  89 : CreditTransferEntity(id=3, debitorIban=HU-PETER, creditorIban=DE-ALICE, amount=2000.00, statusCode=3, errorText=hu.perit.credittransferservice.services.model.CreditTransferException: There is no sufficient account coverage!, recVersion=1) 
2023-01-28 12:27:20.960 DEBUG --- [Test worker    ] .i.c.CreditTransferServiceImpl  91 : ---------------------------------------------------------------------------------------------------------- 
2023-01-28 12:27:21.473 INFO  SPR [ionShutdownHook] tainerEntityManagerFactoryBean 651 : Closing JPA EntityManagerFactory for persistence unit 'credit-transfer-db' 

```

I think this works as expected.
