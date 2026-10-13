package io.github.siddharthjaswal.logpose.desktop.wireless

/**
 * *Switch to wireless* for a cable-attached phone — the only wireless route on Android 10 and older,
 * and it works on newer phones too. Reads the phone's Wi-Fi IP over the cable, restarts its adbd in
 * TCP mode (`adb tcpip 5555`), then `adb connect ip:5555`. After that the cable can come out.
 *
 * Blocking adb calls: run on `Dispatchers.IO`.
 */
class LegacySwitch(
    private val adb: AdbCommands,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    sealed interface Result {
        data class Connected(val address: String, val serial: String) : Result
        /** The phone has no Wi-Fi address: Wi-Fi is off or not joined. */
        data object NoWifi : Result
        data class TcpipFailed(val detail: String) : Result
        data class ConnectFailed(val address: String, val detail: String) : Result
    }

    suspend fun switch(serial: String, port: Int = PORT): Result {
        val ip = AdbOutput.wifiIp(adb.shell(serial, "ip route"), null)
            ?: AdbOutput.wifiIp(null, adb.shell(serial, "ip addr show wlan0"))
            ?: return Result.NoWifi
        val tcpip = adb.tcpip(serial, port)
        if (tcpip == null || !AdbOutput.tcpipOk(tcpip)) return Result.TcpipFailed(tcpip?.trim().orEmpty().ifEmpty { "adb tcpip didn't answer" })
        // adbd restarts; the USB transport drops and comes back. Give it a moment before dialling in.
        sleep(2_000)
        val address = "$ip:$port"
        var last = ""
        repeat(5) {
            when (val r = adb.connect(address)) {
                is AdbOutput.ConnectResult.Connected -> return Result.Connected(address, r.serial)
                is AdbOutput.ConnectResult.AlreadyConnected -> return Result.Connected(address, r.serial)
                is AdbOutput.ConnectResult.NotAuthorized -> return Result.ConnectFailed(address, r.detail)
                is AdbOutput.ConnectResult.Unreachable -> last = r.detail
                is AdbOutput.ConnectResult.Failed -> last = r.detail
            }
            sleep(1_500)
        }
        return Result.ConnectFailed(address, last)
    }

    companion object {
        const val PORT = 5555

        fun describe(result: Result): String = when (result) {
            is Result.Connected -> "Connected over Wi-Fi at ${result.address}. You can unplug the cable."
            Result.NoWifi -> "The phone isn't on Wi-Fi. Turn Wi-Fi on, join the same network as this Mac, and try again."
            is Result.TcpipFailed -> "The phone didn't switch to wireless mode: ${result.detail}"
            is Result.ConnectFailed ->
                "The phone switched to wireless mode but this Mac couldn't reach it at ${result.address}. " +
                    "Are both on the same Wi-Fi? Office Wi-Fi often blocks this — keep using the cable."
        }
    }
}
