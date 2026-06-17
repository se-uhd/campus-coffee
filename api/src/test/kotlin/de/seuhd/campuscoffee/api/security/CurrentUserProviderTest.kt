package de.seuhd.campuscoffee.api.security

import de.seuhd.campuscoffee.domain.ports.api.UserService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

/**
 * Tests [CurrentUserProvider], the bridge from the Spring Security principal to the domain [User].
 */
class CurrentUserProviderTest {
    private val userService: UserService = mock()
    private val currentUserProvider = CurrentUserProvider(userService)

    @Test
    fun `currentUser is not yet implemented`() {
        // TODO (Exercise 2): a placeholder for the unimplemented stub. Once you implement `currentUser()`,
        //  this assertion will fail because the stub no longer throws. Replace it with real tests: stub a
        //  SecurityContext with an authenticated principal and a UserService that returns a fixture user,
        //  assert `currentUser()` returns that user, and assert it throws when no one is authenticated.
        assertThrows<NotImplementedError> { currentUserProvider.currentUser() }
    }
}
