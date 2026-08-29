# Design Handoff — LogPose

**For:** Claude Design · **From:** Sid · **Version described:** plugin 1.9.0 / library v1.7.1 · **Date:** 2026-08-29

This is a redesign brief. It documents **every element and every feature** of the LogPose tool
window as built, with the exact values in the code, and ends with the problems worth solving.
Treat the values as the current baseline, not as constraints — except where §2 marks them as
platform limits, which are real.

---

## 1. What LogPose is

An open-source **runtime inspector for Android**, shipped as a JetBrains IDE plugin. It reads an
app's HTTP traffic, push messages, database queries, background work, config changes and
app-defined events out of **logcat** and renders them as one clean, structured timeline — fixing
the interleaved lines, mismatched bodies and 4 KB truncation you get from ad-hoc logging.

Three things separate it from a proxy tool like Charles or Proxyman:

1. **Zero setup.** No proxy, no certificates. An OkHttp interceptor and `adb logcat`.
2. **It can talk back.** Mock rules, scenario suites and synthetic push injection travel
   IDE → device, so LogPose can *drive* a flow, not just watch one.
3. **An agent can read it.** The same capture is exposed over MCP (21 tools), so a coding agent
   sees what the running app actually did — and can change what it receives next.

**Who uses it:** Android engineers debugging a running app, mid-task, in a dark IDE, in a panel
that is often only 300–400px tall. They are not visiting a dashboard; they are glancing at it
between edits. **Scanning speed beats completeness** in every layout decision.

**Where it lives:** a bottom-docked tool window in Android Studio / IntelliJ. It shares the screen
with the editor and the user's real work. It should feel native to the IDE, never like a web app
embedded in one.

---

## 2. Platform constraints (real, not preferences)

- **Java Swing, custom-painted.** No CSS, no flexbox, no web animation. Everything here is
  `paintComponent` with `Graphics2D`, antialiasing on. Gradients, shadows and blurs are possible
  but expensive and, by IDE convention, unusual.
- **Two themes from one definition.** Every color is a `JBColor(light, dark)` pair. The IDE theme
  can change at runtime. Nothing may be a single hardcoded color. Contrast must hold on both a
  white (`#F7F8FA`) and a near-black (`#1E1F22`) ground.
- **The IDE owns chrome.** Tool window title, notification balloons, dialog frames, diff viewer,
  editor components and the JSON editor are all platform widgets. We style *within* them.
- **Density is the IDE's, not the web's.** Row heights are 24–34px, fonts 9–14pt. A comfortable
  web-scale redesign would halve the visible event count and fail the product.
- **Fonts:** the IDE label font, and JetBrains Mono for anything with digits or identifiers.
  Custom typefaces are not available.
- **Icons** are 16×16 (or 13×13) single-color SVGs, or platform `AllIcons.*`.
- **Live data.** The list updates at up to ~7 fps under load; rows mutate in place (a pending
  request becomes a completed one). Nothing may require a scan or a layout pass during paint —
  this caused a real performance bug once already.

---

## 3. The design system as built

### 3.1 Color tokens

Every token is `JBColor(light, dark)`. Source: `ui/Ui.kt`.

**Surfaces**

| Token | Light | Dark | Role |
|---|---|---|---|
| `bg0` | `#F7F8FA` | `#1E1F22` | Window / list / viewport ground |
| `bg1` | `#FFFFFF` | `#26282C` | Cards, chips, editors |
| `bg2` | `#F0F1F4` | `#2B2D30` | Card headers, inputs, stat chips, ghost buttons |
| `bg3` | `#E3E5EA` | `#303236` | Segmented-control track, switch "off" track |
| `rowHover` | `#EAECF1` | `#2F3136` | Hovered list row / waterfall lane |

**Borders**

| Token | Light | Dark | Role |
|---|---|---|---|
| `borderStrong` | `#C6C9D2` | `#393B40` | Dividers, input frames, chip outlines (20 sites) |
| `borderSubtle` | `#E7E8EE` | `#2D2F33` | Card outlines, secondary gridlines |

**Text**

