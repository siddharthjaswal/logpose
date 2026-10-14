package io.github.siddharthjaswal.logpose.desktop

import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File

/**
 * Rasterizes the app icon's SVG (`packaging/macos/icon.svg`) into a macOS `.iconset` with every
 * size `iconutil` expects — 16, 32, 128, 256 and 512 pt at @1x and @2x — using Skia's SVG renderer,
 * which is already on the classpath through Compose. This Mac has no CLI SVG rasterizer (no
 * rsvg-convert / Inkscape / ImageMagick, and `qlmanage -t` renders at the SVG's intrinsic size), so
 * this is the one reproducible path. Then:
 *
 *   ./gradlew :desktop:renderIconset -PiconSvg=packaging/macos/icon.svg -PiconsetDir=/tmp/LogPose.iconset
 *   iconutil -c icns /tmp/LogPose.iconset -o packaging/macos/LogPose.icns
 */
fun main(args: Array<String>) {
    val svg = File(args[0])
    val out = File(args[1]).apply { deleteRecursively(); mkdirs() }
    val bytes = svg.readBytes()
    for (pt in listOf(16, 32, 128, 256, 512)) {
        for (scale in listOf(1, 2)) {
            val px = pt * scale
            val dom = SVGDOM(Data.makeFromBytes(bytes))
            // The SVG's own width/height win over the container size, so draw at its intrinsic
            // size (1024) and scale the canvas down to the target instead.
            val intrinsic = dom.root?.width?.value ?: 1024f
            dom.setContainerSize(intrinsic, intrinsic)
            val surface = Surface.makeRasterN32Premul(px, px)
            surface.canvas.scale(px / intrinsic, px / intrinsic)
            dom.render(surface.canvas)
            val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed at $px px")
            val name = if (scale == 1) "icon_${pt}x$pt.png" else "icon_${pt}x$pt@2x.png"
            File(out, name).writeBytes(png.bytes)
            println("$name  ${px}x$px")
        }
    }
}
