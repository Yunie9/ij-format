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

internal data class McpConnection(val url: String, val headers: Map<String, String>) {
    fun clientJson(): String = GsonBuilder().setPrettyPrinting().create()
        .toJson(mapOf("mcpServers" to mapOf("ij-format" to this)))
}

@Service(Service.Level.APP)
@State(name = "IjFormatMcpSettings", storages = [Storage("ij-format-mcp.xml", roamingType = RoamingType.DISABLED)])
internal class McpSettings : SimplePersistentStateComponent<McpSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        var enabled by property(true)
    }
}

class McpConfigurable : Configurable {
    private var enabled: JCheckBox? = null
    private var status: JLabel? = null
    private var copy: JButton? = null
    private val settings get() = service<McpSettings>().state
    private val server get() = service<LocalMcpServer>()

    override fun getDisplayName() = "ij-format MCP"

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
    }.also { reset() }

    override fun isModified(): Boolean = enabled?.isSelected?.let { it != settings.enabled } ?: false

    override fun apply() {
        val selected = enabled?.isSelected ?: return
        try {
            server.setEnabled(selected)
        } catch (error: IOException) {
            throw ConfigurationException(error.localizedMessage ?: "Could not start the MCP server", displayName)
        } catch (error: IllegalArgumentException) {
            throw ConfigurationException(error.localizedMessage ?: "Invalid MCP server configuration", displayName)
        }
        updateStatus()
    }

    override fun reset() {
        enabled?.isSelected = settings.enabled
        updateStatus()
    }

    override fun disposeUIResources() {
        enabled = null
        status = null
        copy = null
    }

    private fun updateStatus() {
        val connection = server.connection
        status?.text = when {
            connection != null -> "Running at ${connection.url}"
            settings.enabled -> "Not running. Check the IDE log for startup errors."
            else -> "Disabled"
        }
        copy?.isEnabled = connection != null
    }
}
