package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.app.DoctorResult
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput
import io.github.siddharthjaswal.logpose.desktop.wireless.BonjourBrowser
import io.github.siddharthjaswal.logpose.desktop.wireless.DnsSdSession
import io.github.siddharthjaswal.logpose.desktop.wireless.DoctorContext
import io.github.siddharthjaswal.logpose.desktop.wireless.DoctorProbe
import io.github.siddharthjaswal.logpose.desktop.wireless.PairingFlow
import io.github.siddharthjaswal.logpose.desktop.wireless.Reconnector
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.logcat.Adb

/**
 * Live wireless check against a phone that is already paired and connected over Wi-Fi — no QR scan:
 *
 *  1. Bonjour (dns-sd) browses `_adb-tls-connect._tcp`, finds the phone's guid and resolves an IPv4;
 *  2. with `-Preconnect=true`: `adb disconnect`s the phone, reconnects it through the Bonjour-direct
 *     path with adb's discovery deliberately ignored, then restores the original transport;
 *  3. runs the connection doctor end to end and prints its checks and the diagnostics report;
 *  4. proves no dns-sd process is left behind.
 *
 *   ./gradlew :desktop:wirelessLive [-Preconnect=true]
 */
fun main(args: Array<String>) {
    val reconnect = args.getOrNull(0) == "true"
    AdbLocator.locate()?.let(AdbLocator::install)
    val adb = AdbCommands.Real
    val phone = adb.devices().firstOrNull { it.ready && it.serial.contains("._adb-tls-connect._tcp") }
        ?: error("No phone connected over Wi-Fi (adb devices shows no ._adb-tls-connect._tcp transport)")
    val guid = PairingFlow.guidOf(phone.serial)!!
    println("phone: ${phone.serial} model=${phone.model}  guid=$guid")
    println("adb mdns services:\n" + adb.run(listOf("mdns", "services"), 5)?.trimEnd()?.prependIndent("  "))

    // 1. Bonjour-direct discovery.
    val browser = BonjourBrowser.shared
    val t0 = System.currentTimeMillis()
    val session = DnsSdSession(browser, setOf(AdbOutput.ServiceType.CONNECT, AdbOutput.ServiceType.PAIRING))
    while (guid !in session.seen(AdbOutput.ServiceType.CONNECT) && System.currentTimeMillis() - t0 < 8_000) Thread.sleep(50)
    println("bonjour: saw ${session.seen(AdbOutput.ServiceType.CONNECT)} after ${System.currentTimeMillis() - t0} ms")
    val ep = session.awaitEndpoint(guid, AdbOutput.ServiceType.CONNECT, 8_000) ?: error("Bonjour didn't resolve $guid")
    println("bonjour: resolved $guid → host=${ep.host} ip=${ep.ip} port=${ep.port} txt=${ep.txt}  (${System.currentTimeMillis() - t0} ms)")
    val adbList = adb.mdnsServices().firstOrNull { it.instance == guid }
    println("adb's own list has it at: ${adbList?.address ?: "(not listed)"} → match=${adbList == null || adbList.address == ep.address}")

    // 2. Reconnect through Bonjour only.
    if (reconnect) {
        println("\n— reconnect: adb disconnect ${phone.serial}")
        println("  " + adb.run(listOf("disconnect", phone.serial), 5)?.trim())
        Thread.sleep(500)
        val device = RememberedDevice(guid, phone.model ?: "phone", RememberedDevice.Kind.TLS, null, guid)
        val r0 = System.currentTimeMillis()
        // adb's services list passed as empty: only the Bonjour path can find it.
        val outcome = Reconnector(adb).attempt(device, services = emptyList(), mdnsWorks = true, devices = adb.devices(), bonjour = session)
        println("  reconnector: $outcome in ${System.currentTimeMillis() - r0} ms")
        val after = adb.devices().map { "${it.serial} ${it.state}" }
        println("  adb devices: $after")
        check(outcome.connected) { "Bonjour-direct reconnect failed" }
        // Restore the transport the user had (adb's own mdns-named one): drop ours, let adb's auto-connect bring it back.
        if (outcome.serial != null && outcome.serial != phone.serial) {
            adb.run(listOf("disconnect", outcome.serial!!), 5)
            val w0 = System.currentTimeMillis()
            while (adb.devices().none { it.serial == phone.serial && it.ready } && System.currentTimeMillis() - w0 < 25_000) Thread.sleep(500)
            if (adb.devices().none { it.serial == phone.serial && it.ready }) {
                println("  adb didn't auto-connect within 25 s — reconnecting at ${ep.address} so the phone stays connected")
                adb.connect(ep.address)
            }
        }
        println("  restored: ${adb.devices().map { "${it.serial} ${it.state}" }}")
    }
    session.close()

    // 3. The doctor, end to end.
    println("\n— connection doctor")
    val d0 = System.currentTimeMillis()
    val facts = DoctorProbe(adb, { Adb.resolve() }, browser).run(DoctorContext(targetGuids = setOf(guid), appVersion = "dev"))
    val result = DoctorResult.of(facts)
    println("doctor ran in ${System.currentTimeMillis() - d0} ms")
    result.checks.forEach { println("  [${it.verdict.word}] ${it.title}") }
    println("\n${result.markdown}")

    // 4. Nothing left running.
    browser.closeAll()
    Thread.sleep(300)
    val leftover = ProcessBuilder("pgrep", "-lx", "dns-sd").start().inputStream.bufferedReader().readText().trim()
    println("dns-sd processes left: ${leftover.ifEmpty { "none" }}  tracked live: ${browser.live.size}")
    kotlin.system.exitProcess(0)
}
