package de.seuhd.campuscoffee.domain.model.objects

/**
 * Authorization role a user can hold. Roles are an independent set of capabilities, not a rank: [USER] is
 * the base and is always held — registration grants it and an admin cannot strip it; [MODERATOR] grants
 * content moderation and [ADMIN] grants user administration (including role changes) as independent grants
 * on top. Beyond that baseline a user holds exactly the roles assigned; checks test set membership
 * (`Role.X in roles`), and there is deliberately no Spring `RoleHierarchy`.
 */
enum class Role {
    USER,
    MODERATOR,
    ADMIN
}
