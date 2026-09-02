package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceCapabilityTest {

    private val plain = MockRule(id = "r", method = "GET", pathPattern = "/v1/x")

    @Test
    fun `version comparison handles the shapes a device actually reports`() {
        assertTrue(DeviceCapability.atLeast("1.7.0", "1.7.0"))
        assertTrue(DeviceCapability.atLeast("1.7.1", "1.7.0"))
        assertTrue(DeviceCapability.atLeast("1.10.0", "1.7.0"))
        assertTrue(DeviceCapability.atLeast("2.0", "1.7.0"))
        assertTrue(DeviceCapability.atLeast("v1.7.0", "1.7.0"))
        assertTrue(DeviceCapability.atLeast("1.7.0-SNAPSHOT", "1.7.0"))

        assertFalse(DeviceCapability.atLeast("1.6.9", "1.7.0"))
        assertFalse(DeviceCapability.atLeast("1.7", "1.7.1"))
        assertFalse(DeviceCapability.atLeast("0.9.9", "1.0.0"))
    }

    @Test
    fun `an unknown version supports nothing — gating fails closed`() {
        assertFalse(DeviceCapability.atLeast(null, "1.7.0"))
        assertFalse(DeviceCapability.atLeast("", "1.7.0"))
        assertFalse(DeviceCapability.atLeast("unknown", "1.7.0"))
        assertFalse(DeviceCapability.supports(null, DeviceFeature.RICH_MATCHERS))
    }

    @Test
    fun `a rule using only long-standing fields is pushable to any library`() {
        assertTrue(DeviceCapability.featuresUsedBy(plain).isEmpty())
        assertNull(DeviceCapability.requiredVersion(plain))
        assertTrue(DeviceCapability.canPush(plain, "1.1.0"))
        // Even to a device that hasn't said which version it is.
        assertTrue(DeviceCapability.canPush(plain, null))
    }

    @Test
    fun `patch rules are not gated — they predate this check`() {
        val patch = plain.copy(mode = MockRule.MODE_PATCH, body = "{}")
        assertTrue(DeviceCapability.featuresUsedBy(patch).isEmpty())
        assertTrue(DeviceCapability.canPush(patch, "1.2.1"))
    }

    @Test
    fun `every new matcher field needs 1_7_0`() {
        val byQuery = plain.copy(matchQuery = mapOf("debug" to "1"))
        val byHeader = plain.copy(matchHeaders = mapOf("X-Env" to MockRule.MATCH_ANY))
        val byBody = plain.copy(matchBodyContains = "force")

        for (rule in listOf(byQuery, byHeader, byBody)) {
            assertEquals(setOf(DeviceFeature.RICH_MATCHERS), DeviceCapability.featuresUsedBy(rule))
            assertEquals("1.7.0", DeviceCapability.requiredVersion(rule))
            assertFalse(DeviceCapability.canPush(rule, "1.6.0"), "should be withheld from 1.6.0")
            assertTrue(DeviceCapability.canPush(rule, "1.7.0"))
        }
        // An empty matcher map is no constraint at all, so it gates nothing.
        assertTrue(DeviceCapability.canPush(plain.copy(matchQuery = emptyMap()), "1.6.0"))
        assertTrue(DeviceCapability.canPush(plain.copy(matchBodyContains = ""), "1.6.0"))
    }

    @Test
    fun `sequential responses need 1_7_0`() {
        val stepped = plain.copy(responses = listOf(MockStep(status = 500), MockStep(status = 200)))
        assertEquals(setOf(DeviceFeature.SEQUENTIAL_RESPONSES), DeviceCapability.featuresUsedBy(stepped))
        assertFalse(DeviceCapability.canPush(stepped, "1.6.9"))
        assertTrue(DeviceCapability.canPush(stepped, "1.7.0"))
    }

    @Test
    fun `an old device gets the plain rules and none of the gated ones`() {
        val gated = listOf(
            plain.copy(id = "q", matchQuery = mapOf("debug" to "1")),
            plain.copy(id = "s", responses = listOf(MockStep(status = 500))),
        )
        val all = listOf(plain) + gated

        assertEquals(listOf("r"), all.filter { DeviceCapability.canPush(it, "1.6.0") }.map { it.id })
        assertEquals(listOf("r", "q", "s"), all.filter { DeviceCapability.canPush(it, "1.7.0") }.map { it.id })
    }
}
