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

package hu.perit.credittransferservice.db.credittransferdb.table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Version;
import java.math.BigDecimal;


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
