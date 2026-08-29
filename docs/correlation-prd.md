# PRD — LogPose Correlation Keys

**Status:** Draft v1 · **Owner:** Sid · **Targets:** plugin **1.9.0** · `logpose-android` **v1.7.1**
**One-liner:** Group a flow by the id a human actually knows — `order_id 21053953` — instead of
a trace hash the app may never have propagated.

Companion to [`docs/flow-driver-prd.md`](flow-driver-prd.md), which shipped the waterfall and push
injection in 1.8.0. This one fixes what dogfooding 1.8.0 on gandalf exposed.

---

## 1. What dogfooding found

Two defects and one design gap, all from one re-sent push on gandalf (library 1.7.0, plugin 1.8.0):

**a) An injected push produces two rows, and the second one lies.** Re-sending emitted
`inj-a919724d` (`injected: true`, trace `trc-916a23d1`) and, 24 ms later, `inj-7583a101`
(`injected: false`, trace `26ef6ebb`) — same `messageId`. The second is the *app's own*
`FirebaseMessagingService` calling `LogPose.logFcmMessage` after LogPose delivered the push.
LogPose doesn't recognize the re-log as its own, so an injected push appears on the timeline
unmarked — exactly what the INJ pill exists to prevent.

**b) The waterfall shows one event, because traces fragment.** The app starts its own trace when
handling a push, so the IDE's trace never propagates; and HTTP rows join a trace only via an
OkHttp request tag, which gandalf doesn't use. Trace `26ef6ebb` therefore holds the push and two
analytics events — **no HTTP at all**. Meanwhile the same flow's events span four different
traces, one of them null.

**c) `Show waterfall d107086f` is a meaningless label.** A trace hash tells a human nothing. The
id they actually know is `order_id`.

The evidence that settles the design: `contains: 21053953` returns **5 events across 4 traces**,
including the `GET /app/v4/79096/order/21053953/` that has no trace at all. The business key
correlates what tracing structurally cannot, and needs no app cooperation.

## 2. Goals

1. A user-defined correlation key (`order_id`) groups a flow end-to-end, across traces and across
   events with no trace.
2. Every entry point reads as the key, not a hash: *Show waterfall — order_id 21053953*.
3. An injected push is one row, and it stays marked as injected.

### Non-goals

- Automatic key inference as the primary mechanism (it is a *suggestion* aid only — §4.1).
- Cross-session correlation (a key groups within the current capture).
- Changing how traces work, or requiring apps to adopt `traceCalls`.

## 3. The correlation model

**The key names the value; the value does the matching.** This is the whole design, and it's what
makes it work on real payloads:

1. **Extract.** For each configured key (`order_id`), scan an event's payload for that key by name
   and read its value. The scan is recursive **and parses JSON embedded in string values** —
   gandalf nests the real payload as a JSON string inside `data["body"]`, so a shallow scan finds
   nothing. Key matching is case-insensitive and snake/camel-insensitive (`order_id`, `orderId`,
   `ORDER_ID` are one key).
2. **Match.** Once the value is known, an event belongs to the group if that **value** appears
   anywhere in its searchable text — URL and path, request/response body, FCM data, db args and
   SQL, generic section bodies. This is what catches `/app/v4/79096/order/21053953/`, where the id
   is a bare path segment with no key attached.
3. **Guard against over-grouping.** A value must be ≥ 4 characters, and matching is
   delimiter-bounded (the value must not be flanked by `[A-Za-z0-9]`), so `2105` never matches
   `21053953` and vice versa. Values failing the length rule are extracted but marked
   low-confidence and excluded from matching unless the user opts in per key.

`analysis/Correlation.kt` — pure, no IntelliJ/Swing imports, unit-tested — owns all of it:

```
data class CorrelationKey(val name: String, val enabled: Boolean = true, val minLength: Int = 4)
fun valuesFor(event: LogEvent, keys: List<CorrelationKey>): Map<String, String>   // key -> value
fun group(events: List<LogEvent>, key: String, value: String): List<LogEvent>      // value match
fun suggest(events: List<LogEvent>): List<Suggestion>                              // §4.1
```

## 4. User experience

### 4.1 Defining keys

- **Where:** a `Correlation keys…` item in the filter bar's overflow (and from the waterfall
  header). Small dialog: a list of key names, add/remove/enable, plus per-key "allow short
  values". Project-scoped, persisted in `PropertiesComponent` — it's a per-app vocabulary.
