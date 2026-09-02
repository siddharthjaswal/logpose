package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import io.github.siddharthjaswal.logpose.settings.KeyValueStore

/**
 * The IDE's answer to core's [KeyValueStore]: a thin pass-through to [PropertiesComponent].
 *
 * Every method delegates to the platform call the code used to make directly, so the storage
 * format, the keys and the "unset when it equals the default" behaviour are byte-for-byte what
 * they were before core stopped knowing about IntelliJ — an upgrade must not lose a saved MCP
 * token, mock rule set or correlation vocabulary.
 *
 * Two scopes, as before: [forProject] for anything that is a property of the codebase (mock
 * rules, MCP token, correlation keys), [application] for IDE-wide preferences.
 */
class IdeKeyValueStore private constructor(private val props: PropertiesComponent) : KeyValueStore {

    override fun get(key: String): String? = props.getValue(key)

    override fun set(key: String, value: String?) {
        if (value == null) props.unsetValue(key) else props.setValue(key, value)
    }

    override fun getInt(key: String, default: Int): Int = props.getInt(key, default)

    override fun setInt(key: String, value: Int, default: Int) = props.setValue(key, value, default)

    override fun getBoolean(key: String, default: Boolean): Boolean = props.getBoolean(key, default)

    override fun setBoolean(key: String, value: Boolean, default: Boolean) =
        props.setValue(key, value, default)

    companion object {
        fun forProject(project: Project) = IdeKeyValueStore(PropertiesComponent.getInstance(project))

        fun application() = IdeKeyValueStore(PropertiesComponent.getInstance())
    }
}
