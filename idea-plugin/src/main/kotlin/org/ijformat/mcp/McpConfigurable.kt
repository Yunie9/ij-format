package org.ijformat.mcp

import com.google.gson.GsonBuilder
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.dsl.builder.panel
import java.awt.datatransfer.StringSelection
import java.io.IOException
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.JToggleButton

internal data class McpConnection(val url: String, val headers: Map<String, String>) {
    fun clientJson(): String = GsonBuilder().setPrettyPrinting().create()
        .toJson(mapOf("mcpServers" to mapOf("ij-format" to this)))
}

@Service(Service.Level.APP)
@State(name = "IjFormatMcpSettings", storages = [Storage("ij-format-mcp.xml", roamingType = RoamingType.DISABLED)])
internal class McpSettings : SimplePersistentStateComponent<McpSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        var enabled by property(true)
        var minWorkers by property(WorkerLimits.DEFAULT.minWorkers)
        var maxWorkers by property(WorkerLimits.DEFAULT.maxWorkers)
        var requestCapacity by property(0)
        // Legacy profiles supply the initial in-flight limit and scale-up threshold.
        var queueCapacity by property(WorkerLimits.DEFAULT.requestCapacity)
        var httpQueueCapacity by property(0)
        var scaleThreshold by property(-1)
        var timeoutMs by property(WorkerLimits.DEFAULT.timeoutMs)

        fun workerLimits(): WorkerLimits {
            val httpCapacity = httpQueueCapacity.takeUnless { it == 0 } ?: queueCapacity
            return WorkerLimits(minWorkers, maxWorkers, requestCapacity.takeUnless { it == 0 } ?: queueCapacity,
                timeoutMs, scaleThreshold.takeUnless { it == -1 } ?: (httpCapacity / 4).coerceAtLeast(1))
        }

        fun setWorkerLimits(limits: WorkerLimits) {
            minWorkers = limits.minWorkers
            maxWorkers = limits.maxWorkers
            requestCapacity = limits.requestCapacity
            scaleThreshold = limits.scaleThreshold
            timeoutMs = limits.timeoutMs
        }
    }
}

internal data class WorkerLimits(
    val minWorkers: Int,
    val maxWorkers: Int,
    val requestCapacity: Int,
    val timeoutMs: Int,
    val scaleThreshold: Int,
) {
    fun validate() {
        require(minWorkers in WORKERS && maxWorkers in WORKERS) { "Worker counts must be between 1 and 32." }
        require(minWorkers <= maxWorkers) { "Minimum workers must be at most maximum workers." }
        require(requestCapacity in CAPACITY) { "Maximum in-flight requests must be between 1 and 256." }
        require(scaleThreshold in 0..256) { "Scale-up threshold must be between 0 and 256." }
        require(timeoutMs in TIMEOUTS) {
            "Timeouts must be between 1 and 120000 ms."
        }
    }

    val httpBufferCapacity: Int get() = maxOf(requestCapacity, scaleThreshold, maxWorkers)

    fun summary() = "Workers $minWorkers–$maxWorkers, scale-up $scaleThreshold, in-flight limit $requestCapacity, timeout $timeoutMs ms"

    companion object {
        private val WORKERS = 1..32
        private val CAPACITY = 1..256
        val TIMEOUTS = 1..120_000
        val DEFAULT = WorkerLimits(2, 8, 20, 15_000, 5)
    }
}

class McpConfigurable : Configurable {
    private var accessKey: JPasswordField? = null
    private var showAccessKey: JToggleButton? = null
    private var savedKey = ""
    private var scaleThreshold: JTextField? = null
    private var enabled: JCheckBox? = null
    private var status: JLabel? = null
    private var copy: JButton? = null
    private var copyPath: JButton? = null
    private var minWorkers: JTextField? = null
    private var maxWorkers: JTextField? = null
    private var requestCapacity: JTextField? = null
    private var timeoutMs: JTextField? = null
    private var activeLimits: JLabel? = null
    private val settings get() = service<McpSettings>().state
    private val server get() = service<LocalMcpServer>()

    override fun getDisplayName() = "IJ-format"

