package io.github.siddharthjaswal.logpose.desktop

import java.io.File
import kotlin.system.exitProcess

/**
 * Writes a real `.logpose` (the M3 gate's recorded-and-shared session) for checking the packaged
 * app's Finder association (RELEASING.md → *Mac app* → *Verify a build*):
 *
 *   ./gradlew :desktop:sampleCapture -PsampleDir=/tmp/logpose-sample
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val result = M3Gate.run(File(out, "work"), out)
    File(out, "work").deleteRecursively()
    println(result.file.absolutePath)
    result.model.shutdown()
    exitProcess(0)
}