| Token | Light | Dark | Role |
|---|---|---|---|
| `text` | `#1E1F22` | `#DFE1E5` | Primary |
| `textDim` | `#6C707E` | `#A3A6AD` | Secondary (39 sites — the workhorse) |
| `textMuted` | `#9296A1` | `#6B6E76` | Tertiary: captions, hints, axis labels |

**Accent**

| Token | Light | Dark | Role |
|---|---|---|---|
| `accent` | `#3574F0` | `#3574F0` | Links, selection, in-flight, hover affordances |
| `accentHover` | `#2E6AE0` | `#4A82F2` | Rollover only (2 sites) |
| `accentTint` | `rgba(53,116,240,0.15)` | same | Selected row fill, active chip fill |
| `onAccent` | `#FFFFFF` | `#FFFFFF` | Text/knob on accent |

**Semantic**

| Token | Light | Dark | Role |
|---|---|---|---|
| `warn` | `#A86A12` | `#E3B34C` | Duplicate (medium), timeouts, pending sync, withheld |
| `warnTint` | `rgba(227,179,76,0.17)` | same | Its fill |
| `danger` | `#CF3030` | `#EC7A70` | Errors, failures, double-submits |
| `dangerTint` | `rgba(236,122,112,0.17)` | same | Its fill |
| `findAll` | `rgba(255,213,79,0.55)` | `rgba(227,179,76,0.30)` | Find-match highlight |

**JSON syntax** — `jsonKey` `#871094`/`#C77DBB` · `jsonString` `#067D17`/`#7FB069` · `jsonNumber`
`#1750EB`/`#5B9DFF` · `jsonBool` `#0033B3`/`#CC8A52` · `jsonNull` `#808080`/`#8D9298` ·
`jsonPunct` `#5B5E66`/`#787C84` · `jsonCount` `#8C8F98`/`#6F737A`.

### 3.2 Three derived palettes (and their collisions)

**Method** (HTTP verbs): GET `#2E6AE0`/`#5B9DFF` blue · POST `#1A8A3A`/`#5CC26F` green ·
PUT `#A86A12`/`#E0A740` amber · DELETE `#CF3030`/`#E8736A` red · PATCH **and every unknown verb**
`#8250DF`/`#C08CF0` purple.

**Status** (HTTP classes): 2xx green · 3xx `#2E6AE0`/`#5AA9D6` blue-cyan · 4xx amber · 5xx/error
red · unknown `textDim`. Each has a paired 15–17% translucent fill.

**Event type** (one hue per kind, used by the row gutter icon, the TYPE filter chip and the detail
header pill — deliberately the same hue in all three so filter state is legible from the rows):
http `#2E6AE0`/`#5B9DFF` blue · fcm `#8B3FD9`/`#C084FC` purple · db `#A86A12`/`#E0A740` amber ·
worker `#0E9488`/`#2DD4BF` teal · config `#5B6472`/`#94A3B8` slate · analytics `#C42E7A`/`#F472B6`
pink · app-defined `#1F9E4A`/`#4ADE80` green.

> **Known problem.** These three palettes overlap heavily. Amber means "PUT", "4xx", "db",
> "warning", "timeout" and "pending sync". Purple means "PATCH", "MOCK", "FCM NOTIF", "the MOCKS
> strip" and "merge mode". Green means "POST", "2xx", "app-defined event" and "synced". A row can
> show three different ambers meaning three different things. This is the single biggest
> systematic issue in the current design.

### 3.3 Typography

Two families. **Label font** for words, chips, titles, controls. **JetBrains Mono** for numbers,
identifiers, URLs and payload text, so digits align in fixed-width columns.

