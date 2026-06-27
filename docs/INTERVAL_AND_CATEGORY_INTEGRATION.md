# Score Speed + "Not an App" — Frontend Integration Guide

This guide is for the **frontend** of beeproductive. Two new backend hooks are ready
for you. You don't touch the database, the scheduler, or the scoring math — everything
you need is on the `ProductivityEngine` singleton:

```java
ProductivityEngine engine = ProductivityEngine.getInstance();
```

The two features:

1. **A score-speed slider** — make the score build up faster (great for demos).
2. **A "Not an app" category** — mark packages (keyboards, system stuff, anything) as
   neither productive nor unproductive.

> Reminder from the other guides: `ProductivityEngine.getState()` is a
> `LiveData<ProductivityState>` and its updates are safe to observe on the main thread.
> The two setters below are fire-and-forget — call them from the UI thread, they do
> their own background work.

---

## Part 1 — The score-speed slider

The score is increased/decreased on a repeating timer (a "tick"). By default it ticks
**once per minute**. Shorten the interval and the score climbs (and cools down) faster —
perfect for showing the whole 0→100 journey in a demo without waiting an hour.

### The API

| Method | What it does |
|---|---|
| `long getScoreIntervalMillis()` | Current tick interval in **milliseconds**. Default `60000` (1 min). |
| `void setScoreIntervalMillis(long millis)` | Sets the tick interval. Clamped to a **1000 ms (1 s) floor**, **persisted across app restarts**, and applied **immediately**. |

### Wiring up a slider

Map your slider so dragging toward "fast" gives a **smaller** interval. A simple
1 s … 60 s range works well:

```java
// e.g. a SeekBar with max = 59  →  1..60 seconds
seekBar.setProgress((int) (engine.getScoreIntervalMillis() / 1000) - 1); // restore current value

seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
    @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        long seconds = progress + 1L;            // 1..60
        engine.setScoreIntervalMillis(seconds * 1000L);
        label.setText(seconds + "s / tick");
    }
    @Override public void onStartTrackingTouch(SeekBar sb) {}
    @Override public void onStopTrackingTouch(SeekBar sb) {}
});
```

### Good to know

- **Persistence is automatic.** Whatever you set survives an app restart — no need to
  save it yourself. On a fresh install it starts at `60000`.
- **It takes effect immediately.** The next tick happens after the new interval.
- **It also speeds up the AI screenshots.** Screenshot/analysis cadence is counted in
  ticks, so a faster interval makes those fire more often too. That's intended for demos,
  just don't be surprised by extra screenshot activity at very fast settings.
- **Label it as a demo/dev control.** For real usage you'd typically leave it at 1 min.

---

## Part 2 — The "Not an app" (NEUTRAL) category

Every package on the device is its own "app" — including the keyboard, the launcher, and
system UI. Some of those aren't things the user *chooses to use*, so categorizing them as
Productive or Unproductive makes no sense. The new **NEUTRAL** status means exactly
**"not an app"**:

- It **never changes the score**.
- It **never prompts** the user (no categorization dialog, no AI-consent ask).

### Marking an app NEUTRAL

```java
engine.setAppNeutral(packageName);
// equivalent to: engine.updateAppPolicy(packageName, AppStatus.NEUTRAL.name(), 0);
```

That's it — the package is stored as NEUTRAL and immediately stops affecting the score.

### Add it to your categorization dialog

Today your categorization dialog offers **Productive / Unproductive**. Add a third option —
e.g. a "Not an app" radio button — and route it to `setAppNeutral(packageName)` instead of
`updateAppPolicy(..., "PRODUCTIVE"/"UNPRODUCTIVE", severity)`:

```java
if (selected == R.id.radioNotAnApp) {
    engine.setAppNeutral(packageName);
} else {
    String status = (selected == R.id.radioProductive) ? "PRODUCTIVE" : "UNPRODUCTIVE";
    int severity = severitySeekBar.getProgress() + 1;
    engine.updateAppPolicy(packageName, status, severity);
}
```

### How NEUTRAL shows up in state

A NEUTRAL app behaves like a "nothing to see here" app in `ProductivityState`:

| Getter | For a NEUTRAL app |
|---|---|
| `isCheckRequiredForUnknownApp()` | `false` — don't show the categorization dialog. |
| `isAiConsentRequired()` | `false` — don't ask for AI consent. |
| `getScore()` | Unchanged while this app is foreground. |

So if you already gate your categorization dialog on `isCheckRequiredForUnknownApp()`
(as `MainActivity` / `OverlayService` do), NEUTRAL apps simply never trigger it. No extra
work needed there.

### Automatic NEUTRAL

Launchers, the keyboard, and SystemUI now **auto-classify as NEUTRAL** the first time
they're seen (previously they were silently skipped). You don't need to handle them — they
just won't pester the user or move the score. If you list apps from the DB, expect to see
these with status `NEUTRAL`.

---

## Quick reference

```java
ProductivityEngine e = ProductivityEngine.getInstance();

// Score speed (ms per tick; default 60000, min 1000, persisted)
long current = e.getScoreIntervalMillis();
e.setScoreIntervalMillis(2000);   // demo: tick every 2s

// "Not an app"
e.setAppNeutral("com.some.keyboard");
```
