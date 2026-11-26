package org.ijformat.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.etp.ElasticThreadPoolExecutor
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class LocalMcpServer(private val scope: CoroutineScope) : Disposable {
    private class RunningServer(
        val endpoints: List<HttpServer>,
        val workers: ElasticThreadPoolExecutor,
        val requests: CoroutineScope,
        private val queue: ResizableTaskQueue,
        initialLimits: WorkerLimits,
        initialConnection: McpConnection,
    ) {
        val formatting = FormattingQueue(requests)
        private var inFlight = 0

        @Volatile
        var connection = initialConnection

        @Volatile
        var limits = initialLimits
            private set

        @Synchronized
        fun admit(): WorkerLimits? {
            if (inFlight >= limits.requestCapacity) return null
            inFlight++
            return limits
        }

        @Synchronized
        fun release() {
            inFlight--
        }

        @Synchronized
        fun updateLimits(next: WorkerLimits) {
            if (next == limits) return
            queue.resize(next.httpBufferCapacity)
            workers.scaleThreshold = next.scaleThreshold
            // The JDK requires corePoolSize <= maximumPoolSize at each setter.
            if (next.maxWorkers > workers.maximumPoolSize) {
                workers.maximumPoolSize = next.maxWorkers
                workers.corePoolSize = next.minWorkers
            } else {
                workers.corePoolSize = next.minWorkers
                workers.maximumPoolSize = next.maxWorkers
            }
            limits = next
        }
    }

    @Volatile
    private var running: RunningServer? = null
    private var disposed = false
    private val dispatchedAt = ThreadLocal<Long>()
    internal val connection: McpConnection? get() = running?.connection
    internal val activeWorkerLimits: WorkerLimits? get() = running?.limits

    internal fun scheduleStartup() {
        scope.launch(Dispatchers.IO) {
            try {
                startIfEnabled()
            } catch (error: IOException) {
                LOG.warn("Cannot start ij-format MCP HTTP service", error)
            } catch (error: IllegalArgumentException) {
                LOG.warn("Invalid ij-format MCP HTTP configuration", error)
            }
        }
    }

    @Synchronized
    private fun startIfEnabled() {
        val settings = service<McpSettings>().state
        if (!disposed && settings.enabled) start(settings.workerLimits())
    }

    @Synchronized
    internal fun configure(enabled: Boolean, limits: WorkerLimits, keyChange: String?) {
        check(!disposed)
        limits.validate()
        require(keyChange.isNullOrEmpty() || KEY_PATTERN.matches(keyChange)) {
            "MCP access key must contain 16–256 letters, digits, underscores or hyphens."
        }
        val active = running
        if (enabled && active == null) {
            start(limits, keyChange)
        } else {
            if (keyChange != null) {
                val url = active?.connection?.url ?: "http://127.0.0.1:${configuredPort()}/mcp"
                val updated = saveConnection(url, keyChange)
                if (active != null) active.connection = updated
            }
            if (enabled) active?.updateLimits(limits) else stop()
        }
        val settings = service<McpSettings>().state
        settings.setWorkerLimits(limits)
        settings.enabled = enabled
    }

    private fun configuredPort(): Int {
        val port = System.getProperty("ijformat.mcp.port", "49273").toIntOrNull()
        require(port != null && port in 0..65535) { "Invalid ijformat.mcp.port" }
        return port
    }

    private fun start(limits: WorkerLimits, keyChange: String? = null) {
        if (running != null) return
        limits.validate()
        val port = configuredPort()
        val containerAddress = containerAddress()
        val queue = ResizableTaskQueue(limits.httpBufferCapacity)
        val workers = ElasticThreadPoolExecutor(
            limits.minWorkers, limits.maxWorkers, 30, TimeUnit.SECONDS,
            limits.scaleThreshold, queue,
        ) { task -> Thread(task, "ij-format MCP HTTP").apply { isDaemon = true } }
        val requests = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        val endpoints = mutableListOf<HttpServer>()
        try {
            val endpoint = HttpServer.create(InetSocketAddress(LOOPBACK, port), 8)
            endpoints.add(endpoint)
            val actualPort = endpoint.address.port
            val host = "${LOOPBACK.hostAddress}:$actualPort"
            val containerHost = "host.docker.internal:$actualPort"
            val url = "http://$host/mcp"
            val config = connectionFile()
            val credentials = saveConnection(url, keyChange ?: accessKey())
            val active = RunningServer(endpoints, workers, requests, queue, limits, credentials)
            val localHosts = if (containerAddress == LOOPBACK) setOf(host, containerHost) else setOf(host)
            configureEndpoint(endpoint, localHosts, active)
            if (containerAddress != null && containerAddress != LOOPBACK) {
                val container = HttpServer.create(InetSocketAddress(containerAddress, actualPort), 8)
                endpoints.add(container)
                configureEndpoint(container, setOf(containerHost), active)
            }
            endpoints.forEach { it.start() }
            running = active
            LOG.info("ij-format MCP ready at $url; ${limits.summary()}; client configuration: $config")
        } catch (error: Exception) {
            close(endpoints, requests, workers)
            throw error
        }
    }

    private fun containerAddress(): InetAddress? {
        val value = System.getProperty("ijformat.mcp.container.bind") ?: return null
        require(value.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
            "ijformat.mcp.container.bind must be a private IPv4 address or 127.0.0.1"
        }
        val address = InetAddress.getByName(value)
        require(address is Inet4Address && (address.isSiteLocalAddress || address == LOOPBACK)) {
            "ijformat.mcp.container.bind must be a private IPv4 address or 127.0.0.1"
        }
        return address
    }

    private fun configureEndpoint(
        endpoint: HttpServer,
        hosts: Set<String>,
        active: RunningServer,
    ) {
        endpoint.executor = java.util.concurrent.Executor { command ->
            val received = System.nanoTime()
            active.workers.execute {
                dispatchedAt.set(received)
                try {
                    command.run()
                } finally {
                    dispatchedAt.remove()
                }
            }
        }
        endpoint.createContext("/mcp") { exchange ->
            try {
                handle(exchange, hosts, active)
            } catch (_: IOException) {
                exchange.close()
            } catch (error: RuntimeException) {
                LOG.warn("ij-format MCP request failed", error)
                exchange.reject(500)
            }
        }
    }

    private fun stop() {
        val active = running ?: return
        running = null
        close(active.endpoints, active.requests, active.workers)
    }

    private fun close(endpoints: List<HttpServer>, requests: CoroutineScope, workers: ElasticThreadPoolExecutor) {
        requests.cancel()
        try {
            endpoints.forEach { it.stop(0) }
        } finally {
            workers.shutdownNow()
        }
    }

    @Synchronized
    override fun dispose() {
        disposed = true
        stop()
    }

    private fun handle(exchange: HttpExchange, hosts: Set<String>, active: RunningServer) {
        if (exchange.requestURI.rawPath != "/mcp") return exchange.reject(404)
        val request = exchange.requestHeaders
        val host = request["Host"]?.singleOrNull()
        if (host !in hosts || (request["Origin"]?.let { it != listOf("http://$host") } == true)) {
            return exchange.reject(403)
        }
        val presented = request["Authorization"]?.singleOrNull()?.toByteArray() ?: byteArrayOf()
        val authorization = active.connection.headers.getValue("Authorization").toByteArray()
        if (!MessageDigest.isEqual(presented, authorization)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            return exchange.reject(401)
        }
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            return exchange.reject(405)
        }
        val media = request.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!media.equals("application/json", ignoreCase = true)) return exchange.reject(415)
        val limits = active.admit() ?: return exchange.reject(503)
        val received = dispatchedAt.get() ?: System.nanoTime()
        val deadline = received + TimeUnit.MILLISECONDS.toNanos(limits.timeoutMs.toLong())
        active.requests.launch {
            try {
                val body = readRequest(exchange, active.requests, deadline) ?: return@launch
                val reply = McpProtocol.handle(body, request.getFirst("MCP-Protocol-Version"), active.formatting,
                    received, limits.timeoutMs)
                exchange.respond(reply.status, reply.body)
            } catch (_: IOException) {
                // The client may have closed its connection before the response.
            } catch (error: RuntimeException) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                LOG.warn("ij-format MCP request failed", error)
                runCatching { exchange.respond(500) }
            }
        }.invokeOnCompletion {
            try {
                exchange.close()
            } finally {
                active.release()
            }
        }
    }

    private fun readRequest(exchange: HttpExchange, scope: CoroutineScope, deadline: Long): String? {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
            exchange.respond(408)
            return null
        }
        val watchdog = scope.launch(Dispatchers.Default) {
            delay(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
            exchange.close()
        }
        val bytes = try {
            exchange.requestBody.readNBytes(MAX_HTTP_BODY + 1)
        } finally {
            watchdog.cancel()
        }
        if (deadline - System.nanoTime() <= 0) {
            exchange.respond(408)
            return null
        }
        if (bytes.size > MAX_HTTP_BODY) {
            exchange.respond(413)
            return null
        }
        return try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            exchange.respond(400)
            null
        }
    }

    private fun HttpExchange.reject(status: Int) {
        use { respond(status) }
    }

    private fun HttpExchange.respond(status: Int, text: String = "") {
        responseHeaders.set("Cache-Control", "no-store")
        if (text.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            val bytes = text.toByteArray()
            responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.write(bytes)
        }
    }

    internal fun accessKey(): String? {
        val path = connectionFile()
        val existing = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                JsonParser.parseString(Files.readString(path)).asJsonObject
                    .getAsJsonObject("headers").get("Authorization").asString
            }.getOrNull()
        } else {
            null
        }
        return existing?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")?.takeIf { KEY_PATTERN.matches(it) }
    }

    private fun saveConnection(url: String, key: String?): McpConnection {
        val path = connectionFile()
        val token = key?.takeIf { it.isNotEmpty() }
            ?: Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val connection = McpConnection(url, mapOf("Authorization" to "Bearer $token"))
        val config = GsonBuilder().setPrettyPrinting().create().toJson(connection)
        val temporary = if (Files.getFileStore(path.parent).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            Files.createTempFile(path.parent, ".ij-format-", ".json", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------"),
            ))
        } else {
            Files.createTempFile(path.parent, ".ij-format-", ".json")
        }
        try {
            Files.writeString(temporary, config + "\n")
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return connection
    }

    companion object {
        internal fun connectionFile(): Path = PathManager.getConfigDir().resolve("ij-format-mcp.json")

        private val LOOPBACK = InetAddress.getByName("127.0.0.1")
        private const val MAX_HTTP_BODY = 1024 * 1024
        private val KEY_PATTERN = Regex("[A-Za-z0-9_-]{16,256}")
        private val LOG = Logger.getInstance(LocalMcpServer::class.java)
    }
}