| Size | Weight | Used for |
|---|---|---|
| label 15 | bold | Filter overflow `⋯` |
| label 13 | bold | Detail card header trio (kind pill, title, "Overview") |
| label 12.5 | regular | **The row's primary text** (path / summary / title) |
| label 12 | bold | Method pill, waterfall headline, switch label, buttons |
| label 12 | regular | Mock rule path, plain section body |
| label 11.5 | bold | Filter chips, duplicate banner, injected banner |
| label 11.5 | regular | Dialog copy, find-by-value preview |
| label 11 | bold | Row method + status pill, section labels, switcher tabs |
| label 11 | regular | Baseline small: links, hints, sync text, version label, tag default |
| label 10.5 | bold | Filter group captions (`TYPE` / `METHOD` / `STATUS`), `MOCKS` |
| label 10 | bold | Micro-pills: `DUP ×n`, `MOCK`, `INJ`, FCM kind |
| label 9.5 | regular | Stat chip caption (uppercased) |
| mono 14 | bold | Stat chip value (largest mono) |
| mono 13 | — | JSON tree, detail summary text |
| mono 12 | — | URLs, error text, code sections, KV tables, body editors |
| mono 11 | — | Row meta columns, waterfall subline, hit counts |
| mono 10 | — | Waterfall trailing durations, mocks chips |
| mono 9 | — | Waterfall axis ticks (smallest type in the plugin) |

### 3.4 Spacing, radii, geometry

- **Scale steps in use:** 1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 20, 22, 24, 28, 34 — roughly a 2px
  grid with 6/8/10/12 doing most of the work. All go through `JBUI.scale()` for HiDPI.
- **Radii:** 2 (selection rail) · 4 (waterfall bar) · 6 (tag pill, segmented thumb) · 8 (rows,
  stat chips, buttons, cards-in-strip, waterfall tabs) · 10 (cards, filter chips) · full stadium
  (toggle switch).
- **Row heights:** 34 standard · 26 muted HTTP · 24 waterfall lane · 22 JSON tree · 28 patch tree.
- **Key paddings:** detail root `8` · card body `12,14` · filter bar `7,12` · list row `0,14` ·
  tag pill `2,8` · micro-pill `2,7` · filter chip `3,13`.
- **Splitters:** list ↔ detail `0.44` (mins 220 / 320) · HTTP overview ↔ panes `0.30` ·
  request ↔ response `0.50` · FCM `0.34` · generic `0.50`.

### 3.5 Components

| Component | Anatomy | States |
|---|---|---|
| **TagLabel** (pill/badge) | Rounded fill (arc 6), centered colored text, no stroke, padding `2,8` | None built in; "disabled" is the caller passing faded colors |
| **CardPanel** | Rounded `bg1` fill (arc 10) + `borderSubtle` stroke, padding 10 | — |
| **StatChip** | 2-row card: uppercase caption (9.5) over mono-14-bold value; `bg2` fill, `borderStrong` stroke, arc 8 | Optional **clickable**: value turns accent, hand cursor; hover swaps stroke → accent and value → accentHover |
| **PillButton** | Filled (accent fill, white text) or ghost (`bg2` fill, `borderStrong` stroke); arc 8, min height 24 | Rollover changes fill only |
| **StatusDot** | 8px circle in a 12px box | Green pulsing 0.45→1.0 alpha at 60ms while capturing; solid red when stopped |
| **ToggleChip** | Filter pill, arc 10, padding `3,13`, bold 11.5 | Unselected: outline + `textDim` (or nothing at all when `flat`). Selected: 40-alpha tint fill + colored stroke + colored text. **No hover state.** |
| **RemovableChip** | `label ✕`, always active-looking: `accentTint` fill + accent stroke | Whole chip is the remove target |
| **ToggleSwitch** | 34×20 stadium; accent track on, `bg3` off; white knob inset 3 | **No hover, no disabled** |
| **Segmented** | `bg3` track (arc 8) with a `bg0` thumb (arc 6) under the selected label | Selected label `text`, others `textDim` |
| **Waterfall tab** | Arc 8; selected = accentTint + accent stroke + accent text (inert); unselected = `bg2` + `borderStrong` + `textDim` (clickable) | Hidden entirely when only one option exists |
| **Row background** | Selected: `accentTint` rounded fill + a 2px accent rail at the left edge. Hover: `rowHover` fill. Normal: nothing | Hover suppressed on the selected row |
| **Toast** | Platform HTML balloon above the component, 1400ms fadeout | Used for copy confirmations |

