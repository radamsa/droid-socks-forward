package dev.dav.socksforward.core.socks

import java.io.IOException

/**
 * Protocol level failure that maps directly to a SOCKS5 reply code.
 * Anything else (I/O, DNS, connect errors) is translated by the server.
 */
class SocksException(
    val replyCode: Int,
    message: String,
    /** True when a method/auth reply was already written and no SOCKS reply is expected. */
    val replySent: Boolean = false,
) : IOException(message)
