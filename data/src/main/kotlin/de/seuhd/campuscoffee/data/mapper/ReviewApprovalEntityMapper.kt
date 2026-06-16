package de.seuhd.campuscoffee.data.mapper

import de.seuhd.campuscoffee.data.persistence.entities.ReviewApprovalEntity
import de.seuhd.campuscoffee.domain.model.objects.ReviewApproval
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingTarget
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [ReviewApproval] domain objects and [ReviewApprovalEntity] persistence
 * entities.
 */
@Mapper(componentModel = "spring")
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface ReviewApprovalEntityMapper : EntityMapper<ReviewApproval, ReviewApprovalEntity> {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    override fun updateEntity(
        source: ReviewApproval,
        @MappingTarget target: ReviewApprovalEntity
    )
}
