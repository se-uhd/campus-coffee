# Revert the last recorded change (event-sourced, optimistically guarded)

Design for the revert feature. **Builds on** the event sourcing persistence in
`doc/2026-06-18_configurable-event-sourcing-persistence.md` (the event log is the source of truth and
the relational tables are a projected read model).

## Context

CampusCoffee is event-sourced by default: the `events` table is the source of truth and the
relational tables are a read model projected from it (`ReadModelProjector`). A client can name a
specific entity and undo its **last recorded change**, not by deleting a log row (the log is
append-only) but by appending a **compensating event** whose projection reverts the change, in one
transaction.

Revert is a **read-modify-write**, so it needs lost-update protection: between a client's GET and its
revert POST, someone else's edit could become "the last change," and an unguarded revert would undo
*that* change instead. So the endpoint is **optimistically guarded**: the client sends the `version` it
is acting on, and the server reverts only if the entity's current version still matches, else returns
`409`. We back the guard with the existing JPA `@Version` optimistic-lock counter (on
`pos`/`users`/`reviews` from migrations `V4`/`V9`): it is purpose-built for this, present and live in
**both** persistence modes, needs no migration, and in event-sourcing mode it is a deterministic
function of the log that a rebuild reproduces exactly (so a client's held version stays valid across a
rebuild).

Everything the mechanism needs already exists in the data layer: `EventStore` appends events,
`ReadModelProjector.apply` projects any INSERT/UPDATE/DELETE, `EventSourcedWriter` shows the
append-then-project-in-one-`@Transactional` pattern, events are **full-state snapshots** (INSERT/UPDATE
bodies carry the whole object; a DELETE body carries only `{id}`), and the `idx_events_body_id` index
on `(body ->> 'id')` is already in place (unused). Gaps: no per-entity history query on
`EventRepository`, no domain port for "undo," and no `version` exposed on the DTO.

Hexagonal constraint (ArchUnit): **api may not import data**. The seam is a domain port implemented in
data; the controller calls only the domain service port.

**Scope:** guarded **revert-last** now, designed so the extension (expose history plus revert to any
version) is a rework-free add (see "History-ready seam").

## The compensating-event mechanism

Look up the entity's events ordered by `seq`; let `last` be the final one. The reverter implements the
compensating logic for every case (complete and unit-tested). The guarded endpoint loads the current
entity first, so it reaches the INSERT/UPDATE cases. Undoing a *deletion* (no current version to guard
on) arrives with `/history`:

| `last.changeType` | Compensating event | Read-model effect | Result |
|---|---|---|---|
| **INSERT** | `DELETE {id}` | row removed | `204 No Content` |
| **UPDATE** | `UPDATE` carrying the **prior** snapshot body (`history[size-2].body`) verbatim | restored to previous state | `200 OK` plus DTO |
| **DELETE** | `INSERT` carrying the prior snapshot body verbatim | row re-created | via `/history` later |
| no events / no current row | none | none | `404 Not Found` |

`history[size-2]` is always safe for UPDATE (an update is preceded by a snapshot). Re-appending the
stored body **verbatim** works uniformly, including the flattened Review body, because the projector
already consumes exactly that shape (`reconstructReview`). Correctness is inherited from the projector:
a still-referenced INSERT-then-DELETE gives `409 DeletionConflictException`, and a Review whose POS or
author is gone gives `404 NotFoundException`. **Restore is faithful**: the row equals its previous self
(old `updatedAt`), while the compensating *event* still stamps `createdAt = now` and a later `seq`,
recording when the revert happened. A revert is itself an appended event, so reverts stack (reverting a
revert re-applies the change), fully append-only and with no special-casing.

One Review exception: approving a review appends a Review UPDATE that bumps `approvalCount`/`approved`,
which the approval workflow owns, not an edit. Reverting that UPDATE would restore the old count while
the recorded `review_approvals` rows survive, desyncing the two, so the Review adapter rejects a revert
whose UPDATE changed the approval state (`400 ValidationException`); only text edits are revertible. The
generic reverter stays type-agnostic: the Review decorator passes this check in as a `guardRestore`
callback, the way it already passes `getById` and the version accessor.

## The optimistic guard

`POST /api/{res}/{id}/revert?version={v}` requires the `version` parameter. Omitting it returns `400`,
so an unguarded revert is impossible by construction. The reverter loads the current entity (`getById`
returns `404` when it is absent). If `current.version != version`, it throws
**`ConcurrentUpdateException`** (already mapped to `409`, "optimistic-lock conflict; reload and retry").
Otherwise it determines, appends, and projects the compensating event, all in the decorator's one
`@Transactional`. A concurrent double-revert is also caught by Hibernate `@Version` at projection time.
The guard has a side benefit: it blocks "delete an entity that was modified since I looked," because a
stale-`version` INSERT-revert returns `409` instead of deleting a changed row.

## Files to modify or create

### domain
- `ports/api/CrudService.kt` and `ports/data/CrudDataService.kt`: add
  `fun revertLastChange(id: ID, version: Long): DOMAIN?` (KDoc: restored object, or `null` when the
  compensation removed it; `@throws NotFoundException` when there is no such entity; `@throws
  ConcurrentUpdateException` when the version moved). Pos/User/Review inherit it typed.
- `implementation/CrudServiceImpl.kt`: implement once, delegating to `dataService().revertLastChange(...)`
  with logging (mirror `delete`).
- `model/objects/{Pos,User,Review}.kt`: add `version: Long? = null` (read-only aggregate version).

### data
- **new** `persistence/eventsourcing/EventSourcedReverter.kt` (`@Component`, modeled on
  `EventSourcedWriter`): injects `EventStore`, `EventRepository`, `ReadModelProjector`; implements the
  guard and the compensating-event logic; generic over the domain type plus a `getById` callback.
