package dev.dav.socksforward.core.socks

import dev.dav.socksforward.core.model.AuthCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Socks5HandshakeTest {

    private val out = ByteArrayOutputStream()

    private fun handshake(vararg input: Byte): Socks5Handshake =
        Socks5Handshake(ByteArrayInputStream(input), out)

    @Test
    fun `no auth greeting`() {
        handshake(0x05, 0x01, 0x00).negotiate(null)
        assertContentEquals(byteArrayOf(0x05, 0x00), out.toByteArray())
    }

    @Test
    fun `rejects client without required method`() {
        val error = assertFailsWith<SocksException> {
            handshake(0x05, 0x01, 0x00).negotiate(AuthCredentials("u", "p"))
        }
        assertEquals(Socks5.REP_NOT_ALLOWED, error.replyCode)
        assertTrue(error.replySent)
        assertContentEquals(byteArrayOf(0x05, 0xFF.toByte()), out.toByteArray())
    }

    @Test
    fun `accepts username password auth`() {
        val credentials = AuthCredentials("user", "secret")
        val bytes = byteArrayOf(0x05, 0x01, 0x02, 0x01, 0x04) + "user".toByteArray() +
            byteArrayOf(0x06) + "secret".toByteArray()
        handshake(*bytes).negotiate(credentials)
        assertContentEquals(
            byteArrayOf(0x05, 0x02, 0x01, 0x00),
            out.toByteArray(),
        )
    }

    @Test
    fun `rejects wrong password`() {
        val credentials = AuthCredentials("user", "secret")
        val bytes = byteArrayOf(0x05, 0x01, 0x02, 0x01, 0x04) + "user".toByteArray() +
            byteArrayOf(0x05) + "wrong".toByteArray()
        val error = assertFailsWith<SocksException> { handshake(*bytes).negotiate(credentials) }
        assertEquals(Socks5.REP_NOT_ALLOWED, error.replyCode)
        assertTrue(error.replySent)
        assertContentEquals(byteArrayOf(0x05, 0x02, 0x01, 0x01), out.toByteArray())
    }

    @Test
    fun `parses ipv4 connect request`() {
        val bytes = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            93.toByte(), 184.toByte(), 216.toByte(), 34,
            0x00, 0x50,
        )
        val request = handshake(*bytes).readRequest()
        assertEquals("93.184.216.34", request.host)
        assertEquals(80, request.port)
        assertEquals("93.184.216.34", request.address?.hostAddress)
    }

    @Test
    fun `parses domain connect request`() {
        val domain = "example.com"
        val bytes = byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.length.toByte()) +
            domain.toByteArray() + byteArrayOf(0x01, 0xBB.toByte())
        val request = handshake(*bytes).readRequest()
        assertEquals("example.com", request.host)
        assertEquals(443, request.port)
        assertEquals(null, request.address)
        assertEquals("example.com:443", request.display)
    }

    @Test
    fun `parses ipv6 connect request`() {
        val address = ByteArray(16).also { it[15] = 1 }
        val bytes = byteArrayOf(0x05, 0x01, 0x00, 0x04) + address + byteArrayOf(0x00, 0x50)
        val request = handshake(*bytes).readRequest()
        assertEquals("0:0:0:0:0:0:0:1", request.host)
        assertEquals(80, request.port)
        assertEquals("[0:0:0:0:0:0:0:1]:80", request.display)
    }

    @Test
    fun `rejects unsupported command`() {
        val bytes = byteArrayOf(0x05, 0x03, 0x00, 0x01, 1, 2, 3, 4, 0x00, 0x50)
        val error = assertFailsWith<SocksException> { handshake(*bytes).readRequest() }
        assertEquals(Socks5.REP_COMMAND_NOT_SUPPORTED, error.replyCode)
    }

    @Test
    fun `rejects unsupported address type`() {
        val bytes = byteArrayOf(0x05, 0x01, 0x00, 0x09)
        val error = assertFailsWith<SocksException> { handshake(*bytes).readRequest() }
        assertEquals(Socks5.REP_ATYP_NOT_SUPPORTED, error.replyCode)
    }

    @Test
    fun `truncated request fails cleanly`() {
        val bytes = byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2)
        assertFailsWith<EOFException> { handshake(*bytes).readRequest() }
    }

    @Test
    fun `success reply contains bound address`() {
        handshake().sendReply(Socks5.REP_SUCCESS, java.net.InetAddress.getByName("127.0.0.1"), 1080)
        assertContentEquals(
            byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x04, 0x38),
            out.toByteArray(),
        )
    }
}
