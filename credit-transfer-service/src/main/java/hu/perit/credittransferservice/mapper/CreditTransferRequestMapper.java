package hu.perit.credittransferservice.mapper;

import hu.perit.credittransferservice.db.credittransferdb.table.CreditTransferEntity;
import hu.perit.credittransferservice.services.model.CreditTransferRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CreditTransferRequestMapper
{
    @Mapping(target = "statusCode", expression = "java(1L)")
    CreditTransferEntity toEntity(CreditTransferRequest request);

    CreditTransferRequest fromEntity(CreditTransferEntity entity);
}
