package hu.perit.credittransferservice.services.model;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public enum CreditTransferStatus
{
    REQUESTED(1L),
    EXECUTED(2L),
    FAILED(3L);

    private long code;
    private static Map<Long, CreditTransferStatus> mappings;

    private static synchronized Map<Long, CreditTransferStatus> getMappings()
    {
        if (mappings == null)
        {
            mappings = new HashMap<>();
        }

        return mappings;
    }

    CreditTransferStatus(Long code)
    {
        this.code = code;
        getMappings().put(code, this);
    }

    public static CreditTransferStatus fromCode(Long statusCode)
    {
        if (getMappings().containsKey(statusCode))
        {
            return getMappings().get(statusCode);
        }

        throw new IllegalStateException("Unexpected value: " + statusCode);
    }
}
