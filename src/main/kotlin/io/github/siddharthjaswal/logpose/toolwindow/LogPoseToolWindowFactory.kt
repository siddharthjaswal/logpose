package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints

class LogPoseToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Hand core the IDE's settings store before anything can read a mute. Endpoint mutes are
        // application-wide (one noisy heartbeat is noisy in every project), and :core keeps no
        // platform types of its own — see IdeKeyValueStore.
        MutedEndpoints.store = IdeKeyValueStore.application()

        // Show the installed plugin version as a dimmed suffix after "LogPose" in the header.
        PluginVersion.value?.let { toolWindow.setTitle("v$it") }

        val panel = LogPosePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

}
