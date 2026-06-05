# Score Integration Guide

How the 0–100 productivity score is computed and how any frontend component can observe and react to it.

---

## Overview

The score lives entirely inside `ProductivityEngine` (a singleton). Frontend components never read or write the score directly — they observe a `LiveData<ProductivityState>` that the engine publishes whenever something changes.

```
AccessibilityService          ProductivityEngine             Frontend
  onAppChanged()  ─────────►  tickScoreLogic() every 1 min  ──► LiveData<ProductivityState>
                               postStateUpdate()                     │
                                                                      ├── MainActivity.observe()
                                                                      └── OverlayService.observeForever()
```

---

## ProductivityState — what you actually get

`engine/ProductivityState.java`

| Getter | Type | Description |
|---|---|---|
| `getScore()` | `int` 0–100 | Raw score. Higher = more unproductive. |
| `getLevel()` | `int` 0–4 | Bucketed from score (see table below). |
| `isShowInterventionOverlay()` | `boolean` | `true` when score == 100. Show blocking overlay. |
| `getCurrentPackageName()` | `String` | Package currently in foreground. |
| `isCheckRequiredForUnknownApp()` | `boolean` | `true` when user opened an uncategorized app. Show categorization dialog. |
| `isGeminiConsentRequired()` | `boolean` | `true` when Gemini consent not yet given for this app. |

### Score → Level mapping

| Score range | Level | Bee count (`level * 3`) |
|---|---|---|
| 0 – 20 | 0 | 0 bees |
| 21 – 50 | 1 | 3 bees |
| 51 – 75 | 2 | 6 bees |
| 76 – 99 | 3 | 9 bees |
| 100 | 4 | 12 bees + intervention overlay |

---

## How the score changes

`tickScoreLogic()` runs every minute in a background thread.

| Situation | Score change |
|---|---|
| Using an **unproductive** app, severity 1 | +5 per minute |
| Using an **unproductive** app, severity 2 | +10 per minute |
| Using an **unproductive** app, severity 3 | +20 per minute |
| Using a **productive** app | −15 per minute (cooldown) |
| App is **unknown** or **blocked** | no change |

Score is clamped to `[0, 100]`. When it hits 100, `isShowInterventionOverlay()` becomes `true`. The user closes the overlay by clicking the button, which calls `ProductivityEngine.getInstance().resetScore()` and resets to 0.

---

## How to observe the score in a new component

### In an Activity or Fragment (lifecycle-aware)

```java
// 1. Init once — safe to call multiple times
ProductivityEngine.getInstance().init(getApplicationContext());

// 2. Observe — auto-unsubscribes when the lifecycle owner is destroyed
ProductivityEngine.getInstance().getState().observe(this, state -> {
    int score = state.getScore();
    int level = state.getLevel();
    // update your UI here
});
```

> `this` is a `LifecycleOwner` (Activity/Fragment). The observer is automatically removed when the component is destroyed — no manual cleanup needed.

### In a Service (no lifecycle)

```java
private final Observer<ProductivityState> stateObserver = state -> {
    // update UI here
};

// Register (e.g. in onStartCommand)
ProductivityEngine.getInstance().getState().observeForever(stateObserver);

// Unregister (e.g. in onDestroy) — REQUIRED, otherwise leaks
ProductivityEngine.getInstance().getState().removeObserver(stateObserver);
```

`observeForever` has no lifecycle awareness, so you **must** call `removeObserver` in `onDestroy`.

---

## Existing observers

| Component | Where | What it does with the state |
|---|---|---|
| `MainActivity` | `frontend/MainActivity.java:51` | Calls `updateBeeCount(level)` when level changes; shows categorization dialog for unknown apps |
| `OverlayService` | `services/OverlayService.java:44` | Updates system-overlay bees; shows/hides the full-screen intervention overlay |

---

## Things you should NOT do

- **Don't write to the score directly.** There is no public setter. All score changes go through `tickScoreLogic()` or `resetScore()`.
- **Don't hold a reference to `ProductivityState`.** It's immutable and replaced on every update. Always read from the latest emission.
- **Don't call `observe()` from a background thread.** LiveData observation must happen on the main thread. The engine internally uses `postValue()` so updates from background threads are safe.
