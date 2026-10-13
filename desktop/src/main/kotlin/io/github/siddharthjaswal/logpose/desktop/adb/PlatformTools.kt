package io.github.siddharthjaswal.logpose.desktop.adb

import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * First-run download of Google's platform-tools for a Mac with no Android SDK (PRD §4).
 *
 * **Verified, not trusted.** The friendly `platform-tools-latest-darwin.zip` alias is not listed in
 * Google's repository manifest, so it has no checksum to verify against. Instead the app reads the
 * manifest (`repository2-3.xml`, the file Android Studio's SDK manager reads), takes the stable
 * channel's macOS platform-tools archive it names — e.g. `platform-tools_r37.0.1-darwin.zip` —
 * downloads *that*, and refuses it unless its size and SHA-1 match the manifest. Same bytes as the
 * alias in practice, but provably so.
 *
 * Parsing and checking are pure ([parseManifest], [sha1Hex], [verify], [safeEntryTarget]) and
 * unit-tested; [download] and [install] do blocking I/O and must run on `Dispatchers.IO`.
 */
object PlatformTools {

    const val REPOSITORY_BASE = "https://dl.google.com/android/repository/"
    const val MANIFEST_URL = REPOSITORY_BASE + "repository2-3.xml"

    /** The alias the consent screen names as the source — what a person would recognise. */
    const val LATEST_ALIAS_URL = REPOSITORY_BASE + "platform-tools-latest-darwin.zip"

    data class Archive(
        val fileName: String,
        val sizeBytes: Long,
        val sha1: String,
        val revision: String,
    ) {
        val url: String get() = REPOSITORY_BASE + fileName
    }

    /**
     * The stable (`channel-0`) macOS platform-tools archive in [xml], or null when the manifest has
     * none. When several platform-tools packages exist (preview channels), the stable one wins; an
     * archive with a `host-arch` must match [hostArch] (platform-tools ships one universal zip today,
     * with no arch, which matches any host).
     */
    fun parseManifest(xml: String, hostArch: String = currentArch()): Archive? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // A manifest is data from the network: no DTDs, no external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(xml.byteInputStream()) }.getOrNull() ?: return null
        val packages = doc.getElementsByTagName("remotePackage").elements()
            .filter { it.getAttribute("path") == "platform-tools" }
        val stable = packages.filter { pkg ->
            val channel = pkg.child("channelRef")?.getAttribute("ref")
            channel == null || channel == "channel-0"
        }
        for (pkg in stable) {
            val revision = pkg.child("revision")?.let { rev ->
                listOf("major", "minor", "micro").mapNotNull { rev.child(it)?.textContent?.trim() }.joinToString(".")
            }.orEmpty()
            val archives = pkg.child("archives")?.getElementsByTagName("archive")?.elements().orEmpty()
            for (archive in archives) {
                if (archive.child("host-os")?.textContent?.trim() != "macosx") continue
                val arch = archive.child("host-arch")?.textContent?.trim()
                if (arch != null && arch != hostArch) continue
                val complete = archive.child("complete") ?: continue
                val checksum = complete.child("checksum") ?: continue
                if (checksum.getAttribute("type").ifBlank { "sha1" } != "sha1") continue
                val url = complete.child("url")?.textContent?.trim().orEmpty()
                // A relative file name under the repository root, nothing else: the manifest is not
                // allowed to send us to another host or up the path.
                if (url.isEmpty() || url.contains('/') || url.contains("..")) continue
                return Archive(
                    fileName = url,
                    sizeBytes = complete.child("size")?.textContent?.trim()?.toLongOrNull() ?: -1,
                    sha1 = checksum.textContent.trim().lowercase(),
                    revision = revision,
                )
            }
        }
        return null
    }

    fun sha1Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Null when [file] is exactly what [archive] describes, else why not (shown to the user). */
    fun verify(file: File, archive: Archive): String? {
        if (archive.sizeBytes >= 0 && file.length() != archive.sizeBytes) {
            return "The download was ${file.length()} bytes; Google's manifest says ${archive.sizeBytes}."
        }
        val actual = file.inputStream().use(::sha1Hex)
        return if (actual.equals(archive.sha1, ignoreCase = true)) null
        else "Checksum mismatch: got $actual, Google's manifest says ${archive.sha1}."
    }

    /**
     * Where a zip entry may be written under [root], or null if the entry tries to escape it
     * (`../`, an absolute path) — a zip-slip guard, applied to every entry.
     */
    fun safeEntryTarget(root: File, entryName: String): File? {
        if (entryName.startsWith("/") || entryName.contains('\\')) return null
        val target = File(root, entryName).canonicalFile
        val base = root.canonicalFile
        return if (target.path == base.path || target.path.startsWith(base.path + File.separator)) target else null
    }

    // ---- I/O (Dispatchers.IO only) ----------------------------------------------------------------

    fun fetchManifest(): String = open(MANIFEST_URL).use { it.readBytes().decodeToString() }

    /** Streams [archive] into [dest], reporting progress in 0..1. */
    fun download(archive: Archive, dest: File, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        open(archive.url).use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (archive.sizeBytes > 0) onProgress((total.toFloat() / archive.sizeBytes).coerceAtMost(1f))
                }
            }
        }
    }

    /**
     * Unzips a verified archive into `<appSupport>/platform-tools`, swapping it in only once the
     * whole tree is written, and marks the binaries executable (zip entries carry no Unix mode
     * through `java.util.zip`). Returns the adb path.
     */
    fun install(zip: File, appSupport: File): File {
        appSupport.mkdirs()
        val staging = File(appSupport, "platform-tools.staging").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val target = safeEntryTarget(staging, entry.name)
                    ?: error("Refusing unsafe archive entry: ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
            }
        }
        val tree = File(staging, "platform-tools")
        val adb = File(tree, "adb")
        check(adb.isFile) { "The archive has no platform-tools/adb." }
        // Top-level files with no extension are the tools (adb, fastboot, etc1tool, …).
        tree.listFiles().orEmpty().filter { it.isFile && !it.name.contains('.') }.forEach { it.setExecutable(true, false) }
        val final = File(appSupport, "platform-tools")
        val old = File(appSupport, "platform-tools.old").apply { deleteRecursively() }
        if (final.exists()) final.renameTo(old)
        check(tree.renameTo(final)) { "Could not move platform-tools into place." }
        old.deleteRecursively()
        staging.deleteRecursively()
        return File(final, "adb")
    }

    private fun open(url: String): InputStream {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("$url answered HTTP $code")
        }
        return conn.inputStream
    }

    private fun currentArch(): String =
        if (System.getProperty("os.arch").orEmpty().contains("aarch64")) "aarch64" else "x64"

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }

    /** First direct child element named [name]. */
    private fun Element.child(name: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == name) return n
        }
        return null
    }
}
