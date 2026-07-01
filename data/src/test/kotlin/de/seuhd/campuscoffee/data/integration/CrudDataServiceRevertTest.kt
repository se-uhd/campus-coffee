package de.seuhd.campuscoffee.data.integration

import de.seuhd.campuscoffee.domain.exceptions.ValidationException
import de.seuhd.campuscoffee.domain.ports.data.PosDataService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Verifies that the relational adapter rejects a revert: with no event log there is nothing to compensate
 * against, so the relational `revertLastChange` throws a [ValidationException]. The event-sourcing decorator
 * implements the real behavior (covered by `EventSourcedReverterTest`).
 */
class CrudDataServiceRevertTest : AbstractDataIntegrationTest() {
    @Autowired
    private lateinit var posDataService: PosDataService

    @Test
    fun `revertLastChange throws ValidationException in relational mode`() {
        val created = posDataService.upsert(TestFixtures.getPosFixturesForInsertion().first())

        assertThatThrownBy {
            posDataService.revertLastChange(requireNotNull(created.id), requireNotNull(created.version))
        }.isInstanceOf(ValidationException::class.java)
    }
}
