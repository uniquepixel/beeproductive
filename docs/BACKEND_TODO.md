# Backend TODO & Frontend Progress (Swarm + Intervention overlays)

Snapshot of what the latest frontend pass implemented for the **Swarm Overlay** and
**Intervention Overlay**, what is a deliberate placeholder, and what the backend still owns.
See also [`QUEENBEE_UI_STATE_INTEGRATION.md`](QUEENBEE_UI_STATE_INTEGRATION.md).

---

## Done (frontend)

### Swarm overlay (`BeeManager`, `SingleBee`, `ShakeDetector`)
- **Honey-bottle side indicator** — `honey_bottle_full/medium/low` briefly flash at the screen
  side when the honey band worsens (band keyed off the live score: full `<35`, medium `35–65`,
  low `65+`). Fully score-driven.
- **Edge transparency** — bees fade with distance to the nearest edge (`clampedEdgeAlpha`), fully
  transparent at the edge.
- **Swipe/shake lag fix** — replaced the per-bee-per-frame main-thread post (+ per-bee lock +
  `updateViewLayout`) with **one batched post per frame**, snapshot the despawning set once, and
  skip no-op layout updates. Swipe no longer mass-despawns/respawns (that churn was the lag).
- **Shake-to-flee** — `ShakeDetector` (accelerometer, no new permission) scatters the swarm at any
  stage where bees exist. Swipe + shake share one `flee(...)` path: bees rush to the nearest edge
  and **linger longer at low score, shorter when severe** (`fleeLingerForScore`, 5s → 1s).

### Intervention overlay (`OverlayService`, `QueenBeeChatManager`, `ProductivityEngine`)
- **Entrance animations** — overlay fade-in + chat card slide-up + Queen slide-in (`ViewPropertyAnimator`).
- **Single-line dialogue** — driven by `QueenBeeChatManager.getUiState()` LiveData; big box shows
  only the latest line (user's while Queen thinks, Queen's while user types), crossfaded.
- **Queen moods** — 8-state `QueenMood` enum mapped to placeholder drawables; THINKING during
  network calls, `TALKING_1/2` animates while she "talks".
- **Confrontation screenshot** — the last `ActivityLog` screenshot is shown behind the chat.
- **Decision** — AI emits `[DECISION: REFILL|KICK]` (parsed + stripped); 2-min hard timeout →
  KICK. REFILL plays the 3-frame honey-fill then `resetScore()`. KICK calls
  `ProductivityEngine.blockApp(pkg, 5min)` (BLOCKED cooldown) + `enforceLockout()` (HOME).
- **De-duplication** — all chat UI now flows through the `getUiState()` observer; the old
  setText/mood-in-callback logic was removed.

---

## Placeholders to replace (art only — names are final)
- `res/drawable/queen_{sad,happy,talking_1,talking_2,showing_honey,exclaiming,asking,thinking}.xml`
- `res/drawable/honey_bottle_{full,medium,low}.xml`
- `res/drawable/honey_fill_{1,2,3}.xml`

Swap the file contents; no code changes needed (mappings live in `OverlayService.moodToDrawable`
and `BeeManager.showHoneyBottle`).

---

## Backend still owns
1. **Real win-criteria.** The `[DECISION: ...]` token + 2-min timeout is a working stand-in. If the
   model produces structured output, translate it to a `QueenBeeUiState.Decision` and `postValue`
   it on `getUiState()` (see the integration doc). The token parser in
   `QueenBeeChatManager.handleQueenReply` is the only thing to swap.
2. ~~**Backend-driven moods.**~~ **Done.** The system prompt now asks the Queen to begin every
   reply with a hidden `[MOOD: X]` tag (one of the `QueenMood` values except `THINKING`).
   `handleQueenReply` parses & strips it (`MOOD_PATTERN`) and, via `pickMood(...)`, lets the
   model-chosen mood drive `QueenBeeUiState.mood`. It falls back to the old network-lifecycle moods
   (`TALKING_1`, and `HAPPY`/`EXCLAIMING` for a decision) when the model emits no tag, so the
   overlay needs no changes. To move this to a real backend, post the mood on the same LiveData
   instead of parsing a tag.
3. **Tuning.** `DECISION_TIMEOUT_MS` (2 min), `KICK_BLOCK_MS` (5 min cooldown), shake threshold
   (`ShakeDetector.SHAKE_THRESHOLD_G`), and flee linger bounds are constants — adjust to taste.

## Known limitations / not done
- Sessions remain **in-memory only** (unchanged); a process death loses the conversation.
- The swipe touch-layer still only exists at high score (`SWIPE_LAYER_SCORE`); **shake** is what
  provides fleeing in the earlier stages (kept this way to avoid intercepting touches for the
  underlying app at low scores).
- Honey-fill animation is overlay-local (triggered by the REFILL decision); it is not yet a
  backend-synced frame stream.
