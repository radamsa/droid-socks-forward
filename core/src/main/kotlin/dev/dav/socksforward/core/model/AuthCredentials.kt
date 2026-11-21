package dev.dav.socksforward.core.model

/**
 * Optional SOCKS5 username/password credentials (RFC 1929).
 *
 * Only printable ASCII is accepted so the bytes survive the wire format
 * without any encoding ambiguity.
 */
data class AuthCredentials(
    val username: String,
    val password: String,
) {
    init {
        require(username.length in 1..MAX_FIELD_LENGTH) { "username must be 1..$MAX_FIELD_LENGTH chars" }
        require(password.length in 1..MAX_FIELD_LENGTH) { "password must be 1..$MAX_FIELD_LENGTH chars" }
        require(username.all { it.code in PRINTABLE_ASCII }) { "username must be printable ASCII" }
        require(password.all { it.code in PRINTABLE_ASCII }) { "password must be printable ASCII" }
    }

    companion object {
        const val MAX_FIELD_LENGTH = 255
        private val PRINTABLE_ASCII = 0x20..0x7E
    }
}
