package io.github.siddharthjaswal.logpose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Analytics param masking is **off by default** — params are usually staging/test data, and
 * reading them is the point. These pin that default, and that the opt-in still works for the rare
 * build that carries real PII.
 */
class AnalyticsRedactionTest {

    private val masked = "██"

    @Test fun `captures params as-is by default (no masking)`() {
        val params = mapOf("user_email" to "a@b.com", "value" to "499", "screen_name" to "cart")
        val out = LogPose.maskParams(params, LogPoseConfig())
        assertEquals(params, out)
    }

    @Test fun `opting in masks PII-shaped keys and leaves the rest`() {
        val cfg = LogPoseConfig(redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS)
        val out = LogPose.maskParams(
            mapOf(
                "user_email" to "a@b.com",
                "card_number" to "4111111111111111",
                "screen_name" to "cart",   // not PII — must survive
                "value" to "499",
            ),
            cfg,
        )
        assertEquals(masked, out["user_email"])
        assertEquals(masked, out["card_number"])
        assertEquals("cart", out["screen_name"])
        assertEquals("499", out["value"])
    }

    @Test fun `opt-in matching is case-insensitive and extensible`() {
        val cfg = LogPoseConfig(redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS + "user_id")
        val out = LogPose.maskParams(mapOf("User_Email" to "a@b.com", "user_id" to "u-42"), cfg)
        assertEquals(masked, out["User_Email"])
        assertEquals(masked, out["user_id"])
    }
}
