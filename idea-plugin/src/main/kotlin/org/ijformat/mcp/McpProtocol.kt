package org.ijformat.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal object McpProtocol {
    private val gson = Gson()
    private val versions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
    private val pluginVersion by lazy {
        checkNotNull(McpProtocol::class.java.getResource("/META-INF/ij-format-version.txt")).readText().trim()
    }
    private val tool = JsonParser.parseString(
        """
        {
          "name": "ij_format_lines",
          "description": "Format selected lines or an exact range of complete Java or Kotlin source using the current global IDEA code style. Returns the complete text. No project or path is used. Fails if an edit would cross the selection.",
          "annotations": {"readOnlyHint": true, "destructiveHint": false, "openWorldHint": false},
          "inputSchema": {
            "type": "object",
            "properties": {
              "language": {"type": "string", "enum": ["java", "kotlin"]},
              "source": {"type": "string", "description": "Complete source text, up to 128 KiB of UTF-8."},
              "startLine": {"type": "integer", "minimum": 1, "description": "First line, inclusive and 1-based."},
              "endLine": {"type": "integer", "minimum": 1, "description": "Last line, inclusive and 1-based."},
              "range": {
                "type": "object",
                "description": "Exact range with an inclusive start and an exclusive end. Lines and columns are 1-based. Each Unicode code point, including a tab, counts as one column.",
                "properties": {
                  "startLine": {"type": "integer", "minimum": 1},
                  "startColumn": {"type": "integer", "minimum": 1},
                  "endLine": {"type": "integer", "minimum": 1},
                  "endColumn": {"type": "integer", "minimum": 1}
                },
                "required": ["startLine", "startColumn", "endLine", "endColumn"],
                "additionalProperties": false
              }
            },
            "required": ["language", "source"],
            "oneOf": [
              {"required": ["startLine", "endLine"], "not": {"required": ["range"]}},
              {"required": ["range"], "not": {"anyOf": [{"required": ["startLine"]}, {"required": ["endLine"]}]}}
            ],
            "additionalProperties": false
          }
        }
        """.trimIndent(),
    )

    data class Reply(val status: Int, val body: String = "")

    suspend fun handle(body: String, versionHeader: String?): Reply {
        val input = try {
            JsonParser.parseString(body)
        } catch (_: JsonParseException) {
            return error(JsonNull.INSTANCE, -32700, "Parse error", 400)
        }
        if (!input.isJsonObject) return error(JsonNull.INSTANCE, -32600, "Invalid request", 400)
        val request = input.asJsonObject
        val id = request.get("id")
        val method = request.string("method")
        if (request.string("jsonrpc") != "2.0" || method == null ||
            (id != null && !(id.isJsonPrimitive && (id.asJsonPrimitive.isNumber || id.asJsonPrimitive.isString)))) {
            return error(JsonNull.INSTANCE, -32600, "Invalid request", 400)
        }
        val params = request.get("params")
        if (params != null && !params.isJsonObject) {
            return if (id == null) Reply(202) else error(id, -32602, "Expected object parameters")
        }
        if (versionHeader != null && versionHeader !in versions) {
            return error(
                id ?: JsonNull.INSTANCE, -32022, "Unsupported protocol version", 400,
                mapOf("supported" to versions, "requested" to versionHeader),
            )
        }
        if (id == null) return Reply(202)
        when (method) {
            "initialize" -> {
                val requested = params?.asJsonObject?.string("protocolVersion")
                    ?: return error(id, -32602, "Expected protocolVersion")
                return result(id, mapOf(
                    "protocolVersion" to if (requested in versions) requested else versions.first(),
                    "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                    "serverInfo" to mapOf("name" to "ij-format", "version" to pluginVersion),
                ))
            }
            "ping" -> return result(id, emptyMap<String, Any>())
            "tools/list" -> return result(id, mapOf("tools" to listOf(tool)))
            "tools/call" -> {
                val call = params?.asJsonObject ?: return error(id, -32602, "Expected tool parameters")
                if (call.string("name") != "ij_format_lines") return error(id, -32602, "Unknown tool")
                val arguments = call.get("arguments")
                if (arguments == null || !arguments.isJsonObject) return error(id, -32602, "Expected arguments")
                val args = arguments.asJsonObject
                val exactRange = args.has("range")
                val endpoints = if (exactRange && args.keySet() == setOf("language", "source", "range")) {
                    args.get("range").takeIf { it.isJsonObject }?.asJsonObject
                        ?.takeIf { it.keySet() == setOf("startLine", "startColumn", "endLine", "endColumn") }
                        ?: return error(id, -32602, "Expected range with startLine, startColumn, endLine and endColumn")
                } else if (!exactRange && args.keySet() == setOf("language", "source", "startLine", "endLine")) {
                    args
                } else {
                    return error(id, -32602, "Expected language, source and either startLine/endLine or range")
                }
                val language = args.string("language") ?: return error(id, -32602, "Expected language string")
                val source = args.string("source") ?: return error(id, -32602, "Expected source string")
                val start = endpoints.integer("startLine") ?: return error(id, -32602, "Expected integer startLine")
                val end = endpoints.integer("endLine") ?: return error(id, -32602, "Expected integer endLine")
                val startColumn = if (exactRange) endpoints.integer("startColumn")
                    ?: return error(id, -32602, "Expected integer startColumn") else null
                val endColumn = if (exactRange) endpoints.integer("endColumn")
                    ?: return error(id, -32602, "Expected integer endColumn") else null
                val answer = try {
                    val formatted = withTimeout(15_000) {
                        IdeaFormatter.format(language, source, start, end, startColumn, endColumn)
                    }
                    toolResult(formatted, false)
                } catch (failure: IllegalArgumentException) {
                    toolResult(failure.message ?: "Invalid formatting request", true)
                } catch (_: TimeoutCancellationException) {
                    toolResult("Formatting timed out waiting for IDEA", true)
                }
                return result(id, answer)
            }
            else -> return error(id, -32601, "Method not found")
        }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.integer(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()

    private fun toolResult(text: String, failed: Boolean): Map<String, Any> =
        mapOf("content" to listOf(mapOf("type" to "text", "text" to text)), "isError" to failed)

    private fun result(id: JsonElement, value: Any): Reply =
        Reply(200, gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "result" to value)))

    private fun error(id: JsonElement, code: Int, message: String, status: Int = 200, data: Any? = null): Reply {
        val details = JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
            if (data != null) add("data", gson.toJsonTree(data))
        }
        // add(JsonNull) preserves the required null ID for malformed requests.
        val response = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", details)
        }
        return Reply(status, response.toString())
    }
}
