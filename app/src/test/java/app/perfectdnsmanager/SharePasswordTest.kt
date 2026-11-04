package app.perfectdnsmanager
import app.perfectdnsmanager.util.SharePassword
import org.junit.Assert.*
import org.junit.Test
class SharePasswordTest {
    @Test fun generatedSecretsHaveSufficientLengthAndNoAmbiguousCharacters() {
        val passwords=(1..100).map { SharePassword.generate() }
        assertEquals(100,passwords.toSet().size)
        assertTrue(passwords.all { it.matches(Regex("[abcdefghjkmnpqrstuvwxyz23456789]{20}")) })
    }
}
