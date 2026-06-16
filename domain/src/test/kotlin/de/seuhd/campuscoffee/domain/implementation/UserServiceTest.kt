package de.seuhd.campuscoffee.domain.implementation

import de.seuhd.campuscoffee.domain.model.objects.User
import de.seuhd.campuscoffee.domain.ports.data.PasswordHasher
import de.seuhd.campuscoffee.domain.ports.data.UserDataService
import de.seuhd.campuscoffee.domain.tests.TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [UserServiceImpl], which delegates to the [UserDataService] port: the login-name
 * lookup and the inherited id lookup must both resolve through that port, and a freshly supplied raw
 * password is hashed via the [PasswordHasher] port before it is persisted.
 */
@ExtendWith(MockitoExtension::class)
class UserServiceTest {
    @Mock
    private lateinit var userDataService: UserDataService

    @Mock
    private lateinit var passwordHasher: PasswordHasher

    private lateinit var userService: UserServiceImpl

    @BeforeEach
    fun setUp() {
        userService = UserServiceImpl(userDataService, passwordHasher)
    }

    @Test
    fun `getByLoginName returns the user resolved by the data service`() {
        val user = TestFixtures.getUserFixtures().first()
        whenever(userDataService.getByLoginName(user.loginName)).thenReturn(user)

        assertThat(userService.getByLoginName(user.loginName)).isEqualTo(user)
        verify(userDataService).getByLoginName(user.loginName)
    }

    @Test
    fun `getById returns the user resolved by the data service`() {
        // also pins that the service exposes the injected port (a null port would fail this lookup)
        val user = TestFixtures.getUserFixtures().first()
        val id = requireNotNull(user.id)
        whenever(userDataService.getById(id)).thenReturn(user)

        assertThat(userService.getById(id)).isEqualTo(user)
        verify(userDataService).getById(id)
    }

    @Test
    fun `upsert hashes a supplied raw password and clears it before persisting`() {
        val user = TestFixtures.getUserFixturesForInsertion().first().copy(password = "plaintext1")
        whenever(passwordHasher.hash("plaintext1")).thenReturn("{bcrypt}HASHED")
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val result = userService.upsert(user)

        // the raw password is hashed and nulled out, so it is never persisted or returned
        assertThat(result.passwordHash).isEqualTo("{bcrypt}HASHED")
        assertThat(result.password).isNull()
        verify(passwordHasher).hash("plaintext1")
    }

    @Test
    fun `upsert without a raw password does not invoke the hasher`() {
        val user = TestFixtures.getUserFixturesForInsertion().first().copy(password = null)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val result = userService.upsert(user)

        assertThat(result.passwordHash).isNull()
        verify(passwordHasher, never()).hash(any())
    }

    @Test
    fun `upsert without a raw password keeps the stored hash on update`() {
        val existing = TestFixtures.getUserFixtures().first().copy(passwordHash = "{bcrypt}STORED")
        val update = existing.copy(password = null, passwordHash = null, firstName = "Janet")
        whenever(userDataService.getById(existing.id!!)).thenReturn(existing)
        whenever(userDataService.upsert(any())).thenAnswer { it.arguments[0] as User }

        val result = userService.upsert(update)

        // the omitted password leaves the stored hash intact instead of nulling it
        assertThat(result.passwordHash).isEqualTo("{bcrypt}STORED")
        verify(passwordHasher, never()).hash(any())
    }
}
