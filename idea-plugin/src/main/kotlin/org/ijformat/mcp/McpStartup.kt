package org.ijformat.mcp

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class WelcomeMcpStartup : AppLifecycleListener {
    override fun welcomeScreenDisplayed() {
        service<LocalMcpServer>().scheduleStartup()
    }
}

class ProjectMcpStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<LocalMcpServer>().scheduleStartup()
    }
}
