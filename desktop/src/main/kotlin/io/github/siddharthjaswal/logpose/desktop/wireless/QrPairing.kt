package io.github.siddharthjaswal.logpose.desktop.wireless

import io.nayuki.qrcodegen.QrCode
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The QR code a phone scans under *Developer options → Wireless debugging → Pair device with QR code*.
 *
 * Format, confirmed against Android Settings' parser (`wifi/dpp/AdbQrCode.java`, used by
 * `AdbQrcodeScannerFragment`): ZXing's Wi-Fi config syntax with the security type `ADB` —
 *
 *     WIFI:T:ADB;S:<service-name>;P:<password>;;
 *
 * Settings splits on unescaped `;`, removes the backslash from `\\`, `\;`, `\,` and `\:`, rejects any
 * `T` other than `ADB`, and requires a non-empty `S` and `P`. It then calls
 * `AdbManager.enablePairingByQrCode(S, P)`: adbd advertises an `_adb-tls-pairing._tcp` mDNS service
 * whose **instance name is exactly S**, and accepts `P` as the pairing secret. The host finds that
 * instance in `adb mdns services` and runs `adb pair <ip:port> <P>`.
 */
data class QrPairingCode(val serviceName: String, val password: String) {
    val payload: String get() = QrPairingCodes.payload(serviceName, password)
}

object QrPairingCodes {

    /** Prefix of our pairing service names (Android Studio uses `studio-`; adbd accepts any name). */
    const val SERVICE_PREFIX = "logpose-"

    private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** Backslash-escapes the four characters ZXing's Wi-Fi syntax reserves: `\ ; , :`. */
    fun escape(value: String): String = buildString {
        for (c in value) {
            if (c == '\\' || c == ';' || c == ',' || c == ':') append('\\')
            append(c)
        }
    }

    fun payload(serviceName: String, password: String): String {
        require(serviceName.isNotEmpty()) { "service name is required" }
        require(password.isNotEmpty()) { "password is required" }
        return "WIFI:T:ADB;S:${escape(serviceName)};P:${escape(password)};;"
    }

    /**
     * A fresh code per *Add device* sheet: `logpose-` + 10 alphanumerics, and a 12-character
     * alphanumeric password. Alphanumerics only, so nothing needs escaping and nothing a phone's QR
     * library might mishandle ends up in the code — [escape] exists for correctness, not use.
     */
    fun generate(random: Random = SecureRandom().asKotlinRandom()): QrPairingCode {
        fun token(n: Int) = (1..n).map { ALNUM[random.nextInt(ALNUM.length)] }.joinToString("")
        return QrPairingCode(SERVICE_PREFIX + token(10), token(12))
    }
}

/** A QR symbol as a square grid of dark modules, without the quiet zone (the renderer adds it). */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        /**
         * Encodes [text] with error correction **M**: the payload is ~50 bytes, so this is a 33×33
         * (version 4) symbol — big modules, scans from across a desk. Nayuki raises the ECC level
         * for free when the version has room (`boostEcl`).
         */
        fun encode(text: String): QrMatrix {
            val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
            val cells = BooleanArray(qr.size * qr.size) { i -> qr.getModule(i % qr.size, i / qr.size) }
            return QrMatrix(qr.size, cells)
        }
    }
}
