package dev.dav.socksforward.core.socks

import dev.dav.socksforward.core.model.AuthCredentials
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Blocking implementation of the SOCKS5 greeting, optional RFC 1929 auth and
 * the CONNECT request. Intentionally dumb: no timeouts, no sockets — the
 * caller owns those and can unit test this class with byte streams.
 */
class Socks5Handshake(
    private val input: InputStream,
    private val output: OutputStream,
) {

    fun negotiate(auth: AuthCredentials?): Unit {
        val version = readByte()
        if (version != Socks5.VERSION) {
            throw SocksException(Socks5.REP_GENERAL_FAILURE, "Unsupported SOCKS version: $version")
        }
        val methodCount = readByte()
        if (methodCount == 0) {
            throw SocksException(Socks5.REP_GENERAL_FAILURE, "Client offered no auth methods")
        }
        val methods = readBytes(methodCount)
        val required = if (auth != null) Socks5.METHOD_USERNAME_PASSWORD else Socks5.METHOD_NO_AUTH

        if (methods.none { (it.toInt() and 0xFF) == required }) {
            writeBytes(byteArrayOf(Socks5.VERSION.toByte(), Socks5.METHOD_NOT_ACCEPTABLE.toByte()))
            throw SocksException(
                Socks5.REP_NOT_ALLOWED,
                "Client does not support the required auth method",
                replySent = true,
            )
        }

        writeBytes(byteArrayOf(Socks5.VERSION.toByte(), required.toByte()))
        if (required == Socks5.METHOD_USERNAME_PASSWORD) {
            authenticate(auth!!)
        }
    }

    private fun authenticate(auth: AuthCredentials) {
        val version = readByte()
        if (version != Socks5.AUTH_VERSION) {
            writeBytes(byteArrayOf(Socks5.AUTH_VERSION.toByte(), Socks5.AUTH_FAILURE.toByte()))
            throw SocksException(Socks5.REP_NOT_ALLOWED, "Unsupported auth version: $version", replySent = true)
        }
        val username = readString(readByte())
        val password = readString(readByte())

        val valid = constantTimeEquals(username, auth.username) and
            constantTimeEquals(password, auth.password)

        writeBytes(
            byteArrayOf(
                Socks5.AUTH_VERSION.toByte(),
                if (valid) Socks5.AUTH_SUCCESS.toByte() else Socks5.AUTH_FAILURE.toByte(),
            ),
        )
        if (!valid) {
            throw SocksException(Socks5.REP_NOT_ALLOWED, "Неверный логин или пароль", replySent = true)
        }
    }

    fun readRequest(): SocksRequest {
        val version = readByte()
        if (version != Socks5.VERSION) {
            throw SocksException(Socks5.REP_GENERAL_FAILURE, "Unsupported SOCKS version: $version")
        }
        val command = readByte()
        readByte() // reserved
        val addressType = readByte()

        if (command != Socks5.CMD_CONNECT) {
            throw SocksException(
                Socks5.REP_COMMAND_NOT_SUPPORTED,
                when (command) {
                    Socks5.CMD_BIND -> "Команда BIND не поддерживается"
                    Socks5.CMD_UDP_ASSOCIATE -> "Команда UDP ASSOCIATE не поддерживается"
                    else -> "Неизвестная команда: $command"
                },
            )
        }

        val (host, address) = when (addressType) {
            Socks5.ATYP_IPV4 -> {
                val bytes = readBytes(4)
                val inet = InetAddress.getByAddress(bytes)
                inet.hostAddress to inet
            }

            Socks5.ATYP_IPV6 -> {
                val bytes = readBytes(16)
                val inet = InetAddress.getByAddress(bytes)
                inet.hostAddress to inet
            }

            Socks5.ATYP_DOMAIN -> {
                val length = readByte()
                if (length == 0) {
                    throw SocksException(Socks5.REP_GENERAL_FAILURE, "Пустое доменное имя")
                }
                val name = readString(length)
                name to null
            }

            else -> throw SocksException(
                Socks5.REP_ATYP_NOT_SUPPORTED,
                "Неизвестный тип адреса: $addressType",
            )
        }

        val port = (readByte() shl 8) or readByte()
        if (port == 0) {
            throw SocksException(Socks5.REP_GENERAL_FAILURE, "Некорректный порт назначения")
        }
        return SocksRequest(host, port, address)
    }

    /**
     * Sends a reply. When [bindAddress] is null (failures) an empty IPv4
     * address is sent, which is what most clients expect.
     */
    fun sendReply(code: Int, bindAddress: InetAddress?, bindPort: Int) {
        val address = bindAddress ?: InetAddress.getByAddress(ByteArray(4))
        val header = byteArrayOf(
            Socks5.VERSION.toByte(),
            code.toByte(),
            0x00,
            if (address is Inet6Address) Socks5.ATYP_IPV6.toByte() else Socks5.ATYP_IPV4.toByte(),
        )
        val portBytes = byteArrayOf((bindPort ushr 8).toByte(), bindPort.toByte())
        writeBytes(header + address.address + portBytes)
    }

    fun sendFailure(code: Int) = sendReply(code, null, 0)

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("Соединение закрыто клиентом")
        return value
    }

    private fun readBytes(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("Соединение закрыто клиентом")
            offset += read
        }
        return buffer
    }

    private fun readString(count: Int): String =
        String(readBytes(count), StandardCharsets.ISO_8859_1)

    private fun writeBytes(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        val a = actual.toByteArray(StandardCharsets.ISO_8859_1)
        val b = expected.toByteArray(StandardCharsets.ISO_8859_1)
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
