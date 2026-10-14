# Mac app — wireless pairing manual test (M2 gate)

The parts of M2 an agent can't prove: pairing a real phone by QR with no cable, and recovering
after a Wi-Fi drop. Everything else is covered by tests against real `adb` output.

1. `./gradlew :desktop:run`. Mac and phone on the same home or hotspot Wi-Fi (not office or guest
   Wi-Fi). Unplug every cable.
2. If the How-to-connect sheet opens on first launch, check the steps, then click **Show QR code** on the welcome screen (or **Add device…** in the
   device menu at the top left, ⌘K).
3. On the phone: Developer options → Wireless debugging → on → **Pair device with QR code**. Scan the
   code in the app.
4. Expect the staged list to tick through: "Waiting for your phone to scan…" → **"Phone found (via
   macOS Bonjour)"** (or "via adb's discovery" — note which; on Macs where adb's discovery is blind
   it must be Bonjour) → "Pairing with 192.168.x.y" → "Connecting…" → "Connected", each with its
   time. The phone appears in the toolbar's device chip with the Wi-Fi glyph and is selected (⌘K lists
   it under Connected).
4a. Stall help: open the QR sheet and *don't* scan for 15 s. A panel appears: "No progress for 15s —
   LogPose checked your connection" with a finding (on a napping phone: "Your phone is saving Wi-Fi
   power") and the buttons Try pairing code · Use USB cable · Enter IP & port manually · Run
   connection check · Copy diagnostics. Then scan — pairing must still complete.
5. Start session, use the app, confirm rows appear. Stop.
6. Turn the phone's Wi-Fi off for 30 s. Its row in the device menu should say "Wi-Fi connection lost — reconnecting…"
   or "Not on this Wi-Fi…". Turn Wi-Fi back on: Ready again within ~5–60 s, no clicks.
7. Turn Wireless debugging off and on (this changes the connect port). It should reconnect on its own.
8. Quit and relaunch the app. It should reconnect without pairing again.
9. **Forget** (hover the phone in the device menu → trash icon): the row disappears and `adb devices`
   no longer lists it.
9a. **Disconnect** (hover → unlink icon) on a remembered phone: it moves to *Remembered Wi-Fi
   phones* saying "Disconnected — Reconnect to use it again" and is *not* redialled; **Reconnect**
   brings it back within a few seconds.
10. Pairing-code tab: "Pair device with pairing code", type the address and 6 digits — it should
    connect. Then a wrong code: it should say "That code didn't work".
10a. **Enter IP & port manually** (pairing-code tab → "Already paired this phone?…"): type the
    address from the phone's Wireless debugging screen — it connects with no discovery at all.
10b. **Check connection** (device menu, or the welcome screen): within ~5 s every check shows a verdict. With the phone on this
    Wi-Fi expect "N phones with Wireless debugging are visible", "Phone and Mac are on the same
    network", "No VPN in the way" (even though `ifconfig` lists utun interfaces). **Copy
    diagnostics**, paste into a text editor: Mac/adb versions, both discovery lists, ping stats,
    VPN/firewall, the last attempt's path and timings — and no pairing password.
10c. Without a scan: `./gradlew :desktop:wirelessLive -Preconnect=true` with a paired phone on Wi-Fi
    proves Bonjour discovery + resolve, a Bonjour-only reconnect (it disconnects and restores the
    phone), the doctor end to end, and that no `dns-sd` process is left.
11. On office or guest Wi-Fi, if available: scan and wait 90 s. Expect "Couldn't find any phone on
    this Wi-Fi", the client-isolation explanation, and a **Use the cable** button; the doctor's
    finding is "This Mac can't see any phone on the network", and it doesn't offer the pairing code.
12. With a cable phone (ideally Android 10 or older): **Switch to wireless** should end with
    "…You can unplug the cable." Unplug — it stays Ready. Restart the phone: the row should say to
    plug in the cable and switch again.
