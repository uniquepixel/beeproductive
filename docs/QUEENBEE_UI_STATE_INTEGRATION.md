# Queen Bee UI State — Backend Integration Contract

This documents the **already-implemented frontend** for the intervention overlay's Queen Bee
chat, and exactly what the backend must do to drive it. It is the companion to
[`FRONTEND_INTEGRATION.md`](FRONTEND_INTEGRATION.md): that file covers *running the chat*
(`startSession` / `sendMessage` / `getHistory`); **this** file covers the new **UI-state channel**
that carries the Queen's mood, the single visible line, and the final verdict.

> **Design rule (matches the score system):** the frontend never reaches into the backend. It
> observes one `LiveData` and renders it — exactly like `ProductivityEngine.getState()`. The
> backend drives the UI purely by **posting a new `QueenBeeUiState`** to that same LiveData.

---

## The channel

`QueenBeeChatManager` exposes:

```java
LiveData<QueenBeeUiState> getUiState();
```

The intervention overlay (`OverlayService`) does `observeForever(...)` on it and renders every
emission. **Whatever you post here is what the Queen shows.**

### `QueenBeeUiState` (immutable)

| Field | Type | Meaning |
|---|---|---|
| `mood` | `QueenMood` | Which Queen face to show (see enum below). |
| `thinking` | `boolean` | `true` while the AI is generating — overlay shows the THINKING face and ignores `mood`. |
| `speaker` | `Speaker` | `USER`, `QUEEN`, or `NONE` — who owns the visible line. |
| `text` | `String` | The **single** last line of dialogue (the big box only ever shows this). Empty string = leave the box unchanged. |
| `decision` | `Decision` | `NONE`, `REFILL`, or `KICK`. A non-`NONE` value triggers the end-game (below). |
| `showScreenshot` | `boolean` | `true` once the Queen holds up her screenshot evidence (sticky for the rest of the session). |
| `screenshotBase64` | `String` | The evidence screenshot (base64 JPEG), `null` when none exists. |
| `screenshotCaption` | `String` | The AI's one-line description of the screenshot, `null` when unavailable. |

`QueenMood`: `SAD, HAPPY, TALKING_1, TALKING_2, SHOWING_HONEY, EXCLAIMING, ASKING, THINKING`.
Each maps to a drawable in `OverlayService.moodToDrawable(...)` (currently **placeholder**
vector art — `res/drawable/queen_*.xml` — swap these for the real images, names unchanged).

`Speaker`: `NONE, USER, QUEEN`. `Decision`: `NONE, REFILL, KICK`.

---

## What the frontend already does for you

Today `QueenBeeChatManager` *is* the stand-in backend. It posts `QueenBeeUiState` at exactly the
points the real backend should:

| Moment | Posted state |
|---|---|
| `startSession()` | `mood=THINKING, thinking=true, speaker=QUEEN, text=""` (the Queen also takes + analyses a fresh evidence screenshot now) |
| Opening line ready | `mood=<model's [MOOD: X] tag, else TALKING_1>, thinking=false, speaker=QUEEN, text=<greeting>`, usually **plus** `showScreenshot=true` (her prompt tells her to present the evidence in the first reply) |
| `sendMessage()` (immediately) | `mood=THINKING, thinking=true, speaker=USER, text=<user line>` |
| Queen reply ready | `mood=<model's [MOOD: X] tag, else TALKING_1>, thinking=false, speaker=QUEEN, text=<reply>` |
| Reply contains `[SHOW_SCREENSHOT]` | as above **plus** `showScreenshot=true` + `screenshotBase64`/`screenshotCaption` (sticky: every later post keeps carrying them) |
| Reply contains a decision token | as above **plus** `decision=REFILL`/`KICK`; mood is the model's tag, else `SHOWING_HONEY`/`EXCLAIMING` |
| 2-minute timeout, no decision | `decision=KICK` (the chosen fallback) |
| Network error | `mood=ASKING, speaker=QUEEN, text="The Queen is speechless: ..."` |

