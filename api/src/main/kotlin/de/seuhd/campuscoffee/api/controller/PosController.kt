package de.seuhd.campuscoffee.api.controller

import de.seuhd.campuscoffee.api.dtos.PosDto
import de.seuhd.campuscoffee.api.mapper.DtoMapper
import de.seuhd.campuscoffee.api.mapper.PosDtoMapper
import de.seuhd.campuscoffee.api.openapi.CrudOperation
import de.seuhd.campuscoffee.api.openapi.Operation.CREATE
import de.seuhd.campuscoffee.api.openapi.Operation.DELETE
import de.seuhd.campuscoffee.api.openapi.Operation.FILTER
import de.seuhd.campuscoffee.api.openapi.Operation.GET_ALL
import de.seuhd.campuscoffee.api.openapi.Operation.GET_BY_ID
import de.seuhd.campuscoffee.api.openapi.Operation.IMPORT
import de.seuhd.campuscoffee.api.openapi.Operation.UPDATE
import de.seuhd.campuscoffee.api.openapi.Resource.OSM_NODE
import de.seuhd.campuscoffee.api.openapi.Resource.POS
import de.seuhd.campuscoffee.domain.model.enums.CampusType
import de.seuhd.campuscoffee.domain.model.objects.Pos
import de.seuhd.campuscoffee.domain.model.objects.persistedId
import de.seuhd.campuscoffee.domain.ports.api.CrudService
import de.seuhd.campuscoffee.domain.ports.api.PosService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * Controller for handling POS-related API requests.
 */
@Tag(name = "Points of Sale (POS)", description = "Operations for managing coffee points of sale.")
@Controller
@RequestMapping("/pos")
class PosController(
    private val posService: PosService,
    private val posDtoMapper: PosDtoMapper
) : CrudController<Pos, PosDto, UUID>() {
    override fun service(): CrudService<Pos, UUID> = posService

    override fun mapper(): DtoMapper<Pos, PosDto> = posDtoMapper

    @Operation
    @CrudOperation(operation = GET_ALL, resource = POS)
    @GetMapping("")
    override fun getAll(): ResponseEntity<List<PosDto>> = super.getAll()

    @Operation
    @CrudOperation(operation = GET_BY_ID, resource = POS)
    @GetMapping("/{id}")
    override fun getById(
        @Parameter(description = "Unique identifier of the POS to retrieve.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<PosDto> = super.getById(id)

    @Operation
    @CrudOperation(operation = CREATE, resource = POS, roleRestricted = true)
    @PostMapping("")
    override fun create(
        @Parameter(description = "Data of the POS to create.", required = true)
        @RequestBody
        @Valid dto: PosDto
    ): ResponseEntity<PosDto> = super.create(dto)

    @Operation
    @CrudOperation(operation = UPDATE, resource = POS)
    @PutMapping("/{id}")
    override fun update(
        @Parameter(description = "Unique identifier of the POS to update.", required = true)
        @PathVariable id: UUID,
        @Parameter(description = "Data of the POS to update.", required = true)
        @RequestBody
        @Valid dto: PosDto
    ): ResponseEntity<PosDto> = super.update(id, dto)

    @Operation
    @CrudOperation(operation = DELETE, resource = POS)
    @DeleteMapping("/{id}")
    override fun delete(
        @Parameter(description = "Unique identifier of the POS to delete.", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<Void> = super.delete(id)

    /**
     * Retrieves the POS with the given name.
     *
     * @param name the name of the POS to retrieve
     */
    @Operation
    @CrudOperation(operation = FILTER, resource = POS)
    @GetMapping("/filter")
    fun filter(
        @Parameter(description = "Name of the POS to retrieve.", required = true)
        @RequestParam("name") name: String
    ): ResponseEntity<PosDto> = ResponseEntity.ok(posDtoMapper.fromDomain(posService.getByName(name)))

    /**
     * Imports a POS from an OpenStreetMap node and returns 201 Created with its location.
     *
     * @param nodeId     the OpenStreetMap node to import
     * @param campusType the campus type to assign to the imported POS
     */
    @Operation
    @CrudOperation(operation = IMPORT, resource = POS, externalResource = OSM_NODE)
    @PostMapping("/import/osm/{nodeId}")
    fun importFromOsm(
        @Parameter(description = "Unique identifier of the OpenStreetMap node to import.", required = true)
        @PathVariable nodeId: Long,
        @Parameter(description = "Campus type to assign to the imported POS.", required = true)
        @RequestParam("campus_type") campusType: CampusType
    ): ResponseEntity<PosDto> {
        val createdPos = posDtoMapper.fromDomain(posService.importFromOsmNode(nodeId, campusType))
        // the current request is the import URL, so the location must be built from the collection path
        return ResponseEntity.created(getLocation("/pos", createdPos.persistedId)).body(createdPos)
    }

    /**
     * Reverts the POS's last recorded change, guarded by the version the caller observed. Returns 200 with
     * the restored POS, or 204 when the reverted change was the POS's creation (the POS is removed).
     *
     * @param id      the POS whose last change is reverted
     * @param version the version the caller observed; a stale value is rejected with 409
     */
    @Operation(
        summary = "Revert the POS's last recorded change (event sourcing only), guarded by the observed version."
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "The POS restored to its previous state."),
            ApiResponse(
                responseCode = "204",
                description = "The reverted change was the POS's creation; it was removed."
            ),
            ApiResponse(
                responseCode = "400",
                description = "Reverting is unavailable in relational mode, or the version is missing."
            ),
            ApiResponse(responseCode = "401", description = "Authentication is required."),
            ApiResponse(responseCode = "403", description = "A moderator role is required."),
            ApiResponse(responseCode = "404", description = "No POS with the provided ID could be found."),
            ApiResponse(responseCode = "409", description = "The POS was modified since the provided version.")
        ]
    )
    @PostMapping("/{id}/revert")
    override fun revert(
        @Parameter(description = "Unique identifier of the POS to revert.", required = true)
        @PathVariable id: UUID,
        @Parameter(
            description = "The version the caller observed; a stale value is rejected with 409.",
            required = true
        )
        @RequestParam version: Long
    ): ResponseEntity<PosDto> = super.revert(id, version)
}
