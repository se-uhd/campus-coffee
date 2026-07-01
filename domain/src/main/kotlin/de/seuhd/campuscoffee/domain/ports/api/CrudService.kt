package de.seuhd.campuscoffee.domain.ports.api

import de.seuhd.campuscoffee.domain.exceptions.ConcurrentUpdateException
import de.seuhd.campuscoffee.domain.exceptions.DeletionConflictException
import de.seuhd.campuscoffee.domain.exceptions.DuplicationException
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException
import de.seuhd.campuscoffee.domain.model.objects.DomainModel

/**
 * Generic service interface providing common CRUD operations.
 *
 * @param DOMAIN the domain object type managed by this service
 * @param ID     the type of the unique identifier (e.g., Long, UUID, String)
 */
interface CrudService<DOMAIN : DomainModel<ID>, ID> {
    /**
     * Clears all objects (i.e., deletes them).
     * Warning: This is a destructive operation typically used only for testing
     * or administrative purposes. Use with caution in production environments.
     */
    fun clear()

    /**
     * Retrieves all objects.
     *
     * @return a list of all objects; never null, but may be empty if no objects exist
     */
    fun getAll(): List<DOMAIN>

    /**
     * Retrieves a specific object by its unique identifier.
     *
     * @param id the unique identifier of the object to retrieve
     * @return the object with the specified ID
     * @throws NotFoundException if no object exists with the given ID
     */
    fun getById(id: ID): DOMAIN

    /**
     * Creates a new object or updates an existing one (an "upsert"):
     * if the object has no ID it is created, otherwise the existing object is updated.
     *
     * @param domainObject the object to create or update
     * @return the persisted object with populated ID and timestamps
     * @throws NotFoundException if attempting to update an object that does not exist
     * @throws DuplicationException if an object with duplicate unique fields already exists
     */
    fun upsert(domainObject: DOMAIN): DOMAIN

    /**
     * Deletes an object by its unique identifier.
     *
     * @param id the unique identifier of the object to delete
     * @throws NotFoundException if no object exists with the given ID
     * @throws DeletionConflictException if other data still references the object
     */
    fun delete(id: ID)

    /**
     * Reverts an object's last recorded change by appending a compensating event, guarded against
     * concurrent modifications: the revert proceeds only if the object's current version still equals
     * [version]. Available only in event-sourcing mode.
     *
     * @param id the unique identifier of the object whose last change is reverted
     * @param version the version the caller observed; the guard rejects the revert if the object moved past it
     * @return the object restored to its previous state, or null if the compensation removed it (the
     *   last change was its creation)
     * @throws NotFoundException if no object exists with the given ID
     * @throws ConcurrentUpdateException if the object was modified since [version] (reload and retry)
     */
    fun revertLastChange(
        id: ID,
        version: Long
    ): DOMAIN?
}
