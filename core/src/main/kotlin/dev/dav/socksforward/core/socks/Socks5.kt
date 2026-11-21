package dev.dav.socksforward.core.socks

/** SOCKS5 wire constants (RFC 1928) and username/password auth (RFC 1929). */
object Socks5 {
    const val VERSION = 0x05

    const val METHOD_NO_AUTH = 0x00
    const val METHOD_USERNAME_PASSWORD = 0x02
    const val METHOD_NOT_ACCEPTABLE = 0xFF

    const val AUTH_VERSION = 0x01
    const val AUTH_SUCCESS = 0x00
    const val AUTH_FAILURE = 0x01

    const val CMD_CONNECT = 0x01
    const val CMD_BIND = 0x02
    const val CMD_UDP_ASSOCIATE = 0x03

    const val ATYP_IPV4 = 0x01
    const val ATYP_DOMAIN = 0x03
    const val ATYP_IPV6 = 0x04

    const val REP_SUCCESS = 0x00
    const val REP_GENERAL_FAILURE = 0x01
    const val REP_NOT_ALLOWED = 0x02
    const val REP_NETWORK_UNREACHABLE = 0x03
    const val REP_HOST_UNREACHABLE = 0x04
    const val REP_CONNECTION_REFUSED = 0x05
    const val REP_TTL_EXPIRED = 0x06
    const val REP_COMMAND_NOT_SUPPORTED = 0x07
    const val REP_ATYP_NOT_SUPPORTED = 0x08
}
