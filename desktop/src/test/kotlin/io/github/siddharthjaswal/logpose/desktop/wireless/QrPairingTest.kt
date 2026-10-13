package io.github.siddharthjaswal.logpose.desktop.wireless

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class QrPairingTest {

    @Test fun `payload is Android Studio's WIFI ADB format`() {
        assertEquals("WIFI:T:ADB;S:studio-abc;P:pw123;;", QrPairingCodes.payload("studio-abc", "pw123"))
    }

    @Test fun `reserved characters are backslash-escaped per the ZXing wifi convention`() {
        assertEquals("a\\;b\\,c\\:d\\\\e\"f", QrPairingCodes.escape("a;b,c:d\\e\"f"))
        assertEquals("WIFI:T:ADB;S:x\\;y;P:p\\:w;;", QrPairingCodes.payload("x;y", "p:w"))
        assertThrows(IllegalArgumentException::class.java) { QrPairingCodes.payload("", "p") }
        assertThrows(IllegalArgumentException::class.java) { QrPairingCodes.payload("s", "") }
    }

    /** Mirrors Android Settings' AdbQrCode parse: split on unescaped ';', strip backslashes. */
    private fun parseLikeAndroid(qr: String): Map<String, String> {
        require(qr.startsWith("WIFI:"))
        return qr.removePrefix("WIFI:").split(Regex("(?<!\\\\);")).filter { it.length > 2 && it[1] == ':' }
            .associate { kv -> kv.substring(0, 2) to kv.substring(2).replace(Regex("\\\\(.)"), "$1") }
    }

    @Test fun `escaped values survive Android's parser`() {
        val parsed = parseLikeAndroid(QrPairingCodes.payload("odd;name,x", "p:w\\d"))
        assertEquals("ADB", parsed["T:"])
        assertEquals("odd;name,x", parsed["S:"])
        assertEquals("p:w\\d", parsed["P:"])
    }

    @Test fun `generated codes are fresh, prefixed, alphanumeric`() {
        val a = QrPairingCodes.generate(Random(1))
        val b = QrPairingCodes.generate(Random(2))
        assertNotEquals(a, b)
        assertTrue(a.serviceName.matches(Regex("logpose-[A-Za-z0-9]{10}")), a.serviceName)
        assertTrue(a.password.matches(Regex("[A-Za-z0-9]{12}")), a.password)
        assertEquals("WIFI:T:ADB;S:${a.serviceName};P:${a.password};;", a.payload)
    }

    @Test fun `the encoded matrix decodes back to the payload`() {
        val code = QrPairingCodes.generate(Random(42))
        val matrix = QrMatrix.encode(code.payload)
        assertEquals(33, matrix.size) // version 4 at ECC M for a 48-byte payload
        assertEquals(code.payload, decode(matrix, moduleSize = 6))
        // Even with weird characters in it.
        val odd = QrPairingCodes.payload("x;y:z", "p,w")
        assertEquals(odd, decode(QrMatrix.encode(odd), moduleSize = 4))
    }

    companion object {
        /** Rasterises [matrix] with a 4-module quiet zone and decodes it with ZXing. */
        fun decode(matrix: QrMatrix, moduleSize: Int): String {
            val n = (matrix.size + 8) * moduleSize
            val pixels = IntArray(n * n) { i ->
                val x = (i % n) / moduleSize - 4
                val y = (i / n) / moduleSize - 4
                val dark = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
                if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            return decodePixels(pixels, n, n)
        }

        fun decodePixels(argb: IntArray, width: Int, height: Int): String {
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb)))
            return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
        }
    }
}