**Repeated but unformalized:** a "link label" (accent + 11pt + hand cursor) is hand-rolled in
**7 places**; an "icon button" (icon + tooltip + hand cursor) in **4**. Both are components
waiting to be named.

### 3.6 Icons

**Seven custom 16×16 type glyphs**, each with its kind's hue baked into the SVG (no runtime
tinting to fail): `net` a globe with meridian and equator · `fcm` a notification bell ·
`db` a database cylinder · `work` a gear/sunburst · `conf` three slider rails with knobs ·
`anly` a four-bar chart (heaviest stroke, 1.7) · `app` a phone handset. Stroke weights vary
1.3–1.7 between them, which is a minor inconsistency.

**Brand mark:** a compass — circle bezel, chevron needle pointing up, small rounded base. Ships at
13×13 (tool window), 16×16 (unreferenced action icon) and 40×40 (Marketplace, with a 33-tick
compass rose, purple needle `#b48ce8`, amber base `#e0a740` on `#161222`).

**~20 platform `AllIcons`** for actions (Execute, Suspend, GC, Copy, Find, ShowAsTree, Upload,
Download, ListFiles, Lightning, Refresh, Expandall, Collapseall, Prev/NextOccurence, Close, Diff,
Edit, Remove, Add, Settings).

**Unicode used as UI primitives:** `⋯` overflow · `✕` remove · `⧉ cURL` and `⇉ flow` hover
affordances · `⚠` duplicates · `⚡` injected · `●` sync dot · `▾`/`▸` disclosures · `···` pending ·
`—` absent · braille spinner `⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏` · `▶` in empty-state copy.

---

## 4. Surfaces, element by element

### 4.1 Shell

Root is `bg0`. Header stack on top (toolbar row → filter bar → mocks strip), then a horizontal
0.44 splitter: **timeline list** left, **detail card** right. The detail side is a 4-card stack:
HTTP, FCM, Generic, Waterfall.

### 4.2 Toolbar row

Left to right: **pulsing status dot** · **Capture** (▶ Execute / ⏸ Suspend, label flips
Start/Stop Capture) · **Clear** · separator · **Compose Push** · **Scenarios** · separator ·
**Connect Coding Agent** (Lightning). Far right: a dim **version label** reading
`v1.9.0 · device 1.7.1` — plugin version always, device library version once a device says hello,
because version skew between the two halves is what actually bites.

### 4.3 Filter bar

One long row, wrapped in 1px rules top and bottom:

`[search 168px] │ TYPE [NET][FCM][DB][WORK][CONF][ANLY][APP] │ METHOD [GET|POST|PUT|DELETE] │ STATUS [2xx][3xx][4xx][5xx] │ (switch) Hide noise │ [⚠ Dupes]` … right-aligned: `[order_id 21053953 ✕] [⋯] 42/218`

- Type chips are **individual pills** in their kind hues; method chips are a **segmented group**
  inside one outline; status chips are individual pills again. Three different groupings in one row.
- **DB is opt-in even when nothing is selected** — a busy screen runs hundreds of queries and
  would bury the traffic. It is the only chip with a tooltip explaining itself.
- **Selecting any method or status hides every non-HTTP kind entirely** — a hidden mode switch
  with no visual indication that it happened.
- The correlation chip and the Dupes chip are applied *outside* the main filter state (they depend
  on the whole capture, not one event).
- `⋯` opens **Find by value…** and **Correlation keys…**.

### 4.4 Timeline rows

One column geometry for all three row types, so the eye tracks a single set of vertical rules:

```
[14 inset][icon 18][10][method 46][10][status 46] │[12] centre text …flex… [pills] │ [size 64][10][time 56][14 inset]
```

**HTTP row** — colored gutter globe · bold colored method text (not a pill) · status pill (code,
`ERR`, or a braille spinner in accent while pending) · path · optional `MOCK` and `DUP ×N` pills
at the right end of the centre column · size · duration.
*Pending:* size blanks, duration counts up live in accent.
*Muted (noise):* row shrinks to 26px and every color fades 66% toward the background; no hover
affordances at all; clicks are swallowed.
*Hover:* size column becomes **`⧉ cURL`**, duration column becomes **`⇉ flow`** — two 72px and
64px hit bands with a deliberate 4px dead gap between them.

