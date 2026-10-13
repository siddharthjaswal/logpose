package io.github.siddharthjaswal.logpose.desktop.correlation

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.Suggestion
import io.github.siddharthjaswal.logpose.settings.CorrelationSettings
import io.github.siddharthjaswal.logpose.settings.FileKeyValueStore
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VocabularyTest {

    @TempDir lateinit var tmp: File
    private val appSupport get() = File(tmp, "LogPose")

    @Test fun `a QA tester with no project keeps keys in app settings and scenarios in Application Support`() {
        val loc = Vocabulary.resolve(null, appSupport)
        assertFalse(loc.shared)
        assertNull(loc.correlationFile)
        assertEquals(File(appSupport, "scenarios"), loc.scenariosDir)
        val app = KeyValueStore.InMemory()
        assertSame(app, Vocabulary.correlationStore(loc, app))
        assertEquals(File(appSupport, "scenarios/x.json").path, loc.scenarioPath("x"))
    }

    @Test fun `a developer's project folder uses the files the IDE and the daemon share`() {
        val project = File(tmp, "gandalf").apply { mkdirs() }
        val loc = Vocabulary.resolve(project.path, appSupport)
        assertTrue(loc.shared)
        assertEquals(FileKeyValueStore.sharedCorrelation(project).let { File(project, ".logpose/correlation.properties") }, loc.correlationFile)
        assertEquals(File(project, ".logpose/scenarios"), loc.scenariosDir)
        assertEquals(".logpose/scenarios/x.json", loc.scenarioPath("x"))

        // What the IDE writes is what the app reads — and nothing is copied in from the app's settings.
        CorrelationSettings.setKeys(FileKeyValueStore.sharedCorrelation(project), listOf(CorrelationKey("order_id")))
        val app = KeyValueStore.InMemory().also { CorrelationSettings.setKeys(it, listOf(CorrelationKey("trip_id"))) }
        assertEquals(listOf("order_id"), CorrelationSettings.keys(Vocabulary.correlationStore(loc, app)).map { it.name })
    }

    @Test fun `a project folder that no longer exists falls back to this Mac`() {
        val loc = Vocabulary.resolve(File(tmp, "deleted-checkout").path, appSupport)
        assertFalse(loc.shared)
        assertEquals(File(appSupport, "scenarios"), loc.scenariosDir)
        assertFalse(Vocabulary.resolve("  ", appSupport).shared)
    }

    private val sugg = listOf(
        Suggestion("order_id", 31, 6, 4, 3, "OR-881"),
        Suggestion("trip_id", 9, 3, 2, 2, "T-77"),
    )

    @Test fun `a first open seeds suggestions unticked, with their evidence`() {
        val d = KeysDraft.open(emptyList(), sugg, firstTime = true)
        assertTrue(d.seeded)
        assertEquals(listOf("order_id", "trip_id"), d.rows.map { it.name })
        assertTrue(d.rows.none { it.enabled }, "inert until ticked")
        assertEquals("groups 31 events  ·  largest 6  ·  latest OR-881", d.rows.first().note)
        assertEquals(emptyList<CorrelationKey>(), d.result().filter { it.enabled })
    }

    @Test fun `a configured project is not re-seeded, but suggest brings them back without duplicates`() {
        val d = KeysDraft.open(listOf(CorrelationKey("orderId")), sugg, firstTime = false)
        assertFalse(d.seeded)
        assertEquals(listOf("orderId"), d.rows.map { it.name })
        assertEquals(listOf("orderId", "trip_id"), d.appendSuggestions().rows.map { it.name })
    }

    @Test fun `validation names the problem the plugin dialog names`() {
        assertEquals("Give every key a name, or remove the blank row.", KeysDraft.open(emptyList(), emptyList(), false).add().problem())
        val dup = KeysDraft(listOf(KeysDraft.Row("order_id", true, false, ""), KeysDraft.Row("orderId", true, false, "")), false, emptyList())
        assertTrue(dup.problem()!!.contains("already in the list"))
        val ok = dup.remove(1).edit(0) { it.copy(allowShort = true) }
        assertNull(ok.problem())
        assertEquals(listOf(CorrelationKey("order_id", enabled = true, allowShortValues = true)), ok.result())
    }
}
