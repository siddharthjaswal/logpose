package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.wire.Envelope
import io.github.siddharthjaswal.logpose.wire.GenericEvent
import io.github.siddharthjaswal.logpose.wire.Section
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the self-describing event payload — the framework half of the wire. The emit path
 * itself needs `android.util.Log`, so these exercise the builder and its serialization, which
 * is what the plugin actually has to read.
 */
class EventBuilderTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun build(title: String, block: EventBuilder.() -> Unit): GenericEvent =
        EventBuilder(title).apply(block).build()

    @Test fun `sections and badges are collected in order`() {
        val event = build("UserDao.insert") {
            subtitle = "users (3 rows)"
            badge("DB", Tone.INFO)
            badge("14ms")
            code("SQL", "INSERT INTO users ...")
            kv("Params", mapOf("id" to "7"))
        }

        assertEquals("UserDao.insert", event.title)
        assertEquals("users (3 rows)", event.subtitle)
        assertEquals(listOf("DB", "14ms"), event.badges.map { it.text })
        assertEquals(Tone.INFO, event.badges[0].tone)
        assertEquals(Tone.MUTED, event.badges[1].tone) // default tone
        assertEquals(listOf(Section.TYPE_CODE, Section.TYPE_KV), event.sections.map { it.type })
    }

    @Test fun `json section keeps structure`() {
        val event = build("Sync") { json("Body", """{"a":{"b":1}}""") }

        val body = event.sections.single().body.jsonObject
        assertEquals(1, body["a"]!!.jsonObject["b"]!!.jsonPrimitive.int())
    }

    @Test fun `malformed json degrades to text instead of being dropped`() {
        // A logging call must never lose data or throw just because a payload isn't valid JSON.
        val event = build("Sync") { json("Body", "not json {") }

        val section = event.sections.single()
        assertEquals(Section.TYPE_JSON, section.type)
        assertEquals("not json {", section.body.jsonPrimitive.content)
    }

    @Test fun `took turns a point event into a span`() {
        val builder = EventBuilder("Job").apply { took(1_240) }
        assertEquals(builder.at + 1_240, builder.endedAt)
    }

    @Test fun `open leaves the span unclosed`() {
        val builder = EventBuilder("Job").apply { took(10); open() }
        assertNull(builder.endedAt)
    }

    @Test fun `event payload round-trips through an envelope`() {
        val event = build("WorkManager") { badge("JOB", Tone.WARN); text("Note", "retrying") }
        val envelope = Envelope(
            kind = Envelope.KIND_EVENT, id = "e1", at = 100, endedAt = 100,
            payload = json.encodeToJsonElement(event),
        )

        val wire = json.encodeToString(Envelope.serializer(), envelope)
        val back = json.decodeFromString(Envelope.serializer(), wire)
        val decoded = json.decodeFromJsonElement(GenericEvent.serializer(), back.payload)

        assertEquals(Envelope.KIND_EVENT, back.kind)
        assertEquals(1, back.v)
        assertEquals("WorkManager", decoded.title)
        assertEquals(Tone.WARN, decoded.badges.single().tone)
        assertTrue(decoded.sections.single().body.jsonPrimitive.content == "retrying")
    }

    private fun kotlinx.serialization.json.JsonPrimitive.int(): Int = content.toInt()
}
