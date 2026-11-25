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
import javax.swing.JTextField

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
        var queueCapacity by property(WorkerLimits.DEFAULT.queueCapacity)

        fun workerLimits() = WorkerLimits(minWorkers, maxWorkers, queueCapacity)

        fun setWorkerLimits(limits: WorkerLimits) {
            minWorkers = limits.minWorkers
            maxWorkers = limits.maxWorkers
            queueCapacity = limits.queueCapacity
        }
    }
}

internal data class WorkerLimits(val minWorkers: Int, val maxWorkers: Int, val queueCapacity: Int) {
    fun validate() {
        require(minWorkers in WORKERS && maxWorkers in WORKERS) { "Worker counts must be between 1 and 32." }
        require(minWorkers <= maxWorkers) { "Minimum workers must be at most maximum workers." }
        require(queueCapacity in QUEUE) { "Waiting queue capacity must be between 1 and 256." }
    }

    fun summary() = "Workers $minWorkers–$maxWorkers, waiting queue $queueCapacity"

    companion object {
        private val WORKERS = 1..32
        private val QUEUE = 1..256
        val DEFAULT = WorkerLimits(4, 4, 20)
    }
}

class McpConfigurable : Configurable {
    private var enabled: JCheckBox? = null
    private var status: JLabel? = null
    private var copy: JButton? = null
    private var copyPath: JButton? = null
    private var minWorkers: JTextField? = null
    private var maxWorkers: JTextField? = null
    private var queueCapacity: JTextField? = null
    private var activeLimits: JLabel? = null
    private val settings get() = service<McpSettings>().state
    private val server get() = service<LocalMcpServer>()

    override fun getDisplayName() = "ij-format"

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
        group("HTTP workers") {
            row("Minimum workers:") {
                minWorkers = textField().component.apply { columns = 6 }
            }
            row("Maximum workers:") {
                maxWorkers = textField().component.apply { columns = 6 }
            }.rowComment("Additional workers start when the waiting queue fills.")
            row("Waiting queue capacity:") {
                queueCapacity = textField().component.apply { columns = 6 }
            }.rowComment("Apply updates the running server. Already queued requests are kept when the limit is lowered.")
            row("Active limits:") {
                activeLimits = label("").component
            }
        }
    }.also { reset() }

    override fun isModified(): Boolean {
        val selected = enabled?.isSelected ?: return false
        val state = settings
        return selected != state.enabled ||
            minWorkers?.text?.trim()?.toIntOrNull() != state.minWorkers ||
            maxWorkers?.text?.trim()?.toIntOrNull() != state.maxWorkers ||
            queueCapacity?.text?.trim()?.toIntOrNull() != state.queueCapacity
    }

    override fun apply() {
        val selected = enabled?.isSelected ?: return
        try {
            val limits = WorkerLimits(
                readInt(minWorkers, "Minimum workers"),
                readInt(maxWorkers, "Maximum workers"),
                readInt(queueCapacity, "Waiting queue capacity"),
            )
            server.configure(selected, limits)
        } catch (error: IOException) {
            throw ConfigurationException(error.localizedMessage ?: "Could not start the MCP server", displayName)
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.localizedMessage ?: "Invalid MCP server configuration", displayName)
        }
        updateStatus()
    }

    private fun readInt(field: JTextField?, label: String): Int =
        requireNotNull(field?.text?.trim()?.toIntOrNull()) { "$label must be a whole number." }

    override fun reset() {
        val state = settings
        enabled?.isSelected = state.enabled
        minWorkers?.text = state.minWorkers.toString()
        maxWorkers?.text = state.maxWorkers.toString()
        queueCapacity?.text = state.queueCapacity.toString()
        updateStatus()
    }

    override fun disposeUIResources() {
        enabled = null
        status = null
        copy = null
        copyPath = null
        minWorkers = null
        maxWorkers = null
        queueCapacity = null
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
