package io.github.siddharthjaswal.logpose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Analytics params routinely carry PII (emails, phones, ids), and a capture gets pasted into
 * tickets and read by agents — so masking is the one analytics behaviour whose failure escapes the
 * debug session. These pin what's hidden and, just as important, what isn't (a too-eager default
 * that ate `screen_name` would make the events useless).
 */
class AnalyticsRedactionTest {

    private val masked = "██"

    @Test fun `masks PII-shaped param keys by default`() {
        val out = LogPose.maskParams(
            mapOf(
                "user_email" to "a@b.com",
                "phone_number" to "9990001234",
                "card_number" to "4111111111111111",
                "auth_token" to "abc",
            ),
            LogPoseConfig(),
        )
        out.forEach { (k, v) -> assertEquals("$k leaked", masked, v) }
    }

    @Test fun `leaves ordinary event params intact`() {
        // The whole point of analytics capture: reading these. A default that redacted them is a bug.
        val out = LogPose.maskParams(
            mapOf(
                "screen_name" to "cart",
                "product_name" to "Widget",
                "value" to "499",
                "currency" to "INR",
                "item_count" to "3",
            ),
            LogPoseConfig(),
        )
        assertEquals("cart", out["screen_name"])
        assertEquals("Widget", out["product_name"])
        assertEquals("499", out["value"])
        assertEquals("INR", out["currency"])
    }

    @Test fun `matching is case-insensitive`() {
        val out = LogPose.maskParams(mapOf("User_Email" to "a@b.com", "SECRET" to "x"), LogPoseConfig())
        assertEquals(masked, out["User_Email"])
        assertEquals(masked, out["SECRET"])
    }

    @Test fun `custom keys extend the defaults`() {
        // user_id isn't redacted by default (heavily used in debugging); opt in per schema.
        val cfg = LogPoseConfig(redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS + "user_id")
        val out = LogPose.maskParams(mapOf("user_id" to "u-42", "email" to "a@b.com"), cfg)
        assertEquals(masked, out["user_id"])
        assertEquals(masked, out["email"])
    }
}
