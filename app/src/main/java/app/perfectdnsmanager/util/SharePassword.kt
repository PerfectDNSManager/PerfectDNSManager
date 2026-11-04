package app.perfectdnsmanager.util

import java.security.SecureRandom

/** About 98 bits of entropy; v2 readers already accept arbitrary password lengths. */
object SharePassword {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    fun generate(random: SecureRandom = SecureRandom()): String = buildString(20) {
        repeat(20) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