### The decision signal (fills the old "win criteria" TODO)

The system prompt now instructs the Queen to end a reply with a hidden token once she decides:

```
[DECISION: REFILL]   → user convinced you: refill the honey
[DECISION: KICK]     → user must stop now
```

`handleQueenReply(...)` parses the token, **strips it from the displayed text**, records the
decision once per session, and cancels the 2-minute timeout. A hard timeout
(`DECISION_TIMEOUT_MS = 2 min`) posts `KICK` if the Queen never decides.

### The mood signal (model-driven `QueenMood`)

The system prompt also asks the Queen to **begin every reply** with a hidden mood tag:

```
[MOOD: X]   X ∈ { TALKING_1, TALKING_2, ASKING, EXCLAIMING, SAD, HAPPY, SHOWING_HONEY }
```

`handleQueenReply(...)` parses & strips it (`MOOD_PATTERN`) and `pickMood(...)` uses the
model-chosen mood for `QueenBeeUiState.mood`. Unknown values (and `THINKING`, which is reserved for
the in-flight network state) are ignored and fall back to the old lifecycle default: `TALKING_1`,
or `SHOWING_HONEY`/`EXCLAIMING` when a decision is present. The overlay renders whatever mood it
receives, so no overlay change was needed.

### The screenshot signal (evidence presentation)

The system prompt instructs the Queen to present her screenshot evidence in her **first** reply
with a hidden tag:

```
[SHOW_SCREENSHOT]   → the frontend displays the evidence screenshot
```

`handleQueenReply(...)` parses & strips it and marks the screenshot as revealed on the session;
from then on every posted `QueenBeeUiState` carries `showScreenshot=true` plus the image and its
caption, so the overlay can render it purely from the LiveData.

---

## What the overlay does with each field (so you don't double-drive it)

- **`text`** → shown in the big box, with a crossfade. Only the latest line is ever shown — the
  user's while the Queen is thinking, the Queen's while the user types. Post `""` to leave it.
- **`thinking`** → THINKING face. Otherwise `TALKING_1/2` runs a 2-frame talking animation; any
  other mood is shown as a static face. `SHOWING_HONEY` is the **reward pose** (the Queen handing
  over the refilled honey) — it is the default face for a `REFILL` decision, not evidence-showing.
- **`showScreenshot` + `screenshotBase64` + `screenshotCaption`** → the overlay decodes the image
  off the main thread and fades it in (plus a "Caught in the act: …" caption) the first time
  `showScreenshot` is `true`; it stays visible for the rest of the session. The overlay no longer
  pulls `getLastScreenshot()` itself.
- **`decision`** (a visible **verdict banner** pops in the moment either decision arrives)
  - `REFILL` → banner "🍯 Honey refilled!", the 3-frame honey-fill animation plays, and the
    Queen's final line stays readable for ~6 s before `ProductivityEngine.resetScore()`
    (score → 0, chat ends, overlay closes).
  - `KICK` → banner "🚫 The Queen says: out!", EXCLAIMING face, final line stays for ~4.5 s, then
    `ProductivityEngine.blockApp(pkg, 5min)` — marks the app `BLOCKED` for a cooldown (re-entry is
    bounced by the existing BLOCKED check) and sends the user HOME.
  - Acted on **exactly once** per overlay session (guarded by `lastDecision`).

---

## To hand this to a real backend

1. Keep `QueenBeeChatManager.getUiState()` as the single UI channel (don't add a parallel one).
2. Move the *decisions* (mood + `Decision`) to wherever your model logic lives, and `postValue(...)`
   a `QueenBeeUiState` at the same lifecycle points listed above. The overlay needs no changes.
3. If your model emits structured output instead of the `[DECISION: ...]` token, just translate it
   into a `Decision` before posting — the token parsing in `handleQueenReply` is the only thing to
   replace.
4. Replace the placeholder `queen_*` / `honey_*` drawables with real art (same resource names).

That's the whole contract: **observe one LiveData, post one immutable state.**
