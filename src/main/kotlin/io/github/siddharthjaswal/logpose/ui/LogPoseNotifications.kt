package io.github.siddharthjaswal.logpose.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * The plugin's one path for telling the developer something went wrong *outside* the thing they
 * are looking at — a rule set that never reached the device, a push nothing answered.
 *
 * Deliberately not the detail pane: `TransactionDetailView.showError` replaces whatever
 * transaction is selected, so an adb hiccup used to blow away the response someone was reading.
 * Notifications sit beside the work instead, and the IDE keeps them in the event log.
 */
object LogPoseNotifications {

    /** Must match the `notificationGroup` id registered in `META-INF/plugin.xml`. */
    private const val GROUP_ID = "LogPose"

    fun warn(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.WARNING)

    fun info(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.INFORMATION)

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
