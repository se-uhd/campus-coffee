package de.seuhd.campuscoffee.api.dtos

/**
 * Response body for `POST /api/auth/token`: the JWT bearer token returned by the endpoint.
 */
data class TokenResponseDto(
    val token: String
)