- `implementations/CrudDataServiceImpl.kt` (relational base, the decorators' delegate):
  `override fun revertLastChange(id, version) = throw ValidationException("Reverting is only available in event-sourcing mode.")`.
- `persistence/eventsourcing/EventSourced{Pos,User,Review}DataService.kt`: inject the reverter, add
  `@Transactional override fun revertLastChange(id, version) = reverter.revertLastChange(Pos::class, id, version, delegate::getById)`.
- `persistence/eventsourcing/EventStore.kt`: add `appendFromBody(changeType, entityType, body)` exposing
  the private `append` (for the UPDATE-restore compensating event; INSERT-revert reuses `appendDelete`).
- `persistence/eventsourcing/EventRepository.kt`: add the per-entity ordered history query (uses the
  existing indexes; no migration):
  ```kotlin
  @Query(value = "SELECT * FROM events WHERE entity_type = :entityType " +
      "AND body ->> 'id' = :id ORDER BY seq ASC", nativeQuery = true)
  fun findByEntityTypeAndEntityIdOrderBySeqAsc(
      @Param("entityType") entityType: String, @Param("id") id: String): List<EventEntity>
  ```
- `mapper/{Pos,User,Review}EntityMapper.kt`: map `version` on **read** (`fromEntity`, auto by name), and
  **ignore** it on write (`@Mapping(target = "version", ignore = true)` on `toEntity`/`updateEntity`),
  so Hibernate keeps managing `@Version` (the same pattern the mappers already use for timestamps).
- `persistence/eventsourcing/EventJsonMapper.kt`: **exclude `version` from event bodies** (a `@JsonIgnore`
  mixin for Pos/User; the Review serializer simply does not emit it). The log records state, not the
  read-model counter, and the projector ignores version on write regardless.

### api
- `controller/{Pos,User,Review}Controller.kt`: add (mirroring `ReviewController.approve`, with plain
  `@Operation` plus `@ApiResponses`, not `@CrudOperation`):
  ```kotlin
  @PostMapping("/{id}/revert")
  fun revert(@PathVariable id: UUID, @RequestParam version: Long): ResponseEntity<XDto> {
      val reverted = xService.revertLastChange(id, version)
      return reverted?.let { ResponseEntity.ok(xMapper.fromDomain(it)) } ?: ResponseEntity.noContent().build()
  }
  ```
- `dtos/{Pos,User,Review}Dto.kt`: add `version: Long? = null` (populated in responses; validation-exempt).
- `security/SecurityConfig.kt`: POS revert is already `MODERATOR` via `POST /api/pos/**`; add above the
  `authenticated` catch-all `POST /api/users/*/revert -> hasRole("ADMIN")` and
  `POST /api/reviews/*/revert -> hasRole("MODERATOR")` (aligns with the existing role matrix).

### docs
- `CHANGELOG.md` `## [Unreleased]` entry (bump the `gradle.properties` version only when cutting a
  release, since the version-sync CI check couples them). All new public declarations need KDoc (the
  `campus-coffee-kdoc` detekt rule; test sources are exempt).

## History-ready seam (not built now)

`findByEntityTypeAndEntityIdOrderBySeqAsc` already returns the **full** ordered history; the MVP reads
only its tail. Later: `GET /api/{res}/{id}/history` maps it to `HistoryEntryDto(seq, changeType,
timestamp[, snapshot])`; `POST .../revert?to={seq}&version={v}` reuses the reverter's DELETE branch to
undo deletions and reach any past snapshot, with `version` still the optimistic guard. The target is
addressed by the stable event `seq` (a log address), while `version` stays the "unchanged since read"
guard.

## Testing
- **Data-layer integration tests** (the primary coverage, since the logic lives here) extending
  `AbstractEventSourcingDataIntegrationTest`: revert after INSERT removes the row and appends a DELETE
  event; revert after UPDATE restores prior field values; a guard mismatch throws
  `ConcurrentUpdateException`; an unknown or absent id throws `NotFoundException`; a still-referenced
  INSERT-revert throws `DeletionConflictException`; a revert-of-revert round-trips.
- **System tests** through `POST /api/{res}/{id}/revert?version=` in **event-sourcing mode** (via the
  `EventSourcingSystemTests.kt` subclasses that flip `campus-coffee.persistence.mode=event-sourcing`):
  `204` (revert of create), `200` plus restored DTO (revert of update, response carries the new
  `version`), `409` (stale `version`), `400` (missing `version`), `404` (unknown id), plus role gating
  (401/403).
- **Relational mode:** one test asserting revert is rejected (`ValidationException`, `400`).
- `gradle build` runs **both** persistence modes plus ktlint, detekt, the KDoc rule set, and the
  coverage gate (at least 90% line, 80% branch). Use surviving PITest mutants
  (`gradle :data:pitest -Pmutation`) to find missing assertions.

## Manual verification (dev profile; event sourcing is default)
1. Start Postgres; `gradle :application:bootRun --args='--spring.profiles.active=dev'` (seeded fixtures).
2. As a moderator: `GET /api/pos/{id}` (note its `version`), `PUT` an edit, then
   `POST /api/pos/{id}/revert?version={version}` returns `200` with the pre-edit POS (and a bumped
   `version`); a stale `version` returns `409`. `SELECT change_type, entity_type FROM events ORDER BY
   seq` shows the appended compensating event.
3. `POST /api/pos` a new POS, then `POST /api/pos/{id}/revert?version=0` returns `204`; the log keeps
   both the INSERT and the compensating DELETE.
