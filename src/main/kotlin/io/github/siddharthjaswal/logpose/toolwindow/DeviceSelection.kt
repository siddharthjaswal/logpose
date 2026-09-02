package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * Where this project's device selection is *stored*, persisted per project (the style
 * [io.github.siddharthjaswal.logpose.mcp.McpSessions] uses for its token).
 *
 * The decision itself — what to do with a saved serial that is no longer attached, when to speak
 * up — is [io.github.siddharthjaswal.logpose.logcat.DeviceChoice] in core, so a headless capture
 * makes it identically. Nothing here touches adb, so it's safe from any thread.
 */
object DeviceSelection {

    private const val KEY = "logpose.device.serial"

    fun serial(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(KEY)?.takeIf { it.isNotBlank() }

    fun setSerial(project: Project, serial: String?) {
        val props = PropertiesComponent.getInstance(project)
        if (serial.isNullOrBlank()) props.unsetValue(KEY) else props.setValue(KEY, serial)
    }
}
