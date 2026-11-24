package org.ijformat.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class LocalMcpServer(private val scope: CoroutineScope) : Disposable {
    private var server: HttpServer? = null
    @Volatile
    internal var connection: McpConnection? = null
        private set
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8)) { task ->
        Thread(task, "ij-format MCP HTTP").apply { isDaemon = true }
    }

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
        if (!workers.isShutdown && service<McpSettings>().state.enabled) start()
    }

    @Synchronized
    internal fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
        service<McpSettings>().state.enabled = enabled
    }

    private fun start() {
        if (server != null) return
        val port = System.getProperty("ijformat.mcp.port", "49273").toIntOrNull()
        require(port != null && port in 0..65535) { "Invalid ijformat.mcp.port" }
        val endpoint = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        try {
            val host = "127.0.0.1:${endpoint.address.port}"
            val url = "http://$host/mcp"
            val config = PathManager.getConfigDir().resolve("ij-format-mcp.json")
            val credentials = readOrCreateConnection(config, url)
            val authorization = credentials.headers.getValue("Authorization").toByteArray()
            endpoint.executor = workers
            endpoint.createContext("/mcp") { exchange ->
                exchange.use {
                    try {
                        handle(exchange, host, authorization)
                    } catch (_: IOException) {
                        // A caller can disconnect while formatting or receiving its answer.
                    } catch (error: RuntimeException) {
                        LOG.warn("ij-format MCP request failed", error)
                        exchange.respond(500)
                    }
                }
            }
            endpoint.start()
            server = endpoint
            connection = credentials
            LOG.info("ij-format MCP ready at $url; client configuration: $config")
        } catch (error: Exception) {
            endpoint.stop(0)
            throw error
        }
    }

    private fun stop() {
        server?.stop(0)
        server = null
        connection = null
    }

    @Synchronized
    override fun dispose() {
        stop()
        workers.shutdownNow()
    }

    private fun handle(exchange: HttpExchange, host: String, authorization: ByteArray) {
        if (exchange.requestURI.rawPath != "/mcp") return exchange.respond(404)
        val request = exchange.requestHeaders
        if (request["Host"] != listOf(host) || (request["Origin"]?.let { it != listOf("http://$host") } == true)) {
            return exchange.respond(403)
        }
        val presented = request["Authorization"]?.singleOrNull()?.toByteArray() ?: byteArrayOf()
        if (!MessageDigest.isEqual(presented, authorization)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            return exchange.respond(401)
        }
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            return exchange.respond(405)
        }
        val media = request.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!media.equals("application/json", ignoreCase = true)) return exchange.respond(415)
        val bytes = exchange.requestBody.readNBytes(MAX_HTTP_BODY + 1)
        if (bytes.size > MAX_HTTP_BODY) return exchange.respond(413)
        val body = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return exchange.respond(400)
        }
        val reply = runBlocking { McpProtocol.handle(body, request.getFirst("MCP-Protocol-Version")) }
        exchange.respond(reply.status, reply.body)
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

    private fun readOrCreateConnection(path: Path, url: String): McpConnection {
        val existing = if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
                JsonParser.parseString(Files.readString(path)).asJsonObject
                    .getAsJsonObject("headers").get("Authorization").asString
            }.getOrNull()
        } else {
            null
        }
        val authorization = existing?.takeIf { TOKEN_PATTERN.matches(it) }
            ?: "Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val connection = McpConnection(url, mapOf("Authorization" to authorization))
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
        private const val MAX_HTTP_BODY = 1024 * 1024
        private val TOKEN_PATTERN = Regex("Bearer [A-Za-z0-9_-]{43}")
        private val LOG = Logger.getInstance(LocalMcpServer::class.java)
    }
}
