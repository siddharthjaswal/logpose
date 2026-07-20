package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class LogPoseToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Show the installed plugin version as a dimmed suffix after "LogPose" in the header.
        pluginVersion()?.let { toolWindow.setTitle("v$it") }

        val panel = LogPosePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    // PluginManager (not the internal PluginManagerCore) is the public way to read our own
    // descriptor — the Marketplace verifier flags PluginManagerCore as internal API.
    private fun pluginVersion(): String? =
        PluginManager.getInstance().findEnabledPlugin(PluginId.getId("io.github.siddharthjaswal.logpose"))?.version
}
