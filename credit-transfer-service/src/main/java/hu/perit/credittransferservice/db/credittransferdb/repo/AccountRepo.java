/*
 * Copyright 2020-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.perit.credittransferservice.db.credittransferdb.repo;

import hu.perit.credittransferservice.db.credittransferdb.table.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import javax.persistence.LockModeType;
import java.util.Optional;

/**
 * @author Peter Nagy
 */

public interface AccountRepo extends JpaRepository<AccountEntity, Long>
{
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "select e from AccountEntity e where e.iban = :iban")
    Optional<AccountEntity> findByIbanWithWriteLock(String iban);

    Optional<AccountEntity> findByIban(String iban);
}