    override fun createComponent(): JComponent = panel {
        row {
            enabled = checkBox("Enable MCP server").component
        }
        row("Status:") {
            status = label("").component
        }
        row {
            copy = button("Copy MCP JSON") {
                server.connection?.let {
                    CopyPasteManager.getInstance().setContents(StringSelection(it.clientJson()))
                }
            }.component
        }.rowComment("The JSON contains a local access token. Keep it private.")
        row {
            copyPath = button("Copy CLI Config Path") {
                CopyPasteManager.getInstance().setContents(StringSelection(LocalMcpServer.connectionFile().toString()))
            }.component
        }.rowComment("Run ij-format status to check the connection. If several IDEA versions are detected, pass this path with --config.")
        row("MCP access key:") {
            val field = cell(JPasswordField(24)).component
            accessKey = field
            val mask = field.echoChar
            showAccessKey = cell(JToggleButton("Show key")).component.apply {
                addItemListener {
                    field.echoChar = if (isSelected) '\u0000' else mask
                    text = if (isSelected) "Hide key" else "Show key"
                }
            }
        }.rowComment("Use 16–256 letters, digits, underscores or hyphens. Clear and Apply to generate a new key.")
        group("HTTP workers") {
            row("Minimum workers:") {
                minWorkers = textField().component.apply { columns = 6 }
            }
            row("Maximum workers:") {
                maxWorkers = textField().component.apply { columns = 6 }
            }
            row("Scale-up threshold:") {
                scaleThreshold = textField().component.apply { columns = 6 }
            }.rowComment("Pending HTTP tasks that trigger adding workers.")
            row("Active limits:") {
                activeLimits = label("").component
            }
        }
        group("Request limits") {
            row("Maximum in-flight requests:") {
                requestCapacity = textField().component.apply { columns = 6 }
            }.rowComment("Includes reading, formatting and sending the response.")
            row("Default request timeout (ms):") {
                timeoutMs = textField().component.apply { columns = 6 }
            }.rowComment("Includes HTTP worker wait and formatting. Applies to new requests.")

        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val selected = enabled?.isSelected ?: return false
        val state = settings
        val limits = state.workerLimits()
        return enteredKey() != savedKey ||
            scaleThreshold?.text?.trim()?.toIntOrNull() != limits.scaleThreshold ||
            selected != state.enabled ||
            minWorkers?.text?.trim()?.toIntOrNull() != state.minWorkers ||
            maxWorkers?.text?.trim()?.toIntOrNull() != state.maxWorkers ||
            requestCapacity?.text?.trim()?.toIntOrNull() != limits.requestCapacity ||
            timeoutMs?.text?.trim()?.toIntOrNull() != state.timeoutMs
    }

    override fun apply() {
        val selected = enabled?.isSelected ?: return
        try {
            val limits = WorkerLimits(
                readInt(minWorkers, "Minimum workers"),
                readInt(maxWorkers, "Maximum workers"),
                readInt(requestCapacity, "Maximum in-flight requests"),
                readInt(timeoutMs, "Default request timeout"),
                readInt(scaleThreshold, "Scale-up threshold"),
            )
            server.configure(selected, limits, enteredKey().takeUnless { it == savedKey })
        } catch (error: IOException) {
            throw ConfigurationException(error.localizedMessage ?: "Could not start the MCP server", displayName)
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.localizedMessage ?: "Invalid MCP server configuration", displayName)
        }
        reset()
    }

    private fun enteredKey() = accessKey?.password?.concatToString().orEmpty()

    private fun readInt(field: JTextField?, label: String): Int =
        requireNotNull(field?.text?.trim()?.toIntOrNull()) { "$label must be a whole number." }

    override fun reset() {
        val state = settings
        val limits = state.workerLimits()
        savedKey = server.accessKey().orEmpty()
        accessKey?.text = savedKey
        showAccessKey?.isSelected = false
        scaleThreshold?.text = limits.scaleThreshold.toString()
        enabled?.isSelected = state.enabled
        minWorkers?.text = state.minWorkers.toString()
        maxWorkers?.text = state.maxWorkers.toString()
        requestCapacity?.text = limits.requestCapacity.toString()
        timeoutMs?.text = state.timeoutMs.toString()
        updateStatus()
    }

    override fun disposeUIResources() {
        accessKey = null
        showAccessKey = null
        savedKey = ""
        scaleThreshold = null
        enabled = null
        status = null
        copy = null
        copyPath = null
        minWorkers = null
        maxWorkers = null
        requestCapacity = null
        timeoutMs = null
        activeLimits = null
    }

    private fun updateStatus() {
        val connection = server.connection
        status?.text = when {
            connection != null -> "Running at ${connection.url}"
            settings.enabled -> "Not running. Check the IDE log for startup errors."
            else -> "Disabled"
        }
        copy?.isEnabled = connection != null
        copyPath?.isEnabled = connection != null
        activeLimits?.text = server.activeWorkerLimits?.summary() ?: "Server is stopped"
    }
}
