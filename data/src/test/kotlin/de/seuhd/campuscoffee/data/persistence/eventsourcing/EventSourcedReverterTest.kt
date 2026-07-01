package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.objects.Pos
import de.seuhd.campuscoffee.domain.model.objects.Review
import de.seuhd.campuscoffee.domain.model.objects.ReviewApproval
import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Integration tests for [EventSourcedReverter] against a real PostgreSQL container: reverting the last
 * recorded change appends a compensating event and projects it, guarded by the version the caller observed.
 * Reverting a creation removes the row (an appended DELETE), reverting an update restores the prior snapshot
 * (an appended UPDATE), a stale version is rejected, and reverts stack because each is itself an event.
 */
class EventSourcedReverterTest : AbstractEventSourcingDataIntegrationTest() {
    @Test
    fun `reverting a created POS appends a DELETE event and removes the row`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        val id = requireNotNull(created.id)

        val reverted = posDataService.revertLastChange(id, requireNotNull(created.version))

        // the last change was the creation, so the compensation removes the row and returns nothing
        assertThat(reverted).isNull()
        assertThat(eventRepository.findAll().count { it.changeType == ChangeType.DELETE && it.entityType == "Pos" })
            .isEqualTo(1)
        assertThatThrownBy { posDataService.getById(id) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `reverting an updated POS appends a compensating UPDATE and restores the previous values`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        val updated = posDataService.upsert(created.copy(description = "Updated description"))

        val reverted = posDataService.revertLastChange(requireNotNull(updated.id), requireNotNull(updated.version))

        assertThat(reverted?.description).isEqualTo(created.description)
        // the compensation is a second UPDATE event (the user's update plus the revert)
        assertThat(eventRepository.findAll().count { it.changeType == ChangeType.UPDATE && it.entityType == "Pos" })
            .isEqualTo(2)
        assertThat(posDataService.getById(requireNotNull(created.id)).description).isEqualTo(created.description)
    }

    @Test
    fun `reverting with a stale version throws ConcurrentUpdateException`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        // someone else advances the row past the version the caller holds
        posDataService.upsert(created.copy(description = "Changed by someone else"))

        assertThatThrownBy {
            posDataService.revertLastChange(requireNotNull(created.id), requireNotNull(created.version))
        }.isInstanceOf(ConcurrentUpdateException::class.java)
    }

    @Test
    fun `reverting an unknown POS throws NotFoundException`() {
        assertThatThrownBy { posDataService.revertLastChange(UUID.randomUUID(), 0L) }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `reverting a row that exists but has no events throws ValidationException`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        // leave the read-model row but drop its events, the state of a row created in relational mode and
        // then served in event-sourcing mode without importing it into the log
        eventRepository.deleteAllInBatch()

        assertThatThrownBy {
            posDataService.revertLastChange(requireNotNull(created.id), requireNotNull(created.version))
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `reverting a lone update with no preceding creation event throws ValidationException`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        // drop the INSERT event but keep the row, then update once: the log now holds only a lone UPDATE,
        // with no creation event to compensate back to
        eventRepository.deleteAllInBatch()
        val updated = posDataService.upsert(created.copy(description = "Updated with no prior snapshot"))

        assertThatThrownBy {
            posDataService.revertLastChange(requireNotNull(updated.id), requireNotNull(updated.version))
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `reverting the creation of a POS still referenced by a review throws DeletionConflictException`() {
        val (_, pos, _) = seedReview()
        val current = posDataService.getById(requireNotNull(pos.id))

        // the POS's last change is its creation, but a review references it, so the compensating DELETE conflicts
        assertThatThrownBy {
            posDataService.revertLastChange(requireNotNull(current.id), requireNotNull(current.version))
        }.isInstanceOf(DeletionConflictException::class.java)
    }

    @Test
    fun `reverting a revert re-applies the update because each revert is itself an event`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        val updated = posDataService.upsert(created.copy(description = "Updated description"))

        val restored =
            requireNotNull(posDataService.revertLastChange(requireNotNull(updated.id), requireNotNull(updated.version)))
        assertThat(restored.description).isEqualTo(created.description)

        // reverting again undoes the revert, re-applying the original update
        val reReverted =
            posDataService.revertLastChange(requireNotNull(restored.id), requireNotNull(restored.version))

        assertThat(reReverted?.description).isEqualTo("Updated description")
    }

    @Test
    fun `reverting an updated User restores its previous values`() {
        val created = userDataService.upsert(TestFixtures.getUserFixturesForInsertion().first())
        val updated = userDataService.upsert(created.copy(firstName = "Renamed"))

        val reverted = userDataService.revertLastChange(requireNotNull(updated.id), requireNotNull(updated.version))

        assertThat(reverted?.firstName).isEqualTo(created.firstName)
    }

    @Test
    fun `reverting an updated Review restores its previous text`() {
        val (review, _, _) = seedReview()
        val updated = reviewDataService.upsert(review.copy(review = "An edited review, long enough to pass."))

        val reverted = reviewDataService.revertLastChange(requireNotNull(updated.id), requireNotNull(updated.version))

        assertThat(reverted?.review).isEqualTo(review.review)
    }

    @Test
    fun `reverting a review whose last change set the approval state throws ValidationException`() {
        val (review, _, _) = seedReview()
        val approver = userDataService.upsert(TestFixtures.getUserFixturesForInsertion()[1])
        reviewApprovalDataService.record(
            ReviewApproval(reviewId = requireNotNull(review.id), userId = requireNotNull(approver.id))
        )
        // mirror the approval workflow: the review's count/approved is bumped by an UPDATE event, so
        // reverting it would restore the old count while the recorded approval row survives
        val approved = reviewDataService.upsert(review.copy(approvalCount = 1, approved = true))

        assertThatThrownBy {
            reviewDataService.revertLastChange(requireNotNull(approved.id), requireNotNull(approved.version))
        }.isInstanceOf(ValidationException::class.java)
    }

    /** Seeds a POS, an author, and a review referencing both, and returns them (ids and versions assigned). */
    private fun seedReview(): Triple<Review, Pos, User> {
        val pos = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())
        val author =
            userDataService.upsert(
                TestFixtures.getUserFixturesForInsertion().first().copy(
                    passwordHash = $$"{bcrypt}$2a$10$seededhashvalue000000"
                )
            )
        val review =
            reviewDataService.upsert(
                Review(
                    pos = pos,
                    author = author,
                    review = "A review long enough to pass validation.",
                    approvalCount = 0,
                    approved = false
                )
            )
        return Triple(review, pos, author)
    }
}
