package hu.perit.credittransferservice.db.credittransferdb.table;

import hu.perit.credittransferservice.services.model.CreditTransferStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

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
