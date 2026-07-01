package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.ADMIN
import de.seuhd.campuscoffee.tests.SystemTestUtils.MODERATOR
import de.seuhd.campuscoffee.tests.SystemTestUtils.USER
import de.seuhd.campuscoffee.tests.SystemTestUtils.basicAuthHeader
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.posRequests
import de.seuhd.campuscoffee.tests.SystemTestUtils.reviewRequests
import de.seuhd.campuscoffee.tests.SystemTestUtils.userRequests
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.client.returnResult
import java.util.UUID

/**
 * System tests for the revert endpoints in the relational backend and for the access-control rules (which
 * hold in both modes). Reverting needs the event log, so in relational mode an authorized caller gets a
 * 400; the role gates reject the wrong role before the request reaches the service, so these cases need no
 * existing entity.
 */
class RevertSystemTests : AbstractSystemTest() {
    @Test
    fun `reverting a POS returns 401 Unauthorized without authentication`() {
        assertThat(posRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, null))
            .isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `reverting a POS returns 403 Forbidden for a USER`() {
        assertThat(posRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, USER))
            .isEqualTo(HttpStatus.FORBIDDEN.value())
    }

    @Test
    fun `reverting a POS as a moderator returns 400 Bad Request in relational mode`() {
        // the moderator role passes the gate, so the request reaches the service, which rejects a revert
        // without an event log
        assertThat(posRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, MODERATOR))
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `reverting a user requires an admin`() {
        // a plain USER is forbidden; an admin passes the gate and reaches the service (a 400 in relational mode)
        assertThat(userRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, USER))
            .isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat(userRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, ADMIN))
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `reverting a review requires a moderator`() {
        // a plain USER is forbidden; a moderator passes the gate and reaches the service (a 400 in relational mode)
        assertThat(reviewRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, USER))
            .isEqualTo(HttpStatus.FORBIDDEN.value())
        assertThat(reviewRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, MODERATOR))
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
    }
}

/**
 * System tests for the revert endpoints against the event sourcing backend, where reverting is supported:
 * reverting a creation removes the resource (204), reverting an update restores the previous state (200), a
 * stale version is rejected (409), and an unknown id is a 404.
 */
@TestPropertySource(properties = ["campus-coffee.persistence.mode=event-sourcing"])
class EventSourcingRevertSystemTests : AbstractSystemTest() {
    @Test
    fun `reverting a created POS returns 204 No Content and removes it`() {
        val created =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()

        assertThat(
            posRequests.revertAndReturnStatusCode(
                requireNotNull(created.id),
                requireNotNull(created.version),
                MODERATOR
            )
        ).isEqualTo(HttpStatus.NO_CONTENT.value())
        assertThat(posRequests.retrieveByIdStatusCode(requireNotNull(created.id)))
            .isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `reverting an updated POS returns 200 OK with the restored POS`() {
        val created =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()
        val updated = posRequests.update(listOf(created.copy(description = "Updated description"))).first()

        val reverted = posRequests.revert(requireNotNull(updated.id), requireNotNull(updated.version), MODERATOR)

        assertThat(reverted.description).isEqualTo(created.description)
    }

    @Test
    fun `reverting a POS with a stale version returns 409 Conflict`() {
        val created =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()
        // advance the row past the version the caller holds
        posRequests.update(listOf(created.copy(description = "Moved on"))).first()

        assertThat(
            posRequests.revertAndReturnStatusCode(
                requireNotNull(created.id),
                requireNotNull(created.version),
                MODERATOR
            )
        ).isEqualTo(HttpStatus.CONFLICT.value())
    }

    @Test
    fun `reverting an unknown POS returns 404 Not Found`() {
        assertThat(posRequests.revertAndReturnStatusCode(UUID.randomUUID(), 0L, MODERATOR))
            .isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `reverting a POS without the version parameter returns 400 Bad Request`() {
        // in event-sourcing mode a revert with a valid version succeeds, so a missing version returning 400
        // actually proves the parameter is required (unlike relational mode, which rejects every revert)
        val created =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()

        val status =
            client()
                .post()
                .uri("/api/pos/{id}/revert", requireNotNull(created.id))
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader(MODERATOR))
                .exchange()
                .returnResult<ByteArray>()
                .status
                .value()

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `reverting a just-registered user returns 204 No Content for an admin`() {
        // a fresh login/email, so registration does not collide with the seeded fixture users
        val newUser =
            TestFixtures.getUserFixturesForInsertion().first().copy(
                loginName = "revert_demo_user",
                emailAddress = "revert_demo_user@uni-heidelberg.de"
            )
        val registered = userRequests.create(listOf(userDtoMapper.fromDomain(newUser))).first()

        assertThat(
            userRequests.revertAndReturnStatusCode(
                requireNotNull(registered.id),
                requireNotNull(registered.version),
                ADMIN
            )
        ).isEqualTo(HttpStatus.NO_CONTENT.value())
    }
}
