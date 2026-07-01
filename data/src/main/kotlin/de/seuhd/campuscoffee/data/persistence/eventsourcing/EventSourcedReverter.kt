package de.seuhd.campuscoffee.data.persistence.eventsourcing

import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.model.objects.DomainModel
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Reverts an entity's last recorded change by appending a compensating event and projecting it onto the
 * read tables, all within the caller's transaction (the decorator methods are `@Transactional`, so a
 * projection failure rolls the compensating event back too). Modeled on [EventSourcedWriter]: it holds no
 * per-type knowledge, the decorators pass the read-back and version accessors in as lambdas.
 *
 * The compensating event depends on the last recorded change of an entity that currently exists (the
 * guard loads it first):
 * - it was an **INSERT** (the entity was created): append a DELETE, removing the row again.
 * - it was an **UPDATE**: append an UPDATE carrying the immediately preceding snapshot body, restoring the
 *   entity to its previous state.
 *
 * A currently-deleted entity has no version to guard on, so undoing a deletion (an INSERT that re-creates
 * from the last snapshot before the delete) is deferred to the history endpoint and not reached here.
 *
 * Because the compensation is itself an appended event, reverts stack: reverting a revert re-applies the
 * change. The projection reuses [ReadModelProjector], so a compensating event enforces the same invariants
 * as any other write — a still-referenced INSERT->DELETE surfaces a deletion conflict, exactly as a normal
 * delete would.
 */
@Component
class EventSourcedReverter(
    private val eventStore: EventStore,
    private val eventRepository: EventRepository,
    private val projector: ReadModelProjector
) {
    /**
     * Reverts the last recorded change of the entity with [id], guarded by [version]. It loads the current
     * entity (a missing one throws [NotFoundException]) and rejects the revert with
     * [ConcurrentUpdateException] if the entity moved past [version], then appends and projects the
     * compensating event and reads the result back.
     *
     * @param domainType the domain type of the entity, used to label its events
     * @param id the id of the entity whose last change is reverted
     * @param version the version the caller observed; the guard compares it to the current version
     * @param getById reads the current entity back by id (for the guard and to return the reverted state)
     * @param versionOf reads the optimistic-locking version off a read entity, for the guard comparison
     * @param guardRestore called before an UPDATE restore with the prior and current bodies, so the caller
     *   can reject a restore that would leave dependent data inconsistent (e.g. a review's approval state)
     * @return the entity restored to its previous state, or null when the compensation removed it
     * @throws ValidationException when the entity's row has no creation event in the log to compensate against
     */
    fun <D : DomainModel<UUID>> revertLastChange(
        domainType: KClass<out DomainModel<*>>,
        id: UUID,
        version: Long,
        getById: (UUID) -> D,
        versionOf: (D) -> Long?,
        guardRestore: (prior: Map<String, Any?>, current: Map<String, Any?>) -> Unit = { _, _ -> }
    ): D? {
        val current = getById(id)
        if (versionOf(current) != version) {
            throw ConcurrentUpdateException(domainType.java, id)
        }
        val entityType = eventStore.entityTypeOf(domainType)
        val history = eventRepository.findByEntityTypeAndEntityIdOrderBySeqAsc(entityType, id.toString())
        if (history.firstOrNull()?.changeType != ChangeType.INSERT) {
            // a well-formed history begins with the entity's creation. A row whose log holds no INSERT
            // (created in relational mode and never imported) has nothing to compensate against, whether the
            // log is empty or holds only a stray later event.
            throw ValidationException("No recorded change to revert for ${domainType.simpleName} $id.")
        }
        val compensating = compensate(id, domainType, entityType, history, guardRestore)
        projector.apply(compensating)
        return if (compensating.changeType == ChangeType.DELETE) null else getById(id)
    }

    /**
     * Builds and appends the compensating event for the entity's last recorded change. The entity exists
     * (the guard loaded it), so its last event is an INSERT or an UPDATE: an INSERT is compensated by a
     * DELETE, an UPDATE by re-applying the immediately preceding snapshot, once [guardRestore] has approved
     * that restore.
     *
     * @param id the id of the entity being reverted
     * @param domainType the domain type, used to label a compensating DELETE
     * @param entityType the entity type label, used to label a compensating snapshot
     * @param history the entity's events in append order
     * @param guardRestore invoked with the prior and current bodies before an UPDATE restore
     */
    private fun compensate(
        id: UUID,
        domainType: KClass<out DomainModel<*>>,
        entityType: String,
        history: List<EventEntity>,
        guardRestore: (prior: Map<String, Any?>, current: Map<String, Any?>) -> Unit
    ): EventEntity =
        if (history.last().changeType == ChangeType.INSERT) {
            eventStore.appendDelete(domainType, id)
        } else {
            // the entity exists, so its last event is an UPDATE (not a DELETE): restore the prior snapshot,
            // once the caller has approved the restore (a DELETE would instead cascade dependent rows away)
            val prior = priorSnapshot(history)
            guardRestore(prior, requireNotNull(history.last().body) { "An update event must carry a body." })
            eventStore.appendFromBody(ChangeType.UPDATE, entityType, prior)
        }

    /**
     * The body of the snapshot immediately before the last event. Since the history begins with an INSERT
     * (the caller checks) and the last event is an UPDATE, the preceding event is a full-state snapshot (an
     * INSERT or an earlier UPDATE), so its body is a state to restore.
     *
     * @param history the entity's events in append order
     */
    private fun priorSnapshot(history: List<EventEntity>): Map<String, Any?> =
        requireNotNull(history[history.size - 2].body) { "A snapshot event must carry a body." }
}
