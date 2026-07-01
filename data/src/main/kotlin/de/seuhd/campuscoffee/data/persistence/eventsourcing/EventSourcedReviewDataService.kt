package de.seuhd.campuscoffee.data.persistence.eventsourcing
import de.seuhd.campuscoffee.data.configuration.PersistenceProperties
import de.seuhd.campuscoffee.data.implementations.ReviewDataServiceImpl
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.objects.Review
import de.seuhd.campuscoffee.domain.ports.data.ReviewDataService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Event-sourcing review data adapter, active only when `campus-coffee.persistence.mode` is
 * `event-sourcing`. A Decorator around the relational [ReviewDataServiceImpl] (both are adapters for the
 * same `ReviewDataService` port): the `delegate` is typed against the port and pinned to the relational bean
 * with `@Qualifier(ReviewDataServiceImpl.BEAN_NAME)`, so the wrapper shares only the interface with the
 * wrappee. The read methods and the `filter` queries delegate to it, while the
 * mutating methods write event-first. A review event records its POS and author as ids; the projector
 * resolves them against the read model when it applies the event.
 */
@Service
@Primary
@ConditionalOnProperty(
    name = [PersistenceProperties.MODE_PROPERTY],
    havingValue = PersistenceProperties.EVENT_SOURCING_MODE,
    // a missing mode key activates the decorator, matching PersistenceProperties' EVENT_SOURCING default
    matchIfMissing = true
)
class EventSourcedReviewDataService(
    @param:Qualifier(ReviewDataServiceImpl.BEAN_NAME) private val delegate: ReviewDataService,
    private val writer: EventSourcedWriter,
    private val reverter: EventSourcedReverter
) : ReviewDataService by delegate {
    @Transactional
    override fun upsert(domain: Review): Review =
        writer.upsert(
            domain,
            delegate::getById,
            { id, now -> domain.copy(id = id, createdAt = now, updatedAt = now) },
            { existing, now -> domain.copy(createdAt = existing.createdAt, updatedAt = now) }
        )

    @Transactional
    override fun delete(id: UUID) = writer.delete(Review::class, id, delegate::getById)

    @Transactional
    override fun clear() = writer.clear(Review::class, delegate::clear)

    @Transactional
    override fun revertLastChange(
        id: UUID,
        version: Long
    ): Review? =
        reverter.revertLastChange(Review::class, id, version, delegate::getById, { it.version }) { prior, current ->
            // approvalCount/approved are owned by the approval workflow; reverting an approval-driven change
            // would restore the old count while the review_approvals rows survive, desyncing the two
            if (prior["approvalCount"].toString() != current["approvalCount"].toString() ||
                prior["approved"].toString() != current["approved"].toString()
            ) {
                throw ValidationException(
                    "Reverting review $id would change its approval state, which the approval workflow owns."
                )
            }
        }
}
