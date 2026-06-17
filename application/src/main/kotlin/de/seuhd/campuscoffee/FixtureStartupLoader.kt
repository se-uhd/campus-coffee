package de.seuhd.campuscoffee

import de.seuhd.campuscoffee.domain.ports.api.PosService
import de.seuhd.campuscoffee.domain.ports.api.ReviewService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.domain.ports.data.ReviewApprovalDataService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Loads the fixture data on startup when `campus-coffee.fixtures.load-on-startup` is true and the
 * database has no users yet. The prod deployment uses this to populate a fresh database, because the
 * prod profile does not register the `/api/dev` endpoints that load the data during local development.
 */
@Component
@ConditionalOnProperty("campus-coffee.fixtures.load-on-startup", havingValue = "true")
class FixtureStartupLoader(
    private val userService: UserService,
    private val posService: PosService,
    private val reviewService: ReviewService,
    private val reviewApprovalDataService: ReviewApprovalDataService
) {
    @EventListener(ApplicationReadyEvent::class)
    fun loadOnStartup() {
        if (userService.getAll().isNotEmpty()) {
            log.info("Skipping the fixture load: the database already has users.")
            return
        }
        val (users, pos, reviews) =
            TestFixtures.loadAll(userService, posService, reviewService, reviewApprovalDataService)
        log.info("Loaded the fixture data on startup: {} users, {} POS, {} reviews.", users, pos, reviews)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(FixtureStartupLoader::class.java)
    }
}
