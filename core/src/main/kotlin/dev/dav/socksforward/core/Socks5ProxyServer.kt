package dev.dav.socksforward.core

import dev.dav.socksforward.core.model.ConnectionInfo
import dev.dav.socksforward.core.model.ConnectionState
import dev.dav.socksforward.core.model.ProxyConfig
import dev.dav.socksforward.core.model.ProxyStats
import dev.dav.socksforward.core.socks.Socks5
import dev.dav.socksforward.core.socks.Socks5Handshake
import dev.dav.socksforward.core.socks.SocksException
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single-use SOCKS5 server: create, [start], later [stop] and throw away.
 *
 * Connections are handled with plain blocking sockets on a dedicated cached
 * thread pool. That is deliberately simple and predictable; the number of
 * threads is bounded by [ProxyConfig.maxConnections].
 */
class Socks5ProxyServer(
    private val config: ProxyConfig,
    private val listener: ProxyListener,
) {
    private val threadCounter = AtomicInteger()
    private val dispatcher = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "socks5-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val connections = ConcurrentHashMap<Long, Connection>()
    private val nextConnectionId = AtomicLong(1)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val totalConnections = AtomicLong(0)
    private val failedConnections = AtomicLong(0)
    private val startedAt = AtomicLong(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var stopping = false

    private var acceptJob: Job? = null
    private var statsJob: Job? = null

    fun start(): Result<Unit> {
        val socket = try {
            createServerSocket()
        } catch (error: Exception) {
            return Result.failure(error.toBindException())
        }

        serverSocket = socket
        startedAt.set(System.currentTimeMillis())
        listener.onStarted(displayAddress(socket.inetAddress), socket.localPort)
        listener.onStats(snapshot())

        acceptJob = scope.launch { acceptLoop(socket) }
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                listener.onStats(snapshot())
            }
        }
        return Result.success(Unit)
    }

    fun stop() {
        if (stopping) return
        stopping = true
        runCatching { serverSocket?.close() }
        connections.values.forEach { it.closeSockets() }
        acceptJob?.cancel()
        statsJob?.cancel()
        listener.onStopped(null)
        scope.cancel()
        runCatching { dispatcher.close() }
    }

    private fun createServerSocket(): ServerSocket {
        if (!config.isWildcard) {
            return ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(config.bindAddress), config.port), BACKLOG)
            }
        }
        // Wildcard: try a dual stack IPv6 socket first so LAN clients can use
        // either family, and fall back to plain IPv4 when IPv6 is unavailable.
        val dualStack = ServerSocket()
        try {
            dualStack.reuseAddress = true
            dualStack.bind(
                InetSocketAddress(InetAddress.getByName(ProxyConfig.WILDCARD_IPV6), config.port),
                BACKLOG,
            )
            return dualStack
        } catch (error: IOException) {
            runCatching { dualStack.close() }
            val ipv4 = ServerSocket()
            try {
                ipv4.reuseAddress = true
                ipv4.bind(
                    InetSocketAddress(InetAddress.getByName(ProxyConfig.WILDCARD_IPV4), config.port),
                    BACKLOG,
                )
                return ipv4
            } catch (fallbackError: IOException) {
                runCatching { ipv4.close() }
                throw fallbackError
            }
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (currentCoroutineContext().isActive && !stopping) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                if (stopping) break
                delay(ACCEPT_BACKOFF_MS)
                continue
            } catch (_: IOException) {
                if (stopping) break
                delay(ACCEPT_BACKOFF_MS)
                continue
            }

            if (connections.size >= config.maxConnections) {
                failedConnections.incrementAndGet()
                client.closeQuietly()
                continue
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val id = nextConnectionId.getAndIncrement()
        val connection = Connection(
            id = id,
            clientAddress = describe(client.remoteSocketAddress),
            startedAt = System.currentTimeMillis(),
        )
        connection.clientSocket = client
        connections[id] = connection
        totalConnections.incrementAndGet()

        var target: Socket? = null
        var handshake: Socks5Handshake? = null
        var replied = false
        try {
            client.tcpNoDelay = true
            client.soTimeout = config.handshakeTimeoutMs

            handshake = Socks5Handshake(client.getInputStream(), client.getOutputStream())
            handshake.negotiate(config.auth)

            val request = handshake.readRequest()
            connection.target = request.display

            val remote = InetSocketAddress(request.address ?: InetAddress.getByName(request.host), request.port)
            target = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(remote, config.connectTimeoutMs)
            }
            connection.targetSocket = target

            handshake.sendReply(Socks5.REP_SUCCESS, target.localAddress, target.localPort)
            replied = true
            connection.state = ConnectionState.ACTIVE

            client.soTimeout = config.idleTimeoutMs
            target.soTimeout = config.idleTimeoutMs

            pumpBoth(client, target, connection)
        } catch (error: SocksException) {
            fail(connection, error.message ?: "Ошибка протокола")
            if (!error.replySent) handshake?.let { runCatching { it.sendFailure(error.replyCode) } }
        } catch (_: EOFException) {
            fail(connection, "Клиент закрыл соединение")
        } catch (error: SocketTimeoutException) {
            fail(connection, "Таймаут")
            if (!replied) handshake?.let { runCatching { it.sendFailure(Socks5.REP_HOST_UNREACHABLE) } }
        } catch (error: ConnectException) {
            fail(connection, "Соединение отклонено")
            if (!replied) handshake?.let { runCatching { it.sendFailure(Socks5.REP_CONNECTION_REFUSED) } }
        } catch (error: NoRouteToHostException) {
            fail(connection, "Нет маршрута до узла")
            if (!replied) handshake?.let { runCatching { it.sendFailure(Socks5.REP_HOST_UNREACHABLE) } }
        } catch (error: UnknownHostException) {
            fail(connection, "Не удалось разрешить имя хоста")
            if (!replied) handshake?.let { runCatching { it.sendFailure(Socks5.REP_HOST_UNREACHABLE) } }
        } catch (error: IOException) {
            fail(connection, error.message ?: "Ошибка ввода-вывода")
        } finally {
            target.closeQuietly()
            client.closeQuietly()
            connections.remove(id)
        }
    }

    private suspend fun pumpBoth(client: Socket, target: Socket, connection: Connection) {
        val closeBoth = {
            client.closeQuietly()
            target.closeQuietly()
        }
        coroutineScope {
            launch {
                pump(
                    source = client.getInputStream(),
                    sink = target.getOutputStream(),
                    onBytes = { count ->
                        connection.bytesUp.addAndGet(count.toLong())
                        bytesUp.addAndGet(count.toLong())
                    },
                    onHalfClose = { target.shutdownOutputQuietly() },
                    onFailure = closeBoth,
                )
            }
            launch {
                pump(
                    source = target.getInputStream(),
                    sink = client.getOutputStream(),
                    onBytes = { count ->
                        connection.bytesDown.addAndGet(count.toLong())
                        bytesDown.addAndGet(count.toLong())
                    },
                    onHalfClose = { client.shutdownOutputQuietly() },
                    onFailure = closeBoth,
                )
            }
        }
    }

    private fun pump(
        source: InputStream,
        sink: OutputStream,
        onBytes: (Int) -> Unit,
        onHalfClose: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var failed = false
        try {
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                onBytes(read)
            }
        } catch (_: IOException) {
            failed = true
        } finally {
            if (failed) onFailure() else onHalfClose()
        }
    }

    private fun fail(connection: Connection, reason: String) {
        connection.state = ConnectionState.FAILED
        connection.error = reason
        failedConnections.incrementAndGet()
    }

    private fun snapshot(): ProxyStats = ProxyStats(
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        totalConnections = totalConnections.get(),
        failedConnections = failedConnections.get(),
        startedAt = startedAt.get(),
        connections = connections.values.map { it.toInfo() }.sortedBy { it.id },
    )

    private fun displayAddress(address: InetAddress?): String = when {
        address == null -> config.bindAddress
        address.isAnyLocalAddress -> ProxyConfig.WILDCARD_IPV4
        else -> address.hostAddress ?: config.bindAddress
    }

    private fun describe(address: SocketAddress?): String = when (address) {
        is InetSocketAddress -> {
            val host = address.address?.hostAddress ?: address.hostString
            if (host.contains(':')) "[$host]:${address.port}" else "$host:${address.port}"
        }

        null -> "unknown"
        else -> address.toString()
    }

    private fun Exception.toBindException(): ProxyBindException {
        val raw = message?.takeIf { it.isNotBlank() }
        val text = when {
            this is BindException && raw != null && raw.contains("in use", ignoreCase = true) ->
                "Порт ${config.port} уже занят другим приложением"

            this is BindException && raw != null && raw.contains("denied", ignoreCase = true) ->
                "Нет прав на порт ${config.port}. Порты ниже 1024 требуют root"

            this is BindException -> "Не удалось занять порт ${config.port}: ${raw ?: "ошибка"}"

            else -> "Не удалось запустить сервер: ${raw ?: javaClass.simpleName}"
        }
        return ProxyBindException(text, this)
    }

    private class Connection(
        val id: Long,
        val clientAddress: String,
        val startedAt: Long,
    ) {
        @Volatile
        var target: String = ""

        @Volatile
        var state: ConnectionState = ConnectionState.CONNECTING

        @Volatile
        var error: String? = null

        @Volatile
        var clientSocket: Socket? = null

        @Volatile
        var targetSocket: Socket? = null

        val bytesUp = AtomicLong(0)
        val bytesDown = AtomicLong(0)

        fun toInfo(): ConnectionInfo = ConnectionInfo(
            id = id,
            clientAddress = clientAddress,
            target = target,
            state = state,
            startedAt = startedAt,
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            error = error,
        )

        fun closeSockets() {
            clientSocket.closeQuietly()
            targetSocket.closeQuietly()
        }
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER_SIZE = 16 * 1024
        const val STATS_INTERVAL_MS = 500L
        const val ACCEPT_BACKOFF_MS = 100L
    }
}

private fun Socket?.closeQuietly() {
    runCatching { this?.close() }
}

private fun Socket.shutdownOutputQuietly() {
    runCatching { shutdownOutput() }
}
