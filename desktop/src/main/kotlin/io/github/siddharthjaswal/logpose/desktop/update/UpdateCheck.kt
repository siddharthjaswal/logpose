package io.github.siddharthjaswal.logpose.desktop.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * The update check — deliberately not Sparkle or Conveyor (docs/mac-app-prd.md, *Status*): once a
 * day at most, one anonymous GET to GitHub's public Releases API, and if a newer **Mac app** release
 * exists the status bar shows a quiet *Update available* link to its release page. Nothing
 * is downloaded or installed, and nothing about the user, their devices or their captures is sent
 * (the request carries only a User-Agent naming the app version, which GitHub's API requires).
 *
 * Mac app releases are the tags `desktop-v<semver>` ([TAG_PREFIX]) — distinct from the library's
 * `v1.7.x` JitPack tags and the plugin's `v*` release tags, which share this repository's release
 * list and are filtered out here.
 */
object UpdateCheck {

    const val TAG_PREFIX = "desktop-v"
    const val RELEASES_API = "https://api.github.com/repos/siddharthjaswal/logpose/releases?per_page=50"
    /** The only pages the *Download* link may open — a release page of this repository. */
    const val RELEASE_PAGE_PREFIX = "https://github.com/siddharthjaswal/logpose/releases/"

    /** Settings keys (the app's settings.properties). */
    const val ENABLED_KEY = "logpose.desktop.updates.enabled"
    const val LAST_CHECK_KEY = "logpose.desktop.updates.lastCheckMillis"
    /** The newest release seen at the last check, `tag url` — so the link survives the daily throttle. */
    const val AVAILABLE_KEY = "logpose.desktop.updates.available"

    const val INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    /** A `MAJOR.MINOR.PATCH[-pre]` version. Build metadata (`+…`) is ignored, as semver says. */
    data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String? = null) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            compareValues(major, other.major).let { if (it != 0) return it }
            compareValues(minor, other.minor).let { if (it != 0) return it }
            compareValues(patch, other.patch).let { if (it != 0) return it }
            // A pre-release sorts before its release: 1.1.0-beta.1 < 1.1.0.
            return when {
                pre == other.pre -> 0
                pre == null -> 1
                other.pre == null -> -1
                else -> comparePre(pre, other.pre)
            }
        }

        override fun toString(): String = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

        companion object {
            private val PATTERN = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

            fun parse(text: String): Version? {
                val m = PATTERN.matchEntire(text.trim()) ?: return null
                val (a, b, c) = m.destructured
                return Version(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null, m.groupValues[4].ifEmpty { null })
            }

            /** Semver §11: dot-separated identifiers; numeric ones compare numerically and sort first. */
            private fun comparePre(a: String, b: String): Int {
                val x = a.split('.')
                val y = b.split('.')
                for (i in 0 until minOf(x.size, y.size)) {
                    val p = x[i].toIntOrNull()
                    val q = y[i].toIntOrNull()
                    val c = when {
                        p != null && q != null -> p.compareTo(q)
                        p != null -> -1
                        q != null -> 1
                        else -> x[i].compareTo(y[i])
                    }
                    if (c != 0) return c
                }
                return x.size.compareTo(y.size)
            }
        }
    }

    data class Release(val tag: String, val version: Version, val url: String)

    /** The version in a Mac app tag, or null for any other tag (`v1.7.0`, `desktop-vX`, …). */
    fun versionOfTag(tag: String): Version? =
        if (tag.startsWith(TAG_PREFIX)) Version.parse(tag.removePrefix(TAG_PREFIX)) else null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The Mac app releases in a Releases API response: published (not draft), not marked
     * pre-release, a `desktop-v` tag with a parseable version, and a page under this repository.
     * Anything malformed yields an empty list, never an exception.
     */
    fun parseReleases(body: String): List<Release> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
            fun flag(k: String) = runCatching { o[k]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
            if (flag("draft") || flag("prerelease")) return@mapNotNull null
            val tag = str("tag_name") ?: return@mapNotNull null
            val version = versionOfTag(tag) ?: return@mapNotNull null
            val url = str("html_url")?.takeIf { it.startsWith(RELEASE_PAGE_PREFIX) } ?: return@mapNotNull null
            Release(tag, version, url)
        }
    }

    /** The highest release strictly newer than [current], or null (also null for a `dev` build). */
    fun newest(releases: List<Release>, current: String): Release? {
        val mine = Version.parse(current) ?: return null
        return releases.filter { it.version > mine }.maxByOrNull { it.version }
    }

    /** Whether a check is due: never checked, a day has passed, or the clock went backwards. */
    fun due(lastCheckMillis: Long?, nowMillis: Long): Boolean =
        lastCheckMillis == null || nowMillis - lastCheckMillis >= INTERVAL_MILLIS || nowMillis < lastCheckMillis

    fun encodeAvailable(r: Release): String = "${r.tag} ${r.url}"

    fun decodeAvailable(text: String?): Release? {
        val parts = text?.trim()?.split(' ')?.takeIf { it.size == 2 } ?: return null
        val version = versionOfTag(parts[0]) ?: return null
        return Release(parts[0], version, parts[1].takeIf { it.startsWith(RELEASE_PAGE_PREFIX) } ?: return null)
    }

    /** The real fetch: short timeouts, no cookies, no auth. Throws on anything but a 2xx. */
    fun fetch(currentVersion: String, url: String = RELEASES_API): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LogPose-for-Mac/$currentVersion")
            val code = conn.responseCode
            check(code in 200..299) { "GitHub answered HTTP $code" }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
