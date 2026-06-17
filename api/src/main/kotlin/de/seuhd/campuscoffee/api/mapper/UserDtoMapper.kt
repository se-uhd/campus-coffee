package de.seuhd.campuscoffee.api.mapper

import de.seuhd.campuscoffee.api.dtos.UserDto
import de.seuhd.campuscoffee.domain.model.objects.Role
import de.seuhd.campuscoffee.domain.model.objects.User
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean

/**
 * MapStruct mapper between [User] domain objects and [UserDto]s.
 *
 * The raw [UserDto.password] maps into the domain on the way in only; the domain's stored
 * `passwordHash` has no DTO counterpart, so it is never serialized. A request that omits the roles
 * defaults to {USER}, so a newly registered user is a plain user (the assignment hardens this into a
 * forced {USER} on registration, preventing self-promotion).
 */
@Mapper(componentModel = "spring", imports = [Role::class])
@ConditionalOnMissingBean // prevent IntelliJ warning about duplicate beans
interface UserDtoMapper : DtoMapper<User, UserDto> {
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(
        target = "roles",
        expression = "java(source.getRoles() != null ? source.getRoles() : java.util.Set.of(Role.USER))"
    )
    override fun toDomain(source: UserDto): User
}
