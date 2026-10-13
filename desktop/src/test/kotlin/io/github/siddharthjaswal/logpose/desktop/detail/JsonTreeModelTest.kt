package io.github.siddharthjaswal.logpose.desktop.detail

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonTreeModelTest {
    private val root = Json.parseToJsonElement(
        """{"a":1,"b":{"c":[true,null,"x"],"d":{"e":{"f":2}}},"body":"{\"items\":[1,2],\"n\":3}"}""",
    )

    @Test fun `collapsed root is one line with a count`() {
        val lines = JsonTreeModel.flatten(root, emptySet())
        assertEquals(1, lines.size)
        assertEquals(JsonTreeModel.Kind.OBJECT, lines[0].kind)
        assertEquals(3, lines[0].count)
    }

    @Test fun `default expansion opens depth two and the path to an embedded body`() {
        val lines = JsonTreeModel.flatten(root, JsonTreeModel.defaultExpanded(root))
        val keys = lines.map { it.key }
        assertTrue("c" in keys && "d" in keys)          // depth-2 children are visible
        assertFalse("e" in keys && lines.first { it.key == "e" }.expanded) // depth 3 stays folded
        val body = lines.first { it.key == "body" }
        assertTrue(body.embedded && body.expanded && body.kind == JsonTreeModel.Kind.OBJECT)
        assertTrue(lines.first { it.key == "items" }.expanded) // one level into the body
    }

    @Test fun `scalars are typed and strings quoted`() {
        val lines = JsonTreeModel.flatten(root, JsonTreeModel.allContainers(root))
        val kinds = lines.filter { it.depth == 3 && it.path.contains("c") }.map { it.kind }
        assertEquals(listOf(JsonTreeModel.Kind.BOOL, JsonTreeModel.Kind.NULL, JsonTreeModel.Kind.STRING), kinds)
        assertEquals("\"x\"", lines.first { it.kind == JsonTreeModel.Kind.STRING && it.key == "[2]" }.text)
    }

    @Test fun `a string that merely looks like JSON stays a string`() {
        assertEquals(Json.parseToJsonElement("\"{oops\""), JsonTreeModel.decodeEmbedded(Json.parseToJsonElement("\"{oops\"")))
        assertEquals(Json.parseToJsonElement("\"42\""), JsonTreeModel.decodeEmbedded(Json.parseToJsonElement("\"42\"")))
    }

    @Test fun `paths are unique even when keys contain dots`() {
        val tricky = Json.parseToJsonElement("""{"a.b":1,"a":{"b":2}}""")
        val paths = JsonTreeModel.flatten(tricky, JsonTreeModel.allContainers(tricky)).map { it.path }
        assertEquals(paths.size, paths.toSet().size)
    }
}