**FCM row** — bell glyph · *empty method column* · kind tag (`TOKEN` accent / `NOTIF` purple /
`DATA` dim) · summary (notification title, or the `channel` data key, or collapse key, or from) ·
optional **`INJ`** pill · "3 keys" · time. Hover reveals `⇉ flow` only.

**Generic row** (db / worker / config / analytics / app-defined) — kind glyph · *both method and
status columns empty* · `title  ·  subtitle` · a second full-word fact in the count column
(never a `+N`) · duration or time. **Never spins**, deliberately.

> **Known problem.** The generic row leaves 92px of the left band permanently blank to preserve
> alignment with HTTP rows, and the FCM row leaves 46px. In a 400px-wide panel that is a quarter
> of the row spent on emptiness.

### 4.5 Mocks strip

Appears under the filter bar when rules or scenarios exist. Header: purple **`MOCKS`** label,
a **sync dot** with five states, and right-aligned `Scenarios (N) ▾` / `Disable all`.

Sync dot states, in evaluation order — this is a trust surface and the wording matters:

| State | Dot | Text |
|---|---|---|
| No device yet | amber | `waiting for device — needs logpose-android ≥ 1.1.0 + capture running` |
| Failed | red | `<pkg> · not synced — rules may not be live` (tooltip carries the failure) |
| Capture stopped | grey | `<pkg> · capture stopped — device rules cleared` |
| Pending | amber | `<pkg> · syncing rev 12…` |
| Synced | green | `<pkg> · synced rev 12` |

Each rule is a rounded card: enable switch · method · path pattern · then right-aligned chips —
`lib ≥ 1.7.0` (withheld) · `only when` · `×3 steps` · outcome pill (`MERGE` / `TIMEOUT` / `FAILED`
/ status code) · latency · hit count · diff · edit · delete. A disabled rule fades every color 50%.

### 4.6 HTTP detail

**Overview hero card** (rounded, header band, "Overview" + copy glyph), body in order: duplicate
banner (severity-colored: red "Possible double-submit" / amber "Redundant duplicate request" /
grey "Repeated request") → **status pill** (prefixed `MOCK · ` when mocked) + **method pill** →
URL in mono green → error strip (only when there is an error and no response) → **stat chips** →
two buttons (`</> Copy as cURL` filled, `Copy JSON` ghost).

