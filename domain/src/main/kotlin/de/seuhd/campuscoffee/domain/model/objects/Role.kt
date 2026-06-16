package de.seuhd.campuscoffee.domain.model.objects

/**
 * Authorization role a user can hold. Roles are cumulative by convention: a moderator also holds [USER],
 * an admin also holds [USER] and [MODERATOR], so the endpoint checks stay simple `hasRole(...)` tests
 * without a Spring `RoleHierarchy`.
 */
enum class Role {
    USER,
    MODERATOR,
    ADMIN
}