- **Seeded, not blank.** On first open, `suggest()` proposes id-ish keys actually present in the
  capture (`*_id`/`id`-suffixed names whose values look like identifiers, ranked by how many
  distinct events they'd group). The user ticks the ones that matter. This is auto-detection in
  its right role: a discovery aid the human confirms, never a silent grouping decision.
- Ships with **no** built-in defaults — `order_id` is gandalf's vocabulary, not LogPose's.

### 4.2 Using them

- **Row context menu** leads with the key: `Show waterfall — order_id 21053953`, then
  `Filter by order_id 21053953`, and the existing trace actions demoted below and explicitly
  labelled `Show waterfall — trace d107086f`. When a row carries several configured keys, each
  gets its own item (`order_id …`, `trip_id …`). When none match, the menu is exactly as today.
- **Row affordance:** the hover band that already reveals `⧉ cURL` on HTTP rows also reveals a
  waterfall glyph on any row with a key or a trace; one click opens the best available grouping
  (configured key first, trace as fallback). This is the "icon in the row" ask — same pattern,
  no new interaction vocabulary.
- **Waterfall header** states the grouping: `order_id 21053953 · 7 events · 4.2s` with a
  segmented switcher when the row belongs to more than one grouping (`order_id` / `trip_id` /
  `trace`), so you can widen or narrow without going back to the list.
- **Filter bar** gains a removable chip when filtering by a key, mirroring how trace filtering
  already reads.

### 4.2.1 Find by value (no row required)

The common real case is arriving with an id from somewhere else — a ticket, a backend log, a QA
report — and wanting everything about it. A **`Find by value…`** action (filter bar overflow, and
the waterfall's empty state) takes a pasted value directly:

- Input accepts a bare value (`21053953`) or a `key=value` pair (`order_id=21053953`). A bare value
  is matched by §3's value rule alone; if a configured key elsewhere in the capture holds that
  value, the result is labelled with that key, otherwise it reads `value 21053953`.
- Result opens straight into the waterfall for the group, with the count stated before you commit
  (`7 events` / `no events carry that value`) so a typo is obvious rather than an empty screen.
- Trims whitespace and strips surrounding quotes on paste — ids arrive from JSON as often as not.
- The same short-value guard applies (≥ 4 chars), with an explicit "too short to match safely"
  message rather than a silent empty result.

MCP parity already exists: `get_related` takes `key` + `value`, and accepts `value` alone for the
bare-paste equivalent.

### 4.3 MCP

Agents get the same capability, which is strictly more useful than `get_trace` for the same reason
it is in the UI:

- `list_correlation_keys` — configured keys plus suggestions, with the values seen most recently.
- `get_related` — args `key` + `value`, or `event_id` (use that event's key values). Returns the
  grouped timeline in the `get_trace` response shape, with a `grouped_by` field naming the key.
- `get_trace`'s empty-result note points at `get_related` when the app doesn't propagate traces —
  the failure mode gandalf demonstrates.

## 5. Injected-row collapse (library v1.7.1)

- **Plugin:** `PushInject.id` and `PushMessage.messageId` become the same value, so the ack
  correlation id, the envelope id and the message id all agree.
- **Library:** emit the injected FCM envelope with `id = messageId` — the same trick worker events
  already use so a request is one updating row — and keep a bounded (32-entry) set of injected
  messageIds so a later `logFcmMessage` for one of them still sets `injected = true`. The app's
  own re-log then lands on the *same* envelope id, updating one row instead of adding an unmarked
  twin. Mirror nothing new in the no-op (no public API change); bump `LogPoseRuntime.VERSION`.
- **Wire:** no changes. The fix is entirely in how ids are chosen.
- Tests: re-log after injection keeps the flag; two rows collapse to one id; a genuine push with a
  coincidentally-equal messageId (impossible in practice, cheap to assert) doesn't get marked.

## 6. Definition of done

- `CorrelationTest`: nested-JSON extraction (the `data["body"]` shape), snake/camel key matching,
  delimiter-bounded value matching (`2105` vs `21053953`), min-length guard, suggestion ranking,
  grouping across events with null traces.
- `McpToolsTest`: `list_correlation_keys`, `get_related` by key/value, by bare value, and by
  event_id, unknown key, too-short value, no-match note; catalogue order updated.
- Find-by-value parsing (bare vs `key=value`, quote/whitespace trimming, short-value refusal)
  covered as pure logic, not through the dialog.
- Library: injected-collapse tests above; full suite green both halves.
- Manual: on gandalf, re-send a push → **one** INJ-marked row; open the waterfall by `order_id` →
  the push, the `GET /order/<id>/`, and the `PUT /order/accept/` in one picture.
- Docs: README correlation section (with the "the key names the value, the value does the
  matching" explanation), CHANGELOG, `<change-notes>`, plugin 1.9.0 / library v1.7.1.

## 7. Phasing

- **M1 — `analysis/Correlation.kt` + tests** (pure logic, no UI). The whole design risk lives here.
- **M2 — Library v1.7.1** injected-row collapse (independent; tag it and it's done).
- **M3 — Plugin UI**: keys dialog + persistence, menu items, hover affordance, waterfall header
  switcher, filter chip.
- **M4 — MCP** `list_correlation_keys` / `get_related`, `get_trace` note.
- **M5 — Docs + dogfood pass** on gandalf.

## 8. Risks

| Risk | Mitigation |
|------|------------|
| Over-grouping on a common value | ≥4 chars, delimiter-bounded, short values opt-in per key |
| Value scan cost on every row paint | Extract once per event on arrival and cache on the store entry; never scan during paint (the repaint-cost lesson from 1.8.0) |
| Suggestions feel like magic grouping | Suggestions are inert until ticked; nothing groups without a configured key |
| Key vocabulary differs per project | Project-scoped storage, no built-in defaults |
