package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.api.dtos.ReviewDto
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import de.seuhd.campuscoffee.tests.SystemTestUtils.client
import de.seuhd.campuscoffee.tests.SystemTestUtils.posRequests
import de.seuhd.campuscoffee.tests.SystemTestUtils.reviewRequests
import de.seuhd.campuscoffee.tests.SystemTestUtils.userRequests
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.returnResult

/**
 * System tests that pin the HTTP status codes produced by the global exception handler:
 * duplicate unique fields and blocked deletions return 409, missing entities return 404, and invalid
 * input returns 400.
 */
class ErrorPathSystemTests : AbstractSystemTest() {
    @Test
    fun `creating a POS with a duplicate name returns 409 Conflict`() {
        val pos = posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())
        posRequests.create(listOf(pos))

        val statusCode = posRequests.createAndReturnStatusCodes(listOf(pos)).first()

        assertThat(statusCode).isEqualTo(HttpStatus.CONFLICT.value())
    }

    @Test
    fun `creating a user with a duplicate login name returns 409 Conflict`() {
        val user = userDtoMapper.fromDomain(TestFixtures.getUserFixturesForInsertion().first())
        userRequests.create(listOf(user))

        val statusCode = userRequests.createAndReturnStatusCodes(listOf(user)).first()

        assertThat(statusCode).isEqualTo(HttpStatus.CONFLICT.value())
    }

    @Test
    fun `creating a POS with an id in the body returns 400 and does not update the existing POS`() {
        val created =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()

        // the server assigns ids; a POST carrying an existing id must not silently become an update
        val hijack = created.copy(name = "Hijacked name")
        val statusCode = posRequests.createAndReturnStatusCodes(listOf(hijack)).first()

        assertThat(statusCode).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(posRequests.retrieveById(created.id!!).name).isEqualTo(created.name)
    }

    @Test
    fun `deleting a POS or user that has reviews returns 409 Conflict`() {
        val pos =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()
        val user =
            userRequests
                .create(listOf(userDtoMapper.fromDomain(TestFixtures.getUserFixturesForInsertion().first())))
                .first()
        reviewRequests.create(
            listOf(ReviewDto(posId = pos.id, authorId = user.id, review = "A review that blocks deletion."))
        )

        // the review references both, so deleting either parent is a conflict, not a 500
        assertThat(posRequests.deleteAndReturnStatusCodes(listOf(pos.id!!)).first())
            .isEqualTo(HttpStatus.CONFLICT.value())
        assertThat(userRequests.deleteAndReturnStatusCodes(listOf(user.id!!)).first())
            .isEqualTo(HttpStatus.CONFLICT.value())
    }

    @Test
    fun `fetching an unknown id returns 404 Not Found for POS, users, and reviews`() {
        assertThat(posRequests.retrieveByIdStatusCode(MISSING_ID)).isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(userRequests.retrieveByIdStatusCode(MISSING_ID)).isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(reviewRequests.retrieveByIdStatusCode(MISSING_ID)).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `filtering by a value that matches nothing returns 404 Not Found`() {
        assertThat(posRequests.retrieveByFilterStatusCode("name", "NoSuchPosName"))
            .isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(userRequests.retrieveByFilterStatusCode("login_name", "no_such_login"))
            .isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `updating a POS that does not exist returns 404 Not Found`() {
        val missing =
            posDtoMapper
                .fromDomain(TestFixtures.getPosFixturesForInsertion().first())
                .copy(id = MISSING_ID)

        val statusCode = posRequests.updateAndReturnStatusCodes(listOf(missing)).first()

        assertThat(statusCode).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun `updating with a path id that differs from the body id returns 400 Bad Request`() {
        val created =
            posRequests
                .create(listOf(posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first())))
                .first()

        val statusCode = posRequests.updateWithPathIdAndReturnStatusCode(created.id!! + 1, created)

        assertThat(statusCode).isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `creating a POS with a blank required field returns 400 Bad Request naming the field`() {
        val invalid = posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first()).copy(city = "")

        // the validation handler names the rejected field in the message; assert the name, not the exact text
        val result =
            client()
                .post()
                .uri("/api/pos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(invalid)
                .exchange()
                .returnResult<String>()

        assertThat(result.status.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(result.responseBody).contains("city")
    }

    @Test
    fun `creating a POS without a name returns 400 Bad Request naming the field`() {
        // @Size alone treats null as valid; @NotBlank must reject a missing name as a 400, not a 500
        val invalid = posDtoMapper.fromDomain(TestFixtures.getPosFixturesForInsertion().first()).copy(name = null)

        val result =
            client()
                .post()
                .uri("/api/pos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(invalid)
                .exchange()
                .returnResult<String>()

        assertThat(result.status.value()).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(result.responseBody).contains("name")
    }

    @Test
    fun `creating a review with text that is too short returns 400 Bad Request`() {
        // an empty review is rejected by bean validation; this pins the controller-to-400 mapping for a
        // validation failure without depending on the exact length bounds
        val invalid = ReviewDto(posId = 1L, authorId = 1L, review = "")

        assertThat(reviewRequests.createAndReturnStatusCodes(listOf(invalid)).first())
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `creating a review without a POS or author returns 400 Bad Request`() {
        val missingPos = ReviewDto(posId = null, authorId = 1L, review = "Valid length review text.")
        val missingAuthor = ReviewDto(posId = 1L, authorId = null, review = "Valid length review text.")

        assertThat(reviewRequests.createAndReturnStatusCodes(listOf(missingPos)).first())
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(reviewRequests.createAndReturnStatusCodes(listOf(missingAuthor)).first())
            .isEqualTo(HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun `requesting an unmapped path returns 404 Not Found with a clean error body`() {
        // no controller maps this path, so it falls through to a NoResourceFoundException -> 404; the
        // handler renders it without leaking the framework wording ("No static resource ...") or class name
        val result =
            client()
                .get()
                .uri("/api/this-endpoint-does-not-exist")
                .exchange()
                .returnResult<String>()

        assertThat(result.status.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
        val body = result.responseBody ?: ""
        assertThat(body).contains("NotFound")
        assertThat(body).contains("No endpoint found for")
        assertThat(body).contains("/api/this-endpoint-does-not-exist")
        assertThat(body).doesNotContain("static resource")
        assertThat(body).doesNotContain("NoResourceFoundException")
    }

    @Test
    fun `using the wrong HTTP method returns 405 Method Not Allowed`() {
        // the OSM import endpoint is POST-only; a GET (e.g., opening it in a browser) must be 405, not 500
        val status =
            client()
                .get()
                .uri("/api/pos/import/osm/123?campus_type=INF")
                .exchange()
                .returnResult<ByteArray>()
                .status
                .value()

        assertThat(status).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value())
    }

    private companion object {
        const val MISSING_ID = 9999L
    }
}