Stat chips, conditionally: `duration` (live while pending) · `size` · `started` · `host` ·
`req id`/`trace id` (sniffed from 9 candidate headers, response first) · `id` (only when no server
trace found) · **`trace`** (LogPose's own, **clickable** → opens the waterfall). The server
`x-request-id` chip is deliberately *not* clickable — only LogPose traces open a waterfall.

Below: **Request | Response** JSON panels side by side. Request headers shown by default,
response headers hidden — request auth is useful, response CSP is noise.

### 4.7 FCM detail

Kind pill + "Push message"/"Token refresh" → **injected banner** (`⚡ Injected by LogPose —
delivered into the app on your behalf, not by Firebase`) → summary → stat chips (`channel`, `at`,
`sent`, `from`, `priority`, `ttl`, `collapse`, `data`, **`trace`** clickable, `parent`, `id`) →
copy button. Below, the composed payload as a JSON tree.

### 4.8 Generic detail

Type glyph + kind pill + title → subtitle → **badges** (tone-mapped: info accent / warn amber /
error red / muted dim; a badge that merely repeats the kind is dropped) → stat chips (`at`,
`took`, `state`, `trace` clickable, `parent`, `id`) → **typed section cards**: `kv` renders as a
two-column mono table, `text` as wrapped prose, `code`/`json` as scrollable mono capped at 160px.

### 4.9 Waterfall

**Header:** headline = the grouping (`order_id  21053953`), subline = `7 events · spanning 4.20s ·
slowest GET /app/v4/…/order/21053953/ (1.38s)`, right-aligned links `Find by value…` /
`Correlation keys…`, and a **segmented switcher** (`order_id` / `trip_id` / `trace`) that appears
only when more than one grouping exists.

**Canvas:** 190px gutter (kind glyph + row label) · time axis with 5 gridlines labelled `+0ms`,
`+1.05s`… · lanes 24px tall. A **point** is an 8px filled dot; a **span** is a 10px rounded bar
(70-alpha fill, solid stroke); an **open span** pulses its fill 36→80 alpha and trails a spinner;
an **untimed** event is a hollow grey dot pinned at the axis start. Lane color is the kind hue,
except a failed HTTP lane which goes red. Click a lane to select that row (with a toast if the
row is currently filtered out).

**Empty states differ by grouping kind:** `no events carry that value` vs `no events — the trace
may have scrolled out of the capture buffer`.

### 4.10 JSON panels

Card with a header (title, status, and a **wrapping** action row: `Tree|Raw` segmented toggle,
optional `Headers ▾` chip, Find, Expand all, Collapse all, Copy). Tree mode: mono 13, syntax
colors, `{n}`/`[n]` counts, and a preview of the first 4 keys shown only while collapsed,
pre-expanded to depth 3. Raw mode: a real IDE JSON editor with folding, line numbers, PSI-driven
key recoloring, and auto-collapse below depth 3 (skipped for documents under 40 lines). Find bar
forces Raw mode, highlights every match, and counts `3/17`.

**JsonPatchTree** (mock editing): per-leaf override checkboxes that appear only in merge mode,
a bottom inspector strip (Field / Value / type / Remove), add-field, and changed values shown in
bold accent with the original struck through beside them.

### 4.11 Dialogs

| Dialog | Shape | Notes |
|---|---|---|
| **Mock rule** | 720×660, scrollable, three sections: *When a request matches* (method + path glob, `▸ Only when…` disclosure for query/header/body matchers) · *Then* (mode, behavior, latency, serve limit) · *Response* (status/content-type/headers, body as **fields or text**, or a sequential **step list** of cards) | Everything is **hidden, never disabled**. Has a native diff ("Original response vs your mock"). Validation is per-step and specific. |
| **Compose push** | 620×500, *Envelope* (from, collapse key) · `▸ Notification (optional)` · *Data payload* JSON editor | OK reads "Send". Rejects nested values — a real push can't carry them. |
| **Save scenario** | 520×210, name + note + optional "Only successful (2xx)" + live preview + live filename line | Filename updates per keystroke; warns when it will replace an existing scenario. |
| **Correlation keys** | 600×330, explanatory copy + a 4-column table (enabled / Key / Short / "In this capture") + `Suggest from capture` | Suggestions seed once, always unticked, with evidence (`groups 5 events · largest 5 · latest 21053953`). Validation is side-effect-free because the platform revalidates on a timer. |
| **Find by value** | 460×108, one field + a live preview line | 180ms debounce; OK ("Show waterfall") is enabled only when the preview found something; too-short values say so out loud. |

### 4.12 Notifications, toasts, empty states

**Notifications** (platform balloons + Event Log) for session-level problems: rules withheld,
rules may not be live, no app to push to, device library too old, push reached no handler, push
not acknowledged, scenario saved/loaded/failed. **Toasts** (1400ms, above the component) for
confirmations: "cURL copied", "Push delivered (handler)", "That row is hidden by the current
filter". The two are deliberately separate: a problem must not vanish in 1.4 seconds, and a copy
confirmation must not need dismissing.

**Empty capture** is a numbered setup guide ending in a "Setup guide →" link. **There is no
distinct "filtered to nothing" state** — the same setup guide appears, which is wrong when the
user has 200 events and an over-tight filter.

---

## 5. Features (what the product does)

1. **Capture** — start/stop, auto-reattach (5 × 2s), clear, session boundaries on app restart.
2. **Unified timeline** — 7 event kinds in one stream, in-flight rows updating live.
3. **Filtering** — text, kind, method, status class, noise-muting, duplicates-only, correlation
   key.
4. **Duplicate detection** — flags repeated requests in a 1.5s window with three severities.
5. **Inspection** — per-kind detail, JSON tree/raw, copy as cURL/JSON/URL/body/timeline.
6. **Mock & replay** — rules with glob paths, query/header/body matchers, sequential responses,
   latency, timeout and connection-failure injection, merge-vs-replace body editing, hit counts,
   kill switches, ack-verified device sync.
7. **Scenario suites** — snapshot a session into committable `.logpose/scenarios/*.json`, load
   merge-or-replace for offline demo mode.
8. **Push injection** — compose or re-send an FCM message into the running app, marked `INJ`.
9. **Correlation** — user-defined business keys (`order_id`) group a flow across traces; find by
   pasted value; waterfall by key or trace.
10. **Agent access (MCP)** — 21 tools over the IDE's built-in server, including `await_event` for
    trigger→await→assert loops.

---

## 6. Principles the current design already commits to

Keep these; they are the product's spine.

- **The timeline never lies.** Anything LogPose caused is marked: `MOCK`, `INJ`, `MOCK ·` status
  prefix, sync states that say "may not be live" rather than showing green.
- **State the type once.** The kind is carried by one colored glyph; badges that merely repeat it
  are dropped.
- **Never scan during paint.** Affordances read from a cache and simply don't render for a frame
  rather than blocking the UI.
- **Hidden, not disabled.** Irrelevant controls disappear; they never sit greyed out.
- **Say why, not just what.** Empty states and errors explain the mechanism ("the app mints its
  own trace…") instead of reporting a null result.
- **One option is a statement, not a choice** — the waterfall switcher hides itself at n=1.

---

## 7. The brief: known problems and open questions

Ranked by how much they cost the user.

1. **Three colliding color palettes** (§3.2). Amber means six things; purple five; green four.
   Can method, status and kind be re-encoded so a row's colors are unambiguous — perhaps by giving
   only *one* of the three axes color and the others shape, weight or position?
2. **Dead space in non-HTTP rows** (§4.4). 46–92px of every FCM/generic row is empty padding
   holding a column grid that only HTTP uses. Is strict column alignment worth it, or should each
   kind get its own rhythm with a shared left edge?
3. **The filter bar is a wall.** Search + 7 type chips + 4 method chips + 4 status chips + a
   switch + a dupes chip + a correlation chip + overflow + counter, in one row, with three
   different grouping treatments. It wraps badly in a narrow panel. What earns permanent space,
   and what belongs in the overflow?
4. **Hidden mode switches.** Picking a method silently hides every non-HTTP event; DB is invisible
   until asked for. Both are defensible behaviors with no visual explanation.
5. **No "filtered to nothing" state** — users see a setup guide for a tool they've already set up.
6. **Hover-only affordances.** `⧉ cURL` and `⇉ flow` are discoverable only by accident, and they
   live in hit bands defined in pixels from the right edge.
7. **Unformalized components** — 7 hand-rolled link labels, 4 icon buttons, chips with no hover
   state, switches with no disabled state.
8. **The waterfall is new and least designed.** Its lane rendering, axis and switcher have never
   had a visual pass, and it's the surface that most wants to be beautiful.
9. **Density vs. breathing room.** Everything above must survive in a 300px-tall panel showing 8
   rows. Any redesign that trades event count for elegance loses.

---

## 8. What would be most useful back

In rough priority:

1. **A color system proposal** that resolves §7.1 — the full token set, both themes, with the
   method/status/kind encoding worked out and contrast-checked on `#F7F8FA` and `#1E1F22`.
2. **The timeline row**, all three variants, at 34px and at the 26px muted height, showing
   selection, hover, pending, mocked, injected and duplicate states.
3. **The filter bar** rethought for a 400px-wide panel.
4. **The waterfall**, given the design attention it hasn't had.
5. **A component sheet** for the pieces that recur: pill, chip, stat chip, switch, segmented
   control, link, icon button — with the states they're currently missing.

Everything must be expressible in custom-painted Swing: flat fills, 1px strokes, rounded rects,
text, and 16px single-color SVG icons. If a proposal needs more than that, say so explicitly so we
can judge the cost.
