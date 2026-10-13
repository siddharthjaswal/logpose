# Mac app — wireless pairing manual test (M2 gate)

The parts of M2 an agent can't prove: pairing a real phone by QR with no cable, and recovering
after a Wi-Fi drop. Everything else is covered by tests against real `adb` output.

1. `./gradlew :desktop:run`. Mac and phone on the same home or hotspot Wi-Fi (not office or guest
   Wi-Fi). Unplug every cable.
2. If the How-to-connect sheet opens on first launch, check the steps, then click **Add a device**.
3. On the phone: Developer options → Wireless debugging → on → **Pair device with QR code**. Scan the
   code in the app.
4. Expect: "Scan this code…" → "Found your phone — pairing…" → "Paired. Connecting…" → "Connected".
   The phone appears in the sidebar with the Wi-Fi glyph and is selected.
5. Start session, use the app, confirm rows appear. Stop.
6. Turn the phone's Wi-Fi off for 30 s. The row should say "Wi-Fi connection lost — reconnecting…"
   or "Not on this Wi-Fi…". Turn Wi-Fi back on: Ready again within ~5–60 s, no clicks.
7. Turn Wireless debugging off and on (this changes the connect port). It should reconnect on its own.
8. Quit and relaunch the app. It should reconnect without pairing again.
9. **Forget device**: the row disappears and `adb devices` no longer lists it.
10. Pairing-code tab: "Pair device with pairing code", type the address and 6 digits — it should
    connect. Then a wrong code: it should say "That code didn't work".
11. On office or guest Wi-Fi, if available: scan and wait 90 s. Expect "Couldn't find any phone on
    this Wi-Fi", the client-isolation explanation, and a **Use the cable** button.
12. With a cable phone (ideally Android 10 or older): **Switch to wireless** should end with
    "…You can unplug the cable." Unplug — it stays Ready. Restart the phone: the row should say to
    plug in the cable and switch again.
