package dev.dav.socksforward.core

import dev.dav.socksforward.core.model.AuthCredentials
import dev.dav.socksforward.core.model.ProxyConfig
import dev.dav.socksforward.core.model.ProxyStats
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class Socks5ProxyServerTest {

    @Test
    fun `proxies a connect request`() = runBlocking<Unit> {
        EchoServer().use { echo ->
            val listener = RecordingListener()
            val server = Socks5ProxyServer(ProxyConfig(port = 0), listener)
            server.start().getOrThrow()
            try {
                val proxyPort = listener.listenPort ?: fail("server did not start")
                Socket("127.0.0.1", proxyPort).use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream()
                    val output = client.getOutputStream()

                    output.write(byteArrayOf(0x05, 0x01, 0x00))
                    output.flush()
                    assertEquals(0x05, input.read())
                    assertEquals(0x00, input.read())

                    writeConnectRequest(output, "127.0.0.1", echo.port)
                    assertConnectReply(input)

                    output.write("hello".toByteArray())
                    output.flush()
                    val reply = ByteArray(5)
                    readFully(input, reply)
                    assertEquals("hello", String(reply))
                }

                val stats = listener.awaitStats { it.totalConnections == 1L && it.bytesUp >= 5 && it.bytesDown >= 5 }
                assertEquals(1L, stats.totalConnections)
                assertEquals(0L, stats.failedConnections)
                assertTrue(stats.bytesUp >= 5)
                assertTrue(stats.bytesDown >= 5)
            } finally {
                server.stop()
            }
            assertTrue(listener.stopped)
        }
    }

    @Test
    fun `rejects clients without the required auth method`() = runBlocking<Unit> {
        val listener = RecordingListener()
        val server = Socks5ProxyServer(
            ProxyConfig(port = 0, auth = AuthCredentials("user", "secret")),
            listener,
        )
        server.start().getOrThrow()
        try {
            Socket("127.0.0.1", listener.listenPort!!).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                client.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                assertEquals(0x05, input.read())
                assertEquals(0xFF, input.read())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `authenticates and proxies`() = runBlocking<Unit> {
        EchoServer().use { echo ->
            val listener = RecordingListener()
            val server = Socks5ProxyServer(
                ProxyConfig(port = 0, auth = AuthCredentials("user", "secret")),
                listener,
            )
            server.start().getOrThrow()
            try {
                Socket("127.0.0.1", listener.listenPort!!).use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream()
                    val output = client.getOutputStream()

                    output.write(byteArrayOf(0x05, 0x01, 0x02))
                    output.flush()
                    assertEquals(0x05, input.read())
                    assertEquals(0x02, input.read())

                    output.write(
                        byteArrayOf(0x01, 0x04) + "user".toByteArray() +
                            byteArrayOf(0x06) + "secret".toByteArray(),
                    )
                    output.flush()
                    assertEquals(0x01, input.read())
                    assertEquals(0x00, input.read())

                    writeConnectRequest(output, "127.0.0.1", echo.port)
                    assertConnectReply(input)

                    output.write("ping".toByteArray())
                    output.flush()
                    val reply = ByteArray(4)
                    readFully(input, reply)
                    assertEquals("ping", String(reply))
                }
                listener.awaitStats { it.bytesUp >= 4 && it.bytesDown >= 4 }
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `reports connection refused`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        val listener = RecordingListener()
        val server = Socks5ProxyServer(ProxyConfig(port = 0), listener)
        server.start().getOrThrow()
        try {
            Socket("127.0.0.1", listener.listenPort!!).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                writeConnectRequest(output, "127.0.0.1", closedPort)
                assertEquals(0x05, input.read())
                assertEquals(0x05, input.read()) // connection refused
            }
            listener.awaitStats { it.failedConnections == 1L }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rejects unsupported commands`() = runBlocking<Unit> {
        val listener = RecordingListener()
        val server = Socks5ProxyServer(ProxyConfig(port = 0), listener)
        server.start().getOrThrow()
        try {
            Socket("127.0.0.1", listener.listenPort!!).use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                // UDP ASSOCIATE
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x07, input.read())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `binds several addresses on one port`() = runBlocking<Unit> {
        // Binding extra 127.x aliases needs a manual lo0 alias on macOS, so
        // this test only runs where the platform allows it (e.g. Linux).
        assumeTrue(canBind("127.0.0.2"))
        val listener = RecordingListener()
        val server = Socks5ProxyServer(
            ProxyConfig(bindAddresses = listOf("127.0.0.1", "127.0.0.2"), port = 0),
            listener,
        )
        server.start().getOrThrow()
        try {
            val port = listener.listenPort ?: fail("server did not start")
            assertEquals(listOf("127.0.0.1", "127.0.0.2"), listener.listenAddresses)

            // Both addresses accept SOCKS handshakes on the very same port.
            for (host in listOf("127.0.0.1", "127.0.0.2")) {
                Socket(host, port).use { client ->
                    client.soTimeout = 5_000
                    client.getOutputStream().apply {
                        write(byteArrayOf(0x05, 0x01, 0x00))
                        flush()
                    }
                    assertEquals(0x05, client.getInputStream().read())
                    assertEquals(0x00, client.getInputStream().read())
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `skips unavailable addresses and starts on the rest`() = runBlocking<Unit> {
        val listener = RecordingListener()
        val server = Socks5ProxyServer(
            // 192.0.2.x is TEST-NET-1: never assigned to an interface, so the
            // bind must fail while the loopback socket still starts.
            ProxyConfig(bindAddresses = listOf("192.0.2.123", "127.0.0.1"), port = 0),
            listener,
        )
        server.start().getOrThrow()
        try {
            val port = listener.listenPort ?: fail("server did not start")
            assertTrue("127.0.0.1" in listener.listenAddresses)
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 5_000
                client.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                assertEquals(0x05, client.getInputStream().read())
            }
        } finally {
            server.stop()
        }
    }

    private fun canBind(address: String): Boolean = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, 0), 1)
        }.use { true }
    } catch (_: IOException) {
        false
    }

    private fun writeConnectRequest(output: OutputStream, host: String, port: Int) {
        val address = host.split('.').map { it.toInt().toByte() }.toByteArray()
        output.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x01) + address +
                byteArrayOf((port ushr 8).toByte(), port.toByte()),
        )
        output.flush()
    }

    private fun assertConnectReply(input: InputStream) {
        assertEquals(0x05, input.read())
        assertEquals(0x00, input.read())
        assertEquals(0x00, input.read())
        when (val addressType = input.read()) {
            0x01 -> readFully(input, ByteArray(4))
            0x04 -> readFully(input, ByteArray(16))
            0x03 -> readFully(input, ByteArray(input.read()))
            else -> fail("unexpected address type $addressType")
        }
        readFully(input, ByteArray(2))
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) fail("unexpected end of stream")
            offset += read
        }
    }

    private class RecordingListener : ProxyListener {
        @Volatile
        var listenAddresses: List<String> = emptyList()

        @Volatile
        var listenPort: Int? = null

        @Volatile
        var lastStats: ProxyStats = ProxyStats()

        @Volatile
        var stopped: Boolean = false

        override fun onStarted(listenAddresses: List<String>, listenPort: Int) {
            this.listenAddresses = listenAddresses
            this.listenPort = listenPort
        }

        override fun onStats(stats: ProxyStats) {
            lastStats = stats
        }

        override fun onStopped(error: String?) {
            stopped = true
        }

        suspend fun awaitStats(
            timeoutMs: Long = 5_000,
            predicate: (ProxyStats) -> Boolean,
        ): ProxyStats {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val current = lastStats
                if (predicate(current)) return current
                delay(50)
            }
            fail("stats condition was not met, last stats: $lastStats")
        }
    }

    private class EchoServer : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

        val port: Int get() = serverSocket.localPort

        init {
            thread(isDaemon = true, name = "echo-accept") {
                while (!serverSocket.isClosed) {
                    val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true, name = "echo-client") { echo(client) }
                }
            }
        }

        private fun echo(client: Socket) {
            client.use { socket ->
                val buffer = ByteArray(4096)
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        }

        override fun close() {
            runCatching { serverSocket.close() }
        }
    }
}
