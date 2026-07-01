package de.seuhd.campuscoffee.data.persistence.eventsourcing

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Repository for the append-only event log.
 */
interface EventRepository : JpaRepository<EventEntity, UUID> {
    /** All events in append order (by the monotonic [EventEntity.seq]), for replaying the whole log. */
    fun findAllByOrderBySeqAsc(): List<EventEntity>

    /**
     * One entity's events in append order, for the revert feature to find its last recorded change and
     * the snapshot before it. Filters on both the entity type and the id inside the body (the DELETE body
     * also carries the id), so an id shared across types cannot cross-match. Uses the `entity_type` and
     * `body ->> 'id'` indexes.
     *
     * @param entityType the entity type label (the domain class's simple name)
     * @param id the domain object's id, as its string form (matched against the body's `id`)
     */
    @Query(
        value =
            "SELECT * FROM events WHERE entity_type = :entityType AND body ->> 'id' = :id ORDER BY seq ASC",
        nativeQuery = true
    )
    fun findByEntityTypeAndEntityIdOrderBySeqAsc(
        @Param("entityType") entityType: String,
        @Param("id") id: String
    ): List<EventEntity>

    /**
     * Whether the log already holds at least one event for the given domain type, so the import can skip it.
     *
     * @param entityType the entity type label (the domain class's simple name)
     */
    fun existsByEntityType(entityType: String): Boolean

    /**
     * Removes every event for the given domain type, when clearing that type's data.
     *
     * @param entityType the entity type label (the domain class's simple name)
     */
    fun deleteByEntityType(entityType: String)
}
